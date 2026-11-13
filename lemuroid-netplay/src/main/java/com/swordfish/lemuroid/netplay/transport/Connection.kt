package com.swordfish.lemuroid.netplay.transport

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** An established link with the other player, whatever the medium (Wi-Fi, Bluetooth...) is. */
interface Connection : Closeable {
    val input: InputStream
    val output: OutputStream

    /** Human readable name of the other end, for the UI (an address, a Bluetooth device name...). */
    val description: String
}

/**
 * Knows how to get a [Connection]: the host waits for the other player to arrive, the guest goes and finds the host.
 */
interface ConnectionProvider : Closeable {
    /**
     * Blocks until the connection is established. Closing the provider from another thread aborts a pending call by
     * throwing an [IOException]. A provider is single use.
     */
    @Throws(IOException::class)
    fun open(): Connection
}
