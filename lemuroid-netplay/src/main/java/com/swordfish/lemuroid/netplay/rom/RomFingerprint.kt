package com.swordfish.lemuroid.netplay.rom

import java.io.InputStream
import java.security.MessageDigest

object RomFingerprint {
    private const val BUFFER_SIZE = 64 * 1024

    /**
     * Streams [input] once and returns its size and SHA-256. The stream is NOT closed: that's up to the caller.
     * [onProgress] receives the number of bytes processed so far, so big ROMs can show a progress indicator.
     */
    fun compute(
        systemId: String,
        input: InputStream,
        onProgress: ((Long) -> Unit)? = null,
    ): GameFingerprint {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L

        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
            total += read
            onProgress?.invoke(total)
        }

        return GameFingerprint(systemId, total, digest.digest().toHex())
    }

    private fun ByteArray.toHex(): String {
        val chars = "0123456789abcdef"
        val result = StringBuilder(size * 2)
        for (byte in this) {
            val value = byte.toInt() and 0xFF
            result.append(chars[value shr 4]).append(chars[value and 0x0F])
        }
        return result.toString()
    }
}
