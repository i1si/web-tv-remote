package io.github.webtvremote.input

import dadb.AdbKeyPair
import dadb.AdbStream
import dadb.Dadb
import java.net.InetSocketAddress
import java.net.Socket

/** A [Dadb] over a socket we own, so it can be closed at any moment — even mid-handshake. */
class LoopbackDadb private constructor(private val socket: Socket, private val connection: Any) : Dadb {

    override fun open(destination: String): AdbStream = AdbInternals.open(connection, destination)

    override fun supportsFeature(feature: String) = AdbInternals.supportsFeature(connection, feature)

    override fun close() {
        runCatching { AdbInternals.close(connection) }
        runCatching { socket.close() }
    }

    companion object {
        /** Connects and authenticates. Closing [socket] from another thread aborts the attempt. */
        fun connect(socket: Socket, port: Int, keyPair: AdbKeyPair): LoopbackDadb {
            socket.connect(InetSocketAddress("127.0.0.1", port), 3000)
            socket.keepAlive = true
            return LoopbackDadb(socket, AdbInternals.connect(socket, keyPair))
        }
    }
}
