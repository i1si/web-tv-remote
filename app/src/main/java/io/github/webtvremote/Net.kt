package io.github.webtvremote

import java.net.Inet4Address
import java.net.NetworkInterface

object Net {
    /** Best guess of the address other devices in the home network can reach us at. */
    fun localIpv4(): String? {
        val candidates = runCatching { NetworkInterface.getNetworkInterfaces().toList() }.getOrDefault(emptyList())
            .filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtual }.getOrDefault(false) }
            .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { nif.name to it } }
        val best = candidates.firstOrNull { (name, a) -> a.isSiteLocalAddress && (name.startsWith("eth") || name.startsWith("wlan")) }
            ?: candidates.firstOrNull { it.second.isSiteLocalAddress }
            ?: candidates.firstOrNull()
        return best?.second?.hostAddress
    }
}
