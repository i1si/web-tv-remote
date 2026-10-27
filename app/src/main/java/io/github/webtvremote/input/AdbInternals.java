package io.github.webtvremote.input;

import java.io.IOException;
import java.net.Socket;

import dadb.AdbConnection;
import dadb.AdbKeyPair;
import dadb.AdbStream;

/**
 * Access to dadb's AdbConnection, which is Kotlin-internal (Java can still see it).
 *
 * dadb's own Dadb.create() opens the socket privately, so a handshake that never completes
 * (e.g. the "Allow debugging?" prompt is ignored, or adbd restarts mid-connect) cannot be
 * aborted. Here we own the socket and can close it. Tied to dadb 1.2.x internals.
 */
final class AdbInternals {
    private AdbInternals() {}

    static Object connect(Socket socket, AdbKeyPair keyPair) throws IOException {
        return AdbConnection.Companion.connect(socket, keyPair);
    }

    static AdbStream open(Object connection, String destination) throws IOException {
        return ((AdbConnection) connection).open(destination);
    }

    static boolean supportsFeature(Object connection, String feature) {
        return ((AdbConnection) connection).supportsFeature(feature);
    }

    static void close(Object connection) throws Exception {
        ((AdbConnection) connection).close();
    }
}
