package com.swordfish.lemuroid.netplay.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** Identifies Lemuroid netplay among the Bluetooth services of a phone. */
val NETPLAY_BLUETOOTH_UUID: UUID = UUID.fromString("8c1d6a4e-2f3b-4d57-9a60-5e0b7c9f1a21")

private const val SERVICE_NAME = "Lemuroid Netplay"

// The callers are responsible for holding BLUETOOTH_CONNECT (Android 12+) before using these classes. If they don't,
// the SecurityException is turned into an IOException, which the session reports as a connection failure.

internal class BluetoothConnection(private val socket: BluetoothSocket) : Connection {
    override val input: InputStream get() = socket.inputStream
    override val output: OutputStream get() = socket.outputStream

    @get:SuppressLint("MissingPermission")
    override val description: String
        get() {
            val device = socket.remoteDevice
            return runCatching { device.name }.getOrNull() ?: device.address
        }

    override fun close() {
        socket.close()
    }
}

/** Waits for a paired phone to connect. The host doesn't need to be discoverable, only paired with the guest. */
@SuppressLint("MissingPermission")
class BluetoothHostProvider(private val adapter: BluetoothAdapter) : ConnectionProvider {
    @Volatile
    private var server: BluetoothServerSocket? = null

    @Volatile
    private var closed = false

    override fun open(): Connection {
        if (closed) throw IOException("Closed")

        val listening =
            try {
                adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, NETPLAY_BLUETOOTH_UUID)
            } catch (e: SecurityException) {
                throw IOException("Missing Bluetooth permission", e)
            }

        server = listening
        if (closed) {
            // close() came right before the socket was stored: nobody else would close it.
            listening.close()
            throw IOException("Cancelled")
        }

        val socket =
            try {
                listening.accept()
            } catch (e: IOException) {
                throw if (closed) IOException("Cancelled", e) else e
            } finally {
                // A single guest is enough.
                runCatching { listening.close() }
            }

        return BluetoothConnection(socket)
    }

    override fun close() {
        closed = true
        runCatching { server?.close() }
    }
}

/** Connects to a paired phone that is running a [BluetoothHostProvider]. */
@SuppressLint("MissingPermission")
class BluetoothClientProvider(private val device: BluetoothDevice) : ConnectionProvider {
    @Volatile
    private var socket: BluetoothSocket? = null

    @Volatile
    private var closed = false

    override fun open(): Connection {
        if (closed) throw IOException("Closed")

        val created =
            try {
                device.createRfcommSocketToServiceRecord(NETPLAY_BLUETOOTH_UUID)
            } catch (e: SecurityException) {
                throw IOException("Missing Bluetooth permission", e)
            }

        socket = created
        if (closed) {
            created.close()
            throw IOException("Cancelled")
        }

        try {
            created.connect()
        } catch (e: IOException) {
            runCatching { created.close() }
            throw if (closed) IOException("Cancelled", e) else e
        } catch (e: SecurityException) {
            runCatching { created.close() }
            throw IOException("Missing Bluetooth permission", e)
        }

        return BluetoothConnection(created)
    }

    override fun close() {
        closed = true
        runCatching { socket?.close() }
    }
}
