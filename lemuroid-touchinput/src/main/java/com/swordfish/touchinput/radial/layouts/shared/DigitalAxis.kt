package com.swordfish.touchinput.radial.layouts.shared

/**
 * Turns the continuous value of a joystick axis into what a D-pad reports: -1, 0 or 1.
 * Games read a D-pad as pressed or not pressed, so the stick must be pushed a good part of its way to count.
 */
object DigitalAxis {
    const val THRESHOLD = 0.5f

    fun fromAnalog(value: Float): Float {
        return when {
            value >= THRESHOLD -> 1f
            value <= -THRESHOLD -> -1f
            else -> 0f
        }
    }
}
