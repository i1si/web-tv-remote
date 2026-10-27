package io.github.webtvremote.input

import android.content.Context
import android.util.Base64
import android.util.Log
import dadb.AdbKeyPair
import dadb.AdbStream
import dadb.Dadb
import io.github.webtvremote.App
import io.github.webtvremote.RemoteState
import io.github.webtvremote.RemoteState.Adb
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Connects to the device's own adbd over the loopback network ("Network debugging"), which is the
 * only non-root way for a regular app to send arbitrary key events (D-pad, menu, power...).
 *
 * Once connected it starts [io.github.webtvremote.injector.InjectorMain] as the shell user and
 * talks to it over a local socket; `input keyevent` through the ADB shell is the slow fallback.
 */
class AdbBridge(private val context: Context) {
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "adb") }
    // Timers that must fire even while the worker is blocked inside a connection attempt.
    private val health = Executors.newScheduledThreadPool(2) { Thread(it, "adb-health").apply { isDaemon = true } }
    private val keyPair: AdbKeyPair by lazy { loadKeyPair() }

    @Volatile private var dadb: Dadb? = null
    @Volatile private var injectorStream: AdbStream? = null
    private var injector: Injector? = null
    private val injectorLock = Any()

    val connected get() = dadb != null

    // Grows after each failure so a declined "Allow debugging?" prompt does not pop up constantly.
    private var retrySeconds = MIN_RETRY_SECONDS

    fun start() {
        worker.execute { connect() }
        health.scheduleWithFixedDelay({ checkHealth() }, HEALTH_SECONDS, HEALTH_SECONDS, TimeUnit.SECONDS)
    }

    /**
     * A link can die silently (adbd restarted, debugging switched off). Probe it with a timeout
     * and, if it does not answer, close it so that everything blocked on it fails and we reconnect.
     */
    private fun checkHealth() {
        val d = dadb ?: return
        val probe = health.submit<Boolean> { runCatching { d.shell("echo ok").output.trim() == "ok" }.getOrDefault(false) }
        val ok = runCatching { probe.get(5, TimeUnit.SECONDS) }.getOrDefault(false)
        if (ok) return
        Log.w(TAG, "ADB link is dead, reconnecting")
        d.close()
        worker.execute { if (dadb === d) connect() }
    }

    /** Called on screen-on and by the user: reconnect right away if the link was lost. */
    fun ensureConnected() = worker.execute {
        if (!isAlive()) {
            retrySeconds = MIN_RETRY_SECONDS
            connect()
        }
    }

    private fun isAlive(): Boolean {
        val d = dadb ?: return false
        val probe = health.submit<Boolean> { runCatching { d.shell("echo ok").output.trim() == "ok" }.getOrDefault(false) }
        return runCatching { probe.get(5, TimeUnit.SECONDS) }.getOrDefault(false)
    }

    private fun connect() {
        disconnect()
        set(Adb.CONNECTING)
        val port = App.of(context).prefs.adbPort
        val socket = Socket()
        // The handshake blocks until the user answers "Allow debugging?" on the TV (or forever if
        // adbd never replies). Closing the socket aborts it so the worker is never stuck.
        val watchdog = health.schedule({ runCatching { socket.close() } }, AUTH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        try {
            set(Adb.WAITING_AUTH)
            val d = LoopbackDadb.connect(socket, port, keyPair)
            if (d.shell("echo ok").output.trim() != "ok") throw IllegalStateException("shell did not answer")
            watchdog.cancel(false)
            dadb = d
            retrySeconds = MIN_RETRY_SECONDS
            set(Adb.CONNECTED)
            Log.i(TAG, "ADB connected on port $port")
            grantSelfPermissions(d)
            autoEnableAccessibility()
            startInjector(d)
        } catch (e: Exception) {
            Log.w(TAG, "ADB connection failed: $e")
            watchdog.cancel(false)
            runCatching { socket.close() }
            disconnect()
            set(Adb.UNAVAILABLE)
            worker.schedule({ if (dadb == null) connect() }, retrySeconds, TimeUnit.SECONDS)
            retrySeconds = (retrySeconds * 2).coerceAtMost(MAX_RETRY_SECONDS)
        }
    }

    private fun disconnect() {
        synchronized(injectorLock) {
            injector?.close()
            injector = null
        }
        runCatching { injectorStream?.close() }
        injectorStream = null
        runCatching { dadb?.close() }
        dadb = null
        RemoteState.injectorReady = false
    }

    private fun set(state: Adb) {
        RemoteState.adb = state
        RemoteState.changed()
    }

    private fun grantSelfPermissions(d: Dadb) {
        val pkg = context.packageName
        // The overlay is used to show the pairing PIN and allows launching apps from the background.
        // Many TV builds have no UI to grant it, but the shell user can.
        runCatching { d.shell("appops set $pkg SYSTEM_ALERT_WINDOW allow") }
        // Keep the service alive in doze so the remote keeps working after long standby.
        runCatching { d.shell("cmd deviceidle whitelist +$pkg") }
    }

    /** Turns the accessibility service on the first time ADB is available; never again after that. */
    private fun autoEnableAccessibility() {
        val prefs = App.of(context).prefs
        if (prefs.accessibilityAutoEnabled) return
        if (RemoteAccessibilityService.instance != null || enableAccessibility()) prefs.accessibilityAutoEnabled = true
    }

    /** Enables our accessibility service (keeps the services already enabled by the user). */
    private fun enableAccessibility(): Boolean {
        val d = dadb ?: return false
        val me = "${context.packageName}/${RemoteAccessibilityService::class.java.name}"
        return runCatching {
            val current = d.shell("settings get secure enabled_accessibility_services").output.trim()
            val services = current.split(':').filter { it.isNotBlank() && it != "null" }.toMutableList()
            if (me !in services) services += me
            d.shell("settings put secure enabled_accessibility_services ${services.joinToString(":")}")
            d.shell("settings put secure accessibility_enabled 1")
            true
        }.getOrDefault(false)
    }

    private fun startInjector(d: Dadb) {
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val token = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val apk = context.applicationInfo.sourceDir
        val main = "io.github.webtvremote.injector.InjectorMain"
        val stream = d.open("shell:CLASSPATH='$apk' app_process / $main $port $token")
        injectorStream = stream
        Thread({
            try {
                while (true) {
                    val line = stream.source.readUtf8Line() ?: break
                    Log.i(TAG, "injector: $line")
                    if (line.startsWith("READY")) {
                        val inj = runCatching { Injector(port, token) }.getOrNull() ?: break
                        synchronized(injectorLock) { injector = inj }
                        RemoteState.injectorReady = true
                        RemoteState.changed()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "injector stream closed: $e")
            }
            if (injectorStream === stream) {
                RemoteState.injectorReady = false
                RemoteState.changed()
                // Either the helper crashed or the whole ADB link died; recheck shortly.
                worker.schedule({ if (injectorStream === stream) connect() }, 3, TimeUnit.SECONDS)
            }
        }, "injector-log").apply { isDaemon = true }.start()
    }

    /** Sends one command to the injector. Returns its reply or null if it is unavailable. */
    private fun injectorCommand(cmd: String): String? = synchronized(injectorLock) {
        val inj = injector ?: return null
        val reply = inj.send(cmd)
        if (reply == null) {
            inj.close()
            injector = null
            RemoteState.injectorReady = false
            RemoteState.changed()
        }
        reply
    }

    /** mode: click | down | up | long */
    fun key(code: Int, mode: String): Boolean {
        injectorCommand("key $code $mode")?.let { return it == "ok" }
        val d = dadb ?: return false
        if (mode == "up") return true
        val flag = if (mode == "long") "--longpress " else ""
        return runCatching { d.shell("input keyevent $flag$code").exitCode == 0 }.getOrDefault(false)
    }

    /** Types text with the virtual keyboard map; false if it contains unmappable characters. */
    fun text(text: String): Boolean {
        val b64 = Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP)
        injectorCommand("text $b64")?.let { return it == "ok" }
        val d = dadb ?: return false
        if (text.any { it.code !in 0x20..0x7e }) return false
        val escaped = text.replace("'", "'\\''").replace(" ", "%s")
        return runCatching { d.shell("input text '$escaped'").exitCode == 0 }.getOrDefault(false)
    }

    fun shell(command: String): Boolean {
        val d = dadb ?: return false
        return runCatching { d.shell(command).exitCode == 0 }.getOrDefault(false)
    }

    private fun loadKeyPair(): AdbKeyPair {
        val priv = File(context.filesDir, "adbkey")
        val pub = File(context.filesDir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        return AdbKeyPair.read(priv, pub)
    }

    private class Injector(port: Int, token: String) {
        private val socket = Socket(InetAddress.getByName("127.0.0.1"), port).apply { soTimeout = 5000; tcpNoDelay = true }
        private val out: OutputStream = socket.getOutputStream()
        private val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

        init { out.write("$token\n".toByteArray()) }

        fun send(cmd: String): String? = runCatching {
            out.write("$cmd\n".toByteArray())
            out.flush()
            reader.readLine()
        }.getOrNull()

        fun close() = runCatching { socket.close() }
    }

    companion object {
        private const val TAG = "AdbBridge"
        private const val AUTH_TIMEOUT_SECONDS = 90L
        private const val HEALTH_SECONDS = 30L
        private const val MIN_RETRY_SECONDS = 15L
        private const val MAX_RETRY_SECONDS = 600L
    }
}
