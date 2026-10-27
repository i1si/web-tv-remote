package io.github.webtvremote.server

import io.github.webtvremote.Prefs
import io.github.webtvremote.RemoteState
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * PIN pairing: a new browser asks for a PIN, the TV displays it, the browser sends it back and
 * receives a long random token (stored as a cookie). Only the token hash is kept on the TV.
 */
class Pairing(private val prefs: Prefs) {
    private val random = SecureRandom()
    private var pin: String? = null
    private var expiresAt = 0L
    private var attemptsLeft = 0

    @Synchronized
    fun request() {
        val now = System.currentTimeMillis()
        if (pin == null || now > expiresAt) newPin()
        expiresAt = now + PIN_TTL_MS
    }

    sealed class Outcome {
        class Paired(val token: String) : Outcome()
        class WrongPin(val attemptsLeft: Int) : Outcome()
        object Expired : Outcome()
    }

    @Synchronized
    fun confirm(entered: String, clientName: String): Outcome {
        val current = pin
        if (current == null || System.currentTimeMillis() > expiresAt) {
            clear()
            return Outcome.Expired
        }
        if (!MessageDigest.isEqual(entered.toByteArray(), current.toByteArray())) {
            attemptsLeft--
            // Too many guesses: rotate the PIN so it cannot be brute-forced.
            if (attemptsLeft <= 0) newPin()
            return Outcome.WrongPin(attemptsLeft)
        }
        val token = ByteArray(32).also { random.nextBytes(it) }.toHex()
        prefs.addClient(Prefs.Client(hash(token), clientName.take(60), System.currentTimeMillis()))
        clear()
        return Outcome.Paired(token)
    }

    @Synchronized
    fun expireIfNeeded() {
        if (pin != null && System.currentTimeMillis() > expiresAt) clear()
    }

    fun isAuthorized(token: String?) = token != null && token.length == 64 && prefs.hasClient(hash(token))

    private fun newPin() {
        pin = "%04d".format(random.nextInt(10000))
        attemptsLeft = MAX_ATTEMPTS
        expiresAt = System.currentTimeMillis() + PIN_TTL_MS
        publish()
    }

    private fun clear() {
        pin = null
        publish()
    }

    private fun publish() {
        RemoteState.pin = pin
        RemoteState.changed()
    }

    companion object {
        private const val PIN_TTL_MS = 3 * 60_000L
        private const val MAX_ATTEMPTS = 5

        fun hash(token: String) = MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).toHex()
        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    }
}
