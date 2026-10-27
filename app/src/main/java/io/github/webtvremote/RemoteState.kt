package io.github.webtvremote

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

/** Observable state shared between the service, the server and the TV UI. */
object RemoteState {
    enum class Adb { CONNECTING, WAITING_AUTH, CONNECTED, UNAVAILABLE }

    @Volatile var url: String? = null
    /** Same as [url] but by mDNS name (tv-remote.local), null until announced. */
    @Volatile var nameUrl: String? = null
    @Volatile var adb: Adb = Adb.CONNECTING
    @Volatile var injectorReady = false
    @Volatile var pin: String? = null

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)
    fun changed() = main.post { listeners.forEach { it() } }
}
