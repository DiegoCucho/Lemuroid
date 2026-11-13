package com.swordfish.lemuroid.netplay.protocol

import com.swordfish.lemuroid.netplay.rom.GameFingerprint
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class ProtocolException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Binary framing shared by every transport (TCP, Bluetooth RFCOMM...), since they are all plain byte streams:
 *
 *     [length: u32 big endian][type: u8][payload...]
 *
 * where `length` counts the type byte plus the payload. Strings are `[u16 length][UTF-8 bytes]`.
 */
object MessageCodec {
    /** Messages are tiny. Rejecting big frames early avoids huge allocations when talking to garbage. */
    const val MAX_FRAME_BYTES = 1024

    const val MAX_NAME_CHARS = 32
    private const val MAX_STRING_BYTES = 256

    private const val TYPE_HELLO = 1
    private const val TYPE_GAME_INFO = 2
    private const val TYPE_READY = 3
    private const val TYPE_MISMATCH = 4
    private const val TYPE_PING = 5
    private const val TYPE_PONG = 6
    private const val TYPE_BYE = 7

    /** Writes the whole frame with a single write call, so concurrent writers can't interleave partial frames. */
    fun write(
        output: OutputStream,
        message: NetplayMessage,
    ) {
        val bodyBytes = ByteArrayOutputStream()
        val body = DataOutputStream(bodyBytes)
        body.writeByte(typeOf(message))
        writePayload(message, body)

        val frameBytes = ByteArrayOutputStream()
        val frame = DataOutputStream(frameBytes)
        frame.writeInt(bodyBytes.size())
        bodyBytes.writeTo(frame)

        output.write(frameBytes.toByteArray())
        output.flush()
    }

    /**
     * Reads the next message. Returns null when the stream ended cleanly between two frames, and throws
     * [ProtocolException] when it ended in the middle of one or the content makes no sense.
     */
    fun read(input: InputStream): NetplayMessage? {
        val first = input.read()
        if (first < 0) return null

        val data = DataInputStream(input)
        try {
            val second = data.readUnsignedByte()
            val third = data.readUnsignedByte()
            val fourth = data.readUnsignedByte()
            val length = (first shl 24) or (second shl 16) or (third shl 8) or fourth
            if (length < 1 || length > MAX_FRAME_BYTES) {
                throw ProtocolException("Invalid frame length: $length")
            }

            val bodyBytes = ByteArray(length)
            data.readFully(bodyBytes)
            return decode(bodyBytes)
        } catch (e: EOFException) {
            throw ProtocolException("Connection closed in the middle of a message", e)
        }
    }

    private fun typeOf(message: NetplayMessage): Int {
        return when (message) {
            is NetplayMessage.Hello -> TYPE_HELLO
            is NetplayMessage.GameInfo -> TYPE_GAME_INFO
            NetplayMessage.Ready -> TYPE_READY
            is NetplayMessage.Mismatch -> TYPE_MISMATCH
            is NetplayMessage.Ping -> TYPE_PING
            is NetplayMessage.Pong -> TYPE_PONG
            NetplayMessage.Bye -> TYPE_BYE
        }
    }

    private fun writePayload(
        message: NetplayMessage,
        out: DataOutputStream,
    ) {
        when (message) {
            is NetplayMessage.Hello -> {
                out.writeInt(message.protocolVersion)
                out.writeByte(message.role.wireId)
                writeString(out, message.playerName.take(MAX_NAME_CHARS))
            }
            is NetplayMessage.GameInfo -> {
                writeString(out, message.fingerprint.systemId)
                out.writeLong(message.fingerprint.sizeBytes)
                writeString(out, message.fingerprint.sha256)
            }
            is NetplayMessage.Mismatch -> out.writeByte(message.reason.wireId)
            is NetplayMessage.Ping -> {
                out.writeInt(message.sequence)
                out.writeLong(message.sentAtNanos)
            }
            is NetplayMessage.Pong -> {
                out.writeInt(message.sequence)
                out.writeLong(message.sentAtNanos)
            }
            NetplayMessage.Ready, NetplayMessage.Bye -> Unit
        }
    }

    private fun decode(bodyBytes: ByteArray): NetplayMessage {
        val input = ByteArrayInputStream(bodyBytes)
        val data = DataInputStream(input)

        try {
            val message =
                when (val type = data.readUnsignedByte()) {
                    TYPE_HELLO -> {
                        val version = data.readInt()
                        val roleId = data.readUnsignedByte()
                        val role = PeerRole.fromWireId(roleId) ?: throw ProtocolException("Unknown role: $roleId")
                        NetplayMessage.Hello(version, readString(data), role)
                    }
                    TYPE_GAME_INFO -> {
                        val systemId = readString(data)
                        val size = data.readLong()
                        NetplayMessage.GameInfo(GameFingerprint(systemId, size, readString(data)))
                    }
                    TYPE_READY -> NetplayMessage.Ready
                    TYPE_MISMATCH -> {
                        val reasonId = data.readUnsignedByte()
                        val reason =
                            MismatchReason.fromWireId(reasonId)
                                ?: throw ProtocolException("Unknown mismatch reason: $reasonId")
                        NetplayMessage.Mismatch(reason)
                    }
                    TYPE_PING -> NetplayMessage.Ping(data.readInt(), data.readLong())
                    TYPE_PONG -> NetplayMessage.Pong(data.readInt(), data.readLong())
                    TYPE_BYE -> NetplayMessage.Bye
                    else -> throw ProtocolException("Unknown message type: $type")
                }

            if (input.available() > 0) {
                throw ProtocolException("Unexpected extra bytes at the end of a message")
            }

            return message
        } catch (e: EOFException) {
            throw ProtocolException("Message shorter than expected", e)
        }
    }

    private fun writeString(
        out: DataOutputStream,
        value: String,
    ) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) { "String too long for the protocol: ${bytes.size} bytes" }
        out.writeShort(bytes.size)
        out.write(bytes)
    }

    private fun readString(data: DataInputStream): String {
        val length = data.readUnsignedShort()
        if (length > MAX_STRING_BYTES) {
            throw ProtocolException("String too long: $length bytes")
        }
        val bytes = ByteArray(length)
        data.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }
}
