package com.swordfish.lemuroid.netplay

import com.swordfish.lemuroid.netplay.transport.DEFAULT_TCP_PORT
import com.swordfish.lemuroid.netplay.transport.HostAddress
import com.swordfish.lemuroid.netplay.transport.LocalAddress
import com.swordfish.lemuroid.netplay.transport.NetworkAddresses
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress

class NetworkAddressesTest {
    private fun candidate(
        name: String,
        address: String,
    ) = name to InetAddress.getByName(address)

    @Test
    fun keepsWifiAndHotspotAddresses() {
        val result =
            NetworkAddresses.selectUsable(
                listOf(
                    candidate("wlan0", "192.168.1.5"),
                    candidate("ap0", "192.168.43.1"),
                    candidate("eth0", "10.0.0.7"),
                    candidate("wlan1", "172.16.5.4"),
                ),
            )

        assertEquals(
            listOf(
                LocalAddress("ap0", "192.168.43.1"),
                LocalAddress("eth0", "10.0.0.7"),
                LocalAddress("wlan0", "192.168.1.5"),
                LocalAddress("wlan1", "172.16.5.4"),
            ),
            result,
        )
    }

    @Test
    fun dropsAddressesThatCantLeadToAnotherPhone() {
        val result =
            NetworkAddresses.selectUsable(
                listOf(
                    candidate("wlan0", "8.8.8.8"), // Public address.
                    candidate("wlan0", "fe80::1"), // IPv6.
                    candidate("wlan0", "169.254.1.1"), // Link-local.
                    candidate("wlan0", "172.32.0.1"), // Just outside the private 172.16/12 range.
                    candidate("rmnet_data0", "10.1.2.3"), // Mobile data.
                    candidate("tun0", "10.8.0.2"), // VPN.
                ),
            )

        assertEquals(emptyList<LocalAddress>(), result)
    }

    @Test
    fun removesDuplicates() {
        val result =
            NetworkAddresses.selectUsable(
                listOf(candidate("wlan0", "192.168.1.5"), candidate("wlan0", "192.168.1.5")),
            )

        assertEquals(listOf(LocalAddress("wlan0", "192.168.1.5")), result)
    }

    @Test
    fun readingTheRealInterfacesNeverCrashes() {
        // The content depends on the machine; we only care that it works and returns usable addresses.
        NetworkAddresses.localAddresses().forEach {
            assertEquals(false, it.address.isEmpty())
        }
    }

    @Test
    fun convertsTheLittleEndianAddressesOfAndroid() {
        assertEquals("192.168.0.1", NetworkAddresses.ipv4FromLittleEndian(0x0100A8C0))
        assertEquals("192.168.43.1", NetworkAddresses.ipv4FromLittleEndian(0x012BA8C0))
        assertEquals("255.255.255.255", NetworkAddresses.ipv4FromLittleEndian(-1))
        assertNull(NetworkAddresses.ipv4FromLittleEndian(0))
    }

    @Test
    fun parsesAddressesTypedByThePlayer() {
        assertEquals(HostAddress("192.168.43.1", DEFAULT_TCP_PORT), NetworkAddresses.parseHostAddress("192.168.43.1"))
        assertEquals(HostAddress("10.0.0.2", 48600), NetworkAddresses.parseHostAddress("10.0.0.2:48600"))
        assertEquals(HostAddress("10.0.0.2", DEFAULT_TCP_PORT), NetworkAddresses.parseHostAddress("  10.0.0.2  "))
        assertEquals(HostAddress("10.0.0.2", 9), NetworkAddresses.parseHostAddress("10.0.0.2", defaultPort = 9))
    }

    @Test
    fun normalizesLeadingZerosSoTheyAreNotReadAsOctal() {
        val parsed = NetworkAddresses.parseHostAddress("192.168.001.010")

        assertEquals(HostAddress("192.168.1.10", DEFAULT_TCP_PORT), parsed)
    }

    @Test
    fun rejectsAddressesThatAreNotValidIpv4() {
        val invalid =
            listOf(
                "",
                "   ",
                "localhost",
                "192.168.1",
                "192.168.1.1.1",
                "192.168.1.256",
                "192.168.1.-1",
                "192.168..1",
                "192.168.1.a",
                "192.168.1.1:",
                "192.168.1.1:0",
                "192.168.1.1:65536",
                "192.168.1.1:abc",
                "192.168.1.1:80:90",
                "1234.1.1.1",
                "\u0663.1.1.1", // A non-ASCII digit.
            )

        invalid.forEach { assertNull("Should be invalid: '$it'", NetworkAddresses.parseHostAddress(it)) }
    }
}
