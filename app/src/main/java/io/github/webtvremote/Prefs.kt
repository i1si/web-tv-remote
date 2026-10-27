package io.github.webtvremote

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("web_tv_remote", Context.MODE_PRIVATE)

    data class Client(val tokenHash: String, val name: String, val pairedAt: Long)

    @Synchronized
    fun clients(): List<Client> {
        val arr = JSONArray(sp.getString(KEY_CLIENTS, "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Client(o.getString("hash"), o.optString("name"), o.optLong("at"))
        }
    }

    @Synchronized
    fun addClient(client: Client) {
        val list = clients() + client
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("hash", it.tokenHash).put("name", it.name).put("at", it.pairedAt)) }
        sp.edit().putString(KEY_CLIENTS, arr.toString()).apply()
    }

    @Synchronized
    fun clearClients() = sp.edit().remove(KEY_CLIENTS).apply()

    fun hasClient(tokenHash: String) = clients().any { it.tokenHash == tokenHash }

    /** null = never configured, so a default list should be generated. */
    var favorites: List<String>?
        get() = sp.getString(KEY_FAVORITES, null)?.let { s -> s.split(',').filter { it.isNotBlank() } }
        set(value) = sp.edit().putString(KEY_FAVORITES, value?.joinToString(",")).apply()

    var adbPort: Int
        get() = sp.getInt(KEY_ADB_PORT, 5555)
        set(value) = sp.edit().putInt(KEY_ADB_PORT, value).apply()

    /** Set once the accessibility service was switched on automatically, so a later "off" by the user sticks. */
    var accessibilityAutoEnabled: Boolean
        get() = sp.getBoolean(KEY_A11Y_AUTO, false)
        set(value) = sp.edit().putBoolean(KEY_A11Y_AUTO, value).apply()

    /** mDNS name picked for this box, and the device-name-derived base it was picked for. */
    var mdnsName: String?
        get() = sp.getString(KEY_MDNS_NAME, null)
        set(value) = sp.edit().putString(KEY_MDNS_NAME, value).apply()

    var mdnsBase: String?
        get() = sp.getString(KEY_MDNS_BASE, null)
        set(value) = sp.edit().putString(KEY_MDNS_BASE, value).apply()

    companion object {
        private const val KEY_CLIENTS = "clients"
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_ADB_PORT = "adb_port"
        private const val KEY_A11Y_AUTO = "a11y_auto_enabled"
        private const val KEY_MDNS_NAME = "mdns_name"
        private const val KEY_MDNS_BASE = "mdns_base"
    }
}
