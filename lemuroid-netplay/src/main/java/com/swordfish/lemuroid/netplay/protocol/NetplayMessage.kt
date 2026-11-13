package com.swordfish.lemuroid.netplay.protocol

import com.swordfish.lemuroid.netplay.rom.GameFingerprint

/** Bumped whenever the wire format changes in an incompatible way. Checked in the first message of a session. */
const val PROTOCOL_VERSION = 1

enum class PeerRole(val wireId: Int) {
    HOST(0),
    GUEST(1),
    ;

    companion object {
        fun fromWireId(id: Int): PeerRole? = values().firstOrNull { it.wireId == id }
    }
}

enum class MismatchReason(val wireId: Int) {
    /** The two apps speak different versions of the protocol. */
    PROTOCOL_VERSION(0),

    /** The players picked games from different consoles. */
    SYSTEM(1),

    /** Same console, but the ROM files are not identical. */
    ROM_CONTENT(2),
    ;

    companion object {
        fun fromWireId(id: Int): MismatchReason? = values().firstOrNull { it.wireId == id }
    }
}

sealed class NetplayMessage {
    /** First message sent by both peers. */
    data class Hello(
        val protocolVersion: Int,
        val playerName: String,
        val role: PeerRole,
    ) : NetplayMessage()

    /** Sent right after [Hello], so the other side can verify both players will run the same game. */
    data class GameInfo(val fingerprint: GameFingerprint) : NetplayMessage()

    /** "I checked your game and it matches mine." The session is ready once both peers sent it. */
    data object Ready : NetplayMessage()

    /** "I checked your game/version and we can't play together." */
    data class Mismatch(val reason: MismatchReason) : NetplayMessage()

    data class Ping(val sequence: Int, val sentAtNanos: Long) : NetplayMessage()

    /** Echoes the content of the [Ping] it answers; only the sender of the ping interprets [sentAtNanos]. */
    data class Pong(val sequence: Int, val sentAtNanos: Long) : NetplayMessage()

    /** Graceful close, so the other side can tell it apart from a dropped connection. */
    data object Bye : NetplayMessage()
}
