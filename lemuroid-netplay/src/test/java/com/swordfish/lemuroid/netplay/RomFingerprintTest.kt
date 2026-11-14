package com.swordfish.lemuroid.netplay

import com.swordfish.lemuroid.netplay.protocol.MismatchReason
import com.swordfish.lemuroid.netplay.rom.GameFingerprint
import com.swordfish.lemuroid.netplay.rom.RomFingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class RomFingerprintTest {
    @Test
    fun computesKnownSha256() {
        val fingerprint = RomFingerprint.compute("snes", ByteArrayInputStream("abc".toByteArray()))

        assertEquals("snes", fingerprint.systemId)
        assertEquals(3L, fingerprint.sizeBytes)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", fingerprint.sha256)
    }

    @Test
    fun emptyFilesHaveAValidFingerprint() {
        val fingerprint = RomFingerprint.compute("nes", ByteArrayInputStream(ByteArray(0)))

        assertEquals(0L, fingerprint.sizeBytes)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", fingerprint.sha256)
    }

    @Test
    fun bigInputsAreHashedInChunksAndReportProgress() {
        val data = ByteArray(300_000) { (it % 251).toByte() }
        val progress = mutableListOf<Long>()

        val fingerprint = RomFingerprint.compute("gba", ByteArrayInputStream(data)) { progress.add(it) }

        assertEquals(data.size.toLong(), fingerprint.sizeBytes)
        assertTrue("Expected several progress callbacks", progress.size > 1)
        assertEquals(data.size.toLong(), progress.last())
        assertEquals(progress.sorted(), progress)
    }

    @Test
    fun sameContentGivesTheSameFingerprintAndOneChangedByteDoesNot() {
        val data = ByteArray(1000) { it.toByte() }
        val changed = data.copyOf().also { it[500] = (it[500] + 1).toByte() }

        val first = RomFingerprint.compute("snes", ByteArrayInputStream(data))
        val second = RomFingerprint.compute("snes", ByteArrayInputStream(data.copyOf()))
        val third = RomFingerprint.compute("snes", ByteArrayInputStream(changed))

        assertNull(first.incompatibilityWith(second))
        assertEquals(MismatchReason.ROM_CONTENT, first.incompatibilityWith(third))
    }

    @Test
    fun differentSystemsAreIncompatible() {
        val snes = GameFingerprint("snes", 10, "aa")
        val nes = GameFingerprint("nes", 10, "aa")

        assertEquals(MismatchReason.SYSTEM, snes.incompatibilityWith(nes))
    }

    @Test
    fun differentSizesAreIncompatible() {
        val small = GameFingerprint("snes", 10, "aa")
        val big = GameFingerprint("snes", 11, "aa")

        assertEquals(MismatchReason.ROM_CONTENT, small.incompatibilityWith(big))
    }

    @Test
    fun hashComparisonIgnoresCase() {
        val lower = GameFingerprint("snes", 10, "abcdef")
        val upper = GameFingerprint("snes", 10, "ABCDEF")

        assertNull(lower.incompatibilityWith(upper))
    }

    private fun stream(text: String) = ByteArrayInputStream(text.toByteArray())

    @Test
    fun aSingleFileGivesTheSameResultWithComputeAll() {
        val single = RomFingerprint.compute("psx", stream("track"))
        val all = RomFingerprint.computeAll("psx", listOf(stream("track")))

        assertEquals(single, all)
    }

    @Test
    fun multiFileGamesIgnoreTheOrderOfTheFiles() {
        val first = RomFingerprint.computeAll("psx", listOf(stream("cue"), stream("bin1"), stream("bin2")))
        val second = RomFingerprint.computeAll("psx", listOf(stream("bin2"), stream("cue"), stream("bin1")))

        assertEquals(first, second)
        assertEquals(11L, first.sizeBytes)
    }

    @Test
    fun multiFileGamesNoticeAChangedFile() {
        val original = RomFingerprint.computeAll("psx", listOf(stream("cue"), stream("bin1")))
        val changed = RomFingerprint.computeAll("psx", listOf(stream("cue"), stream("bin2")))

        assertFalse(original.sha256 == changed.sha256)
        assertEquals(MismatchReason.ROM_CONTENT, original.incompatibilityWith(changed))
    }

    @Test
    fun multiFileGamesAreDifferentFromTheirFilesMergedIntoOne() {
        val several = RomFingerprint.computeAll("psx", listOf(stream("ab"), stream("cd")))
        val merged = RomFingerprint.compute("psx", stream("abcd"))

        assertFalse(several.sha256 == merged.sha256)
    }

    @Test
    fun multiFileProgressKeepsGrowingAcrossFiles() {
        val progress = mutableListOf<Long>()

        RomFingerprint.computeAll("psx", listOf(stream("1234"), stream("56"))) { progress.add(it) }

        assertEquals(listOf(4L, 6L), progress)
    }
}
