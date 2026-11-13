package com.swordfish.lemuroid.netplay.transport

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

/** Port used by default, so the guest only has to type the address of the host. */
const val DEFAULT_TCP_PORT = 48500

private const val DEFAULT_CONNECT_TIMEOUT_MS = 8_000

internal class TcpConnection(private val socket: Socket) : Connection {
    init {
        // Inputs are tiny and latency matters more than throughput: don't let the OS batch them.
        socket.tcpNoDelay = true
        socket.keepAlive = true
    }

    override val input: InputStream get() = socket.getInputStream()
    override val output: OutputStream get() = socket.getOutputStream()

    override val description: String
        get() = (socket.remoteSocketAddress as? InetSocketAddress)?.address?.hostAddress ?: "?"

    override fun close() {
        socket.close()
    }
}

/** Listens for the other player on the local network (same Wi-Fi, or the hotspot of one of the phones). */
class TcpHostProvider(private val requestedPort: Int = DEFAULT_TCP_PORT) : ConnectionProvider {
    private var server: ServerSocket? = null

    @Volatile
    private var closed = false

    /**
     * Starts listening (idempotent) and returns the port. Calling it before [open] lets the UI show the address
     * and report right away if the port is taken. Pass 0 as requested port to let the OS choose one.
     */
    @Synchronized
    @Throws(IOException::class)
    fun bind(): Int {
        if (closed) throw IOException("Closed")
        val existing = server
        if (existing != null) return existing.localPort

        val created = ServerSocket()
        try {
            created.reuseAddress = true
            created.bind(InetSocketAddress(requestedPort))
        } catch (e: IOException) {
            created.close()
            throw e
        }
        server = created
        return created.localPort
    }

    override fun open(): Connection {
        bind()
        val listening = synchronized(this) { server } ?: throw IOException("Closed")

        val socket =
            try {
                listening.accept()
            } catch (e: SocketException) {
                throw IOException(if (closed) "Cancelled" else "Could not accept the connection", e)
            } finally {
                // There is room for a single guest: free the port as soon as someone arrives (or we give up).
                listening.close()
            }

        return TcpConnection(socket)
    }

    override fun close() {
        closed = true
        synchronized(this) { server }?.close()
    }
}

/** Connects to a host that is already listening. */
class TcpClientProvider(
    private val host: String,
    private val port: Int = DEFAULT_TCP_PORT,
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
) : ConnectionProvider {
    private val socket = Socket()

    @Volatile
    private var closed = false

    override fun open(): Connection {
        if (closed) throw IOException("Closed")

        try {
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
        } catch (e: IOException) {
            socket.close()
            throw if (closed) IOException("Cancelled", e) else e
        }

        return TcpConnection(socket)
    }

    override fun close() {
        closed = true
        socket.close()
    }
}
