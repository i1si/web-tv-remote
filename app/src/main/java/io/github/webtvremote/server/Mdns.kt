package io.github.webtvremote.server

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import io.github.webtvremote.App
import io.github.webtvremote.RemoteState
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * Minimal mDNS responder: answers "who is <name>.local?" with our IPv4 address, so the remote has
 * an address that survives IP changes. The name comes from the device name ("Living room" →
 * living-room.local). Only A records for our own name are handled — that is all a browser needs.
 * (JmDNS was tried first but silently did not receive anything on some boxes.)
 */
class Mdns(context: Context) {
    private val app = App.of(context)
    // Many Wi-Fi drivers drop multicast packets unless an app holds this lock.
    private val multicastLock = context.getSystemService(WifiManager::class.java)
        .createMulticastLock("web-tv-remote").apply { setReferenceCounted(false) }

    @Volatile private var responder: Responder? = null

    /** (Re)announces the name on [ip]; null withdraws it. Call on every network change. */
    @Synchronized
    fun update(ip: String?, port: Int) {
        val current = responder
        if (current != null && current.ip == ip && current.port == port) return
        current?.shutdown()
        responder = null
        if (ip == null) {
            runCatching { multicastLock.release() }
            publish(null)
            return
        }
        multicastLock.acquire()
        responder = Responder(ip, port).also { it.start() }
    }

    @Synchronized
    fun stop() = update(null, 0)

    private inner class Responder(val ip: String, val port: Int) : Thread("mdns") {
        @Volatile private var running = true
        private val address = InetAddress.getByName(ip) as Inet4Address
        private val group = InetAddress.getByName(GROUP)
        private var socket: MulticastSocket? = null
        private var name = "$FALLBACK_NAME.local"
        private var queries = 0

        override fun run() {
            try {
                val iface = NetworkInterface.getByInetAddress(address)
                val s = MulticastSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(PORT))
                    if (iface != null) networkInterface = iface
                    timeToLive = 255
                    soTimeout = 1000
                    joinGroup(InetSocketAddress(group, PORT), iface)
                }
                socket = s
                name = pickFreeName(s)
                // Unsolicited announcements, as RFC 6762 §8.3 asks for, so caches update at once.
                repeat(2) { send(s, answer(), null); sleep(1000) }
                Log.i(TAG, "Announced $name on $ip (${iface?.name})")
                publish(if (port == 80) "http://$name" else "http://$name:$port")
                val buf = ByteArray(9000)
                while (running) {
                    val packet = DatagramPacket(buf, buf.size)
                    try { s.receive(packet) } catch (_: SocketTimeoutException) { continue }
                    handle(s, packet)
                }
            } catch (e: Exception) {
                if (running) {
                    Log.w(TAG, "mDNS responder failed: $e")
                    publish(null)
                }
            } finally {
                runCatching { socket?.close() }
            }
        }

        fun shutdown() {
            running = false
            runCatching { socket?.close() }
        }

        private fun handle(s: MulticastSocket, packet: DatagramPacket) {
            val msg = runCatching { Message.parse(packet.data, packet.length) }.getOrNull() ?: return
            if (msg.isResponse) return
            val ask = msg.questions.firstOrNull {
                it.name.equals(name, ignoreCase = true) && (it.type == TYPE_A || it.type == TYPE_ANY)
            } ?: return
            if (queries++ < 20) Log.i(TAG, "Query for $name from ${packet.address.hostAddress}")
            // "QU" questions ask for a unicast reply; otherwise answer to the multicast group.
            send(s, answer(), if (ask.unicast) packet.socketAddress as InetSocketAddress else null)
        }

        /**
         * Picks a name nobody else answers for: the one picked before (so it stays stable even
         * when a same-named box boots first), else the base name, else base-2, base-3...
         */
        private fun pickFreeName(s: MulticastSocket): String {
            val base = hostLabel(app.deviceName)
            val previous = app.prefs.mdnsName?.takeIf { app.prefs.mdnsBase == base }
            val candidates = listOfNotNull(previous) + (1..9).map { n -> if (n == 1) "$base.local" else "$base-$n.local" }
            val chosen = candidates.distinct().firstOrNull { !isTaken(s, it) } ?: "$base.local"
            app.prefs.mdnsBase = base
            app.prefs.mdnsName = chosen
            return chosen
        }

        private fun isTaken(s: MulticastSocket, candidate: String): Boolean {
            send(s, Message.query(candidate), null)
            val deadline = System.currentTimeMillis() + 750
            val buf = ByteArray(9000)
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buf, buf.size)
                try { s.receive(packet) } catch (_: SocketTimeoutException) { break }
                if (packet.address == address) continue
                val msg = runCatching { Message.parse(packet.data, packet.length) }.getOrNull() ?: continue
                if (msg.isResponse && msg.answerNames.any { it.equals(candidate, ignoreCase = true) }) return true
            }
            return false
        }

        private fun answer() = Message.answerA(name, address.address, TTL_SECONDS)

        private fun send(s: MulticastSocket, data: ByteArray, to: InetSocketAddress?) {
            val target = to ?: InetSocketAddress(group, PORT)
            runCatching { s.send(DatagramPacket(data, data.size, target)) }
                .onFailure { Log.w(TAG, "send failed: $it") }
        }
    }

    private fun publish(url: String?) {
        RemoteState.nameUrl = url
        RemoteState.changed()
    }

    /** Just enough DNS wire format for mDNS host-name queries and answers. */
    private class Message(val isResponse: Boolean, val questions: List<Question>, val answerNames: List<String>) {
        class Question(val name: String, val type: Int, val unicast: Boolean)

        companion object {
            fun parse(data: ByteArray, length: Int): Message {
                fun u16(i: Int) = ((data[i].toInt() and 0xff) shl 8) or (data[i + 1].toInt() and 0xff)
                val flags = u16(2)
                val qd = u16(4)
                val an = u16(6)
                var pos = 12
                fun readName(): String {
                    val labels = mutableListOf<String>()
                    var p = pos
                    var jumped = false
                    var hops = 0
                    while (true) {
                        require(p < length && hops++ < 64)
                        val len = data[p].toInt() and 0xff
                        when {
                            len == 0 -> { p++; break }
                            len and 0xc0 == 0xc0 -> { // compression pointer
                                if (!jumped) pos = p + 2
                                jumped = true
                                p = ((len and 0x3f) shl 8) or (data[p + 1].toInt() and 0xff)
                            }
                            else -> {
                                labels += String(data, p + 1, len, Charsets.UTF_8)
                                p += len + 1
                            }
                        }
                    }
                    if (!jumped) pos = p
                    return labels.joinToString(".")
                }
                val questions = List(qd) {
                    val name = readName()
                    val type = u16(pos)
                    val cls = u16(pos + 2)
                    pos += 4
                    Question(name, type, cls and 0x8000 != 0)
                }
                val answers = List(an) {
                    val name = readName()
                    val rdLength = u16(pos + 8)
                    pos += 10 + rdLength
                    name
                }
                return Message(flags and 0x8000 != 0, questions, answers)
            }

            fun query(name: String) = ByteArrayOutputStream().apply {
                write(byteArrayOf(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0)) // one question
                writeName(name)
                write16(TYPE_ANY)
                write16(0x8001) // IN, unicast reply wanted
            }.toByteArray()

            fun answerA(name: String, ip: ByteArray, ttl: Int) = ByteArrayOutputStream().apply {
                write(byteArrayOf(0, 0, 0x84.toByte(), 0, 0, 0, 0, 1, 0, 0, 0, 0)) // response, authoritative, one answer
                writeName(name)
                write16(TYPE_A)
                write16(0x8001) // IN with cache-flush: we are the only owner of this name
                write16(ttl ushr 16)
                write16(ttl and 0xffff)
                write16(4)
                write(ip)
            }.toByteArray()

            private fun ByteArrayOutputStream.writeName(name: String) {
                name.split('.').forEach { label ->
                    val bytes = label.toByteArray(Charsets.UTF_8)
                    write(bytes.size)
                    write(bytes)
                }
                write(0)
            }

            private fun ByteArrayOutputStream.write16(v: Int) {
                write((v shr 8) and 0xff)
                write(v and 0xff)
            }
        }
    }

    companion object {
        private const val TAG = "Mdns"

        private val CYRILLIC = mapOf(
            'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e",
            'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m",
            'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
            'ф' to "f", 'х' to "h", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "sch", 'ъ' to "",
            'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya",
        )

        /** "Проектор в зале" → "proektor-v-zale"; falls back to "tv-remote". */
        fun hostLabel(deviceName: String): String {
            val latin = deviceName.lowercase().map { CYRILLIC[it] ?: it.toString() }.joinToString("")
            val label = latin.replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).trimEnd('-')
            return label.ifEmpty { FALLBACK_NAME }
        }
        private const val FALLBACK_NAME = "tv-remote"
        private const val GROUP = "224.0.0.251"
        private const val PORT = 5353
        private const val TTL_SECONDS = 120
        private const val TYPE_A = 1
        private const val TYPE_ANY = 255
    }
}
