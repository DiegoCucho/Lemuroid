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

    /**
     * Fingerprint of a game made of several files (a CD image is a cue sheet plus its bin tracks). The content hashes
     * of the files are sorted before being combined, so neither the file names nor their order matter: two players
     * get the same fingerprint as long as they have the same set of files. With a single file the result is the same
     * as [compute]. Streams are NOT closed. [onProgress] receives the total number of bytes processed so far.
     */
    fun computeAll(
        systemId: String,
        inputs: List<InputStream>,
        onProgress: ((Long) -> Unit)? = null,
    ): GameFingerprint {
        require(inputs.isNotEmpty()) { "A game needs at least one file" }

        var processedBefore = 0L
        val parts =
            inputs.map { input ->
                val base = processedBefore
                compute(systemId, input) { onProgress?.invoke(base + it) }.also { processedBefore += it.sizeBytes }
            }

        if (parts.size == 1) return parts.single()

        val combined = MessageDigest.getInstance("SHA-256")
        parts.map { it.sha256 }.sorted().forEach { combined.update(it.toByteArray(Charsets.US_ASCII)) }
        return GameFingerprint(systemId, parts.sumOf { it.sizeBytes }, combined.digest().toHex())
    }
}
