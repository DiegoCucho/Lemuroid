package com.swordfish.lemuroid.netplay.rom

import com.swordfish.lemuroid.netplay.protocol.MismatchReason

/**
 * Identifies the exact game a player has loaded. Two players can only play together if both emulators run the same
 * core on byte-identical ROMs, otherwise the emulations diverge and the game desyncs after a few frames.
 */
data class GameFingerprint(
    val systemId: String,
    val sizeBytes: Long,
    val sha256: String,
) {
    /**
     * Returns why [other] can't be played together with this game, or null when both are compatible.
     * The file name is deliberately ignored: players may have renamed their copy.
     */
    fun incompatibilityWith(other: GameFingerprint): MismatchReason? {
        return when {
            systemId != other.systemId -> MismatchReason.SYSTEM
            sizeBytes != other.sizeBytes -> MismatchReason.ROM_CONTENT
            !sha256.equals(other.sha256, ignoreCase = true) -> MismatchReason.ROM_CONTENT
            else -> null
        }
    }
}
