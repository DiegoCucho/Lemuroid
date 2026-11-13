package com.swordfish.lemuroid.netplay.transport

import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

data class LocalAddress(val interfaceName: String, val address: String)

/**
 * The addresses the other player can use to reach this phone: the ones of the local network, which includes the
 * hotspot interface when this phone is sharing its connection.
 */
object NetworkAddresses {
    // Mobile data interfaces never lead to the other phone, and only add noise to the list.
    private val IGNORED_PREFIXES = listOf("rmnet", "ccmni", "pdp", "dummy", "tun", "p2p")

    fun localAddresses(): List<LocalAddress> {
        val candidates =
            try {
                NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                    .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                    .flatMap { networkInterface ->
                        networkInterface.inetAddresses.toList().map { networkInterface.name to it }
                    }
            } catch (e: IOException) {
                emptyList()
            }

        return selectUsable(candidates)
    }

    internal fun selectUsable(candidates: List<Pair<String, InetAddress>>): List<LocalAddress> {
        return candidates
            .filter { (name, address) ->
                address is Inet4Address &&
                    address.isSiteLocalAddress &&
                    IGNORED_PREFIXES.none { name.startsWith(it) }
            }
            .map { (name, address) -> LocalAddress(name, address.hostAddress.orEmpty()) }
            .distinct()
            .sortedWith(compareBy({ it.interfaceName }, { it.address }))
    }
}
