package io.github.webtvremote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import io.github.webtvremote.server.Mdns
import io.github.webtvremote.server.WebServer
import java.io.IOException

/** Foreground service that keeps the web server and the ADB link running. */
class RemoteService : Service() {
    private val app by lazy { App.of(this) }
    private val main = Handler(Looper.getMainLooper())
    private var server: WebServer? = null
    private lateinit var overlay: PinOverlay
    private val mdns by lazy { Mdns(this) }

    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = app.adb.ensureConnected()
    }

    private val network = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(n: Network, lp: LinkProperties) {
            main.post { updateUrl() }
        }

        override fun onLost(n: Network) {
            main.post { updateUrl() }
        }
    }

    // While a PIN is shown, periodically check whether it expired so the overlay goes away.
    private val expireCheck = object : Runnable {
        override fun run() {
            app.pairing.expireIfNeeded()
            if (RemoteState.pin != null) main.postDelayed(this, 10_000)
        }
    }

    private val stateListener: () -> Unit = {
        overlay.update(RemoteState.pin)
        main.removeCallbacks(expireCheck)
        if (RemoteState.pin != null) main.postDelayed(expireCheck, 10_000)
    }

    override fun onCreate() {
        super.onCreate()
        startForeground()
        overlay = PinOverlay(this)
        RemoteState.addListener(stateListener)
        startServer()
        app.adb.start()
        registerReceiver(screenOn, IntentFilter(Intent.ACTION_SCREEN_ON))
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(network)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        RemoteState.removeListener(stateListener)
        main.removeCallbacks(expireCheck)
        unregisterReceiver(screenOn)
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(network) }
        server?.stop()
        mdns.stop()
        overlay.update(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startServer() {
        for (port in PORTS) {
            try {
                server = WebServer(this, port).apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
                Log.i(TAG, "Web server on port $port")
                break
            } catch (e: IOException) {
                Log.w(TAG, "Port $port unavailable: $e")
            }
        }
        updateUrl()
    }

    private fun updateUrl() {
        val port = server?.listeningPort ?: return
        val ip = Net.localIpv4()
        RemoteState.url = ip?.let { if (port == 80) "http://$it" else "http://$it:$port" }
        RemoteState.changed()
        mdns.update(ip, port)
    }

    private fun startForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_MIN)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }

    companion object {
        private const val TAG = "RemoteService"
        private const val CHANNEL = "remote"
        private val PORTS = (8080..8090)

        fun start(context: Context) {
            context.startForegroundService(Intent(context, RemoteService::class.java))
        }
    }
}
