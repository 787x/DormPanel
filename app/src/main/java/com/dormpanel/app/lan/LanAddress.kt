package com.dormpanel.app.lan

import java.net.Inet4Address
import java.net.NetworkInterface

/** Select only a private IPv4 address on an active local interface. */
object LanAddress {
    fun select(candidates: List<String>): String? = candidates.mapNotNull { raw ->
        if (!raw.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))) return@mapNotNull null
        val address = runCatching { java.net.InetAddress.getByName(raw) }.getOrNull()
        if (address is Inet4Address && address.isSiteLocalAddress && !address.isLoopbackAddress &&
            !address.isLinkLocalAddress && !address.isAnyLocalAddress) raw else null
    }.firstOrNull()

    fun deviceAddress(): String? = runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        select(interfaces.filter { it.isUp && !it.isLoopback && !it.isVirtual &&
            !it.name.startsWith("tun") && !it.name.startsWith("tap") && !it.name.startsWith("rmnet") }
            .sortedBy { candidate -> when {
                candidate.name.startsWith("wlan") || candidate.name.startsWith("wifi") -> 0
                candidate.name.startsWith("eth") || candidate.name.startsWith("en") -> 1
                else -> 2
            } }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().map { it.hostAddress.orEmpty() })
    }.getOrNull()
}
