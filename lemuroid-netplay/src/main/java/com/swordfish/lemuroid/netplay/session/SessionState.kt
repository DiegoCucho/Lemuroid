package com.swordfish.lemuroid.netplay.session

import com.swordfish.lemuroid.netplay.protocol.MismatchReason
import com.swordfish.lemuroid.netplay.rom.GameFingerprint

enum class SessionError {
    /** The host couldn't be reached (wrong address, not listening, different network...). */
    CONNECT_FAILED,

    /** The other side connected but never completed the handshake. */
    TIMEOUT,

    /** The link dropped, or the other side stopped answering. */
    CONNECTION_LOST,

    /** The other side sent something that isn't valid netplay traffic. */
    PROTOCOL_ERROR,
}

enum class CloseReason {
    /** We closed the session. */
    LOCAL,

    /** The other player left on purpose. */
    PEER_LEFT,
}

sealed class SessionState {
    /** Created, but [NetplaySession.start] wasn't called yet. */
    data object Idle : SessionState()

    /** Host: waiting for the other player to arrive. Guest: reaching the host. */
    data object Connecting : SessionState()

    /** Connected, exchanging versions and games to check we are compatible. */
    data class Handshaking(val remote: String) : SessionState()

    /** Both sides run the same game: they can start playing. [roundTripMillis] is null until measured. */
    data class Ready(val peerName: String, val roundTripMillis: Long?) : SessionState()

    /** Terminal: the players can't play together. [peerGame] is null if the other side never got to tell us. */
    data class Mismatch(
        val reason: MismatchReason,
        val detectedByPeer: Boolean,
        val peerGame: GameFingerprint?,
    ) : SessionState()

    /** Terminal. */
    data class Failed(val error: SessionError, val detail: String?) : SessionState()

    /** Terminal. */
    data class Closed(val reason: CloseReason) : SessionState()

    val isTerminal: Boolean
        get() = this is Mismatch || this is Failed || this is Closed
}
