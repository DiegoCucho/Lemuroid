package com.swordfish.lemuroid.netplay

import com.swordfish.lemuroid.netplay.transport.TcpClientProvider
import com.swordfish.lemuroid.netplay.transport.TcpHostProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import kotlin.concurrent.thread

class TcpTransportTest {
    @Test
    fun bindReturnsTheChosenPortAndIsIdempotent() {
        val host = TcpHostProvider(0)
        try {
            val port = host.bind()

            assertTrue(port > 0)
            assertEquals(port, host.bind())
        } finally {
            host.close()
        }
    }

    @Test
    fun aTakenPortIsReportedWhenBinding() {
        val first = TcpHostProvider(0)
        try {
            val port = first.bind()

            val second = TcpHostProvider(port)
            try {
                second.bind()
                fail("Expected the port to be taken")
            } catch (e: IOException) {
                // Expected.
            } finally {
                second.close()
            }
        } finally {
            first.close()
        }
    }

    @Test
    fun hostAndGuestCanExchangeBytes() {
        val host = TcpHostProvider(0)
        val port = host.bind()

        var hostSide: ByteArray? = null
        val acceptor =
            thread {
                host.open().use { connection ->
                    hostSide = ByteArray(5).also { connection.input.read(it) }
                    connection.output.write("pong!".toByteArray())
                    connection.output.flush()
                }
            }

        TcpClientProvider("127.0.0.1", port).open().use { connection ->
            connection.output.write("hello".toByteArray())
            connection.output.flush()

            val reply = ByteArray(5)
            var read = 0
            while (read < reply.size) {
                val count = connection.input.read(reply, read, reply.size - read)
                if (count < 0) break
                read += count
            }

            assertEquals("pong!", String(reply))
            assertEquals("127.0.0.1", connection.description)
        }

        acceptor.join(3_000)
        assertEquals("hello", String(hostSide!!))
    }

    @Test
    fun aClosedProviderCantBeOpened() {
        val host = TcpHostProvider(0)
        host.close()
        try {
            host.open()
            fail("Expected an IOException")
        } catch (e: IOException) {
            // Expected.
        }

        val client = TcpClientProvider("127.0.0.1", 1)
        client.close()
        try {
            client.open()
            fail("Expected an IOException")
        } catch (e: IOException) {
            // Expected.
        }
    }

    @Test
    fun closingTheHostWakesUpAPendingOpen() {
        val host = TcpHostProvider(0)
        host.bind()

        var failure: IOException? = null
        val waiting =
            thread {
                try {
                    host.open()
                } catch (e: IOException) {
                    failure = e
                }
            }

        Thread.sleep(150)
        host.close()
        waiting.join(3_000)

        assertTrue("open() was not interrupted", !waiting.isAlive)
        assertTrue(failure != null)
    }
}
