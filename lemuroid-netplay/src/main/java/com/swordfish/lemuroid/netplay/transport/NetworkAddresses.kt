package com.swordfish.lemuroid.netplay.transport

import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

data class LocalAddress(val interfaceName: String, val address: String)

data class HostAddress(val host: String, val port: Int)

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

    /** Converts the little endian int used by Android (`DhcpInfo`) into dotted notation. Null for 0 (no address). */
    fun ipv4FromLittleEndian(value: Int): String? {
        if (value == 0) return null
        return "${value and 0xFF}.${(value shr 8) and 0xFF}.${(value shr 16) and 0xFF}.${(value shr 24) and 0xFF}"
    }

    /**
     * Parses what a player types to reach the host: an IPv4 address with an optional port, like `192.168.43.1` or
     * `192.168.43.1:48600`. Returns null when it isn't valid. The host is returned in canonical form (no leading
     * zeros), which avoids platforms reading `010` as octal.
     */
    fun parseHostAddress(
        text: String,
        defaultPort: Int = DEFAULT_TCP_PORT,
    ): HostAddress? {
        val parts = text.trim().split(":")
        if (parts.size > 2) return null

        val octets = parts[0].split(".")
        if (octets.size != 4) return null

        val numbers =
            octets.map { octet ->
                if (octet.isEmpty() || octet.length > 3 || !octet.all { it in '0'..'9' }) return null
                octet.toInt().takeIf { it <= 255 } ?: return null
            }

        val port =
            if (parts.size == 2) {
                parts[1].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            } else {
                defaultPort
            }

        return HostAddress(numbers.joinToString("."), port)
    }
}
