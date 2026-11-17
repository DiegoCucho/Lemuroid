package com.swordfish.touchinput.radial.layouts.shared

import org.junit.Assert.assertEquals
import org.junit.Test

class DigitalAxisTest {
    @Test
    fun centerAndSmallMovementsAreNotPressed() {
        listOf(0f, 0.1f, -0.1f, 0.49f, -0.49f).forEach {
            assertEquals("value $it", 0f, DigitalAxis.fromAnalog(it))
        }
    }

    @Test
    fun pushingTheStickFarEnoughPressesTheDirection() {
        listOf(0.5f, 0.7f, 1f).forEach { assertEquals("value $it", 1f, DigitalAxis.fromAnalog(it)) }
        listOf(-0.5f, -0.7f, -1f).forEach { assertEquals("value $it", -1f, DigitalAxis.fromAnalog(it)) }
    }

    @Test
    fun theCrossValuesAreNotChanged() {
        listOf(-1f, 0f, 1f).forEach { assertEquals(it, DigitalAxis.fromAnalog(it)) }
    }

    @Test
    fun aDiagonalPushPressesBothAxes() {
        // The stick at 45 degrees with full push reports about (0.71, 0.71).
        assertEquals(1f, DigitalAxis.fromAnalog(0.71f))
        assertEquals(-1f, DigitalAxis.fromAnalog(-0.71f))
    }

    @Test
    fun anAlmostHorizontalPushDoesNotPressUpOrDown() {
        assertEquals(1f, DigitalAxis.fromAnalog(0.98f))
        assertEquals(0f, DigitalAxis.fromAnalog(0.2f))
    }
}
