package com.swordfish.lemuroid.netplay

import com.swordfish.lemuroid.netplay.protocol.MessageCodec
import com.swordfish.lemuroid.netplay.protocol.MismatchReason
import com.swordfish.lemuroid.netplay.protocol.NetplayMessage
import com.swordfish.lemuroid.netplay.protocol.PROTOCOL_VERSION
import com.swordfish.lemuroid.netplay.protocol.PeerRole
import com.swordfish.lemuroid.netplay.protocol.ProtocolException
import com.swordfish.lemuroid.netplay.rom.GameFingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class MessageCodecTest {
    private val fingerprint = GameFingerprint("snes", 1_048_576L, "ab".repeat(32))

    private fun encode(message: NetplayMessage): ByteArray {
        val out = ByteArrayOutputStream()
        MessageCodec.write(out, message)
        return out.toByteArray()
    }

    private fun roundTrip(message: NetplayMessage): NetplayMessage? {
        return MessageCodec.read(ByteArrayInputStream(encode(message)))
    }

    private fun frame(vararg body: Int): ByteArray {
        val bytes = ByteArrayOutputStream()
        bytes.write(byteArrayOf(0, 0, 0, body.size.toByte()))
        body.forEach { bytes.write(it) }
        return bytes.toByteArray()
    }

    /** Checks the bytes are rejected, and (optionally) for the expected reason, not by accident. */
    private fun expectProtocolError(
        bytes: ByteArray,
        reason: String? = null,
    ) {
        try {
            MessageCodec.read(ByteArrayInputStream(bytes))
            fail("Expected a ProtocolException")
        } catch (e: ProtocolException) {
            if (reason != null) {
                assertTrue("Unexpected error: ${e.message}", e.message.orEmpty().contains(reason))
            }
        }
    }

    @Test
    fun everyMessageSurvivesARoundTrip() {
        val messages =
            listOf(
                NetplayMessage.Hello(PROTOCOL_VERSION, "Diego", PeerRole.HOST),
                NetplayMessage.Hello(7, "", PeerRole.GUEST),
                NetplayMessage.GameInfo(fingerprint),
                NetplayMessage.Ready,
                NetplayMessage.Mismatch(MismatchReason.PROTOCOL_VERSION),
                NetplayMessage.Mismatch(MismatchReason.SYSTEM),
                NetplayMessage.Mismatch(MismatchReason.ROM_CONTENT),
                NetplayMessage.Ping(42, Long.MAX_VALUE),
                NetplayMessage.Pong(-1, Long.MIN_VALUE),
                NetplayMessage.Bye,
            )

        messages.forEach { assertEquals(it, roundTrip(it)) }
    }

    @Test
    fun unicodeNamesSurviveARoundTrip() {
        val message = NetplayMessage.Hello(PROTOCOL_VERSION, "Peña ñandú 🎮", PeerRole.GUEST)
        assertEquals(message, roundTrip(message))
    }

    @Test
    fun longNamesAreTruncated() {
        val message = NetplayMessage.Hello(PROTOCOL_VERSION, "a".repeat(100), PeerRole.HOST)
        val decoded = roundTrip(message) as NetplayMessage.Hello
        assertEquals(MessageCodec.MAX_NAME_CHARS, decoded.playerName.length)
    }

    @Test
    fun cleanEndOfStreamReturnsNull() {
        assertNull(MessageCodec.read(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun messagesCanBeReadBackToBack() {
        val out = ByteArrayOutputStream()
        MessageCodec.write(out, NetplayMessage.Ready)
        MessageCodec.write(out, NetplayMessage.Ping(1, 2))
        MessageCodec.write(out, NetplayMessage.Bye)

        val input = ByteArrayInputStream(out.toByteArray())
        assertEquals(NetplayMessage.Ready, MessageCodec.read(input))
        assertEquals(NetplayMessage.Ping(1, 2), MessageCodec.read(input))
        assertEquals(NetplayMessage.Bye, MessageCodec.read(input))
        assertNull(MessageCodec.read(input))
    }

    @Test
    fun frameCutInTheMiddleIsAProtocolError() {
        val bytes = encode(NetplayMessage.Ping(1, 2))
        expectProtocolError(bytes.copyOf(bytes.size - 1), "middle of a message")
    }

    @Test
    fun lengthHeaderCutInTheMiddleIsAProtocolError() {
        expectProtocolError(byteArrayOf(0, 0), "middle of a message")
    }

    @Test
    fun oversizedFramesAreRejected() {
        expectProtocolError(byteArrayOf(0x7F, 0x7F, 0x7F, 0x7F), "Invalid frame length")
        expectProtocolError(byteArrayOf(0, 0, 0x10, 0x00), "Invalid frame length") // 4096 > MAX_FRAME_BYTES
    }

    @Test
    fun negativeLengthsAreRejected() {
        val allOnes = ByteArray(4) { 0xFF.toByte() }
        expectProtocolError(allOnes, "Invalid frame length")
    }

    @Test
    fun emptyFramesAreRejected() {
        expectProtocolError(byteArrayOf(0, 0, 0, 0), "Invalid frame length")
    }

    @Test
    fun unknownMessageTypesAreRejected() {
        expectProtocolError(frame(99), "Unknown message type")
    }

    @Test
    fun extraBytesAfterAMessageAreRejected() {
        expectProtocolError(frame(3, 0), "extra bytes") // READY with a stray payload byte
    }

    @Test
    fun unknownRolesAreRejected() {
        // HELLO: version (4 bytes), role = 9 (invalid), empty name (2 bytes).
        expectProtocolError(frame(1, 0, 0, 0, 1, 9, 0, 0), "Unknown role")
    }

    @Test
    fun unknownMismatchReasonsAreRejected() {
        expectProtocolError(frame(4, 77), "Unknown mismatch reason")
    }

    @Test
    fun payloadShorterThanExpectedIsRejected() {
        expectProtocolError(frame(5, 0, 0), "shorter than expected") // PING needs 12 bytes of payload
    }
}
