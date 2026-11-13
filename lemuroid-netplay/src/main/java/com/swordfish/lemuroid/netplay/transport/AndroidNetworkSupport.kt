package com.swordfish.lemuroid.netplay.transport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat

data class PairedDevice(val name: String, val address: String)

object BluetoothSupport {
    /** Null on devices without Bluetooth, or when it's unavailable. Turned off adapters are still returned. */
    fun adapter(context: Context): BluetoothAdapter? {
        val manager = context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter
    }

    /** Runtime permissions needed to use Bluetooth netplay. Before Android 12 the manifest ones are enough. */
    fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            emptyArray()
        }
    }

    fun hasPermissions(context: Context): Boolean {
        return requiredPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /** Phones this one is already paired with, which are the only ones netplay can reach. */
    @SuppressLint("MissingPermission")
    fun pairedDevices(adapter: BluetoothAdapter): List<PairedDevice> {
        return try {
            adapter.bondedDevices.orEmpty()
                .map { PairedDevice(runCatching { it.name }.getOrNull() ?: it.address, it.address) }
                .sortedBy { it.name.lowercase() }
        } catch (e: SecurityException) {
            emptyList()
        }
    }
}

object WifiSupport {
    /**
     * Address of the gateway of the Wi-Fi network we are connected to. When the other phone is sharing its
     * connection (hotspot), the gateway is that phone, so it's the host the guest is looking for.
     */
    @Suppress("DEPRECATION")
    fun gatewayAddress(context: Context): String? {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        val gateway =
            try {
                wifi.dhcpInfo?.gateway
            } catch (e: SecurityException) {
                null
            }

        return gateway?.let { NetworkAddresses.ipv4FromLittleEndian(it) }
    }
}
