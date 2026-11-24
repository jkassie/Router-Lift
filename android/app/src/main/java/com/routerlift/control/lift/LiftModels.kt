package com.routerlift.control.lift

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** A named height, stored in steps from the lift's current zero. */
data class SavedPosition(val id: Long, val name: String, val steps: Long)

/** Fixed amounts for the nudge buttons, in the display unit (inches or millimetres). */
object Presets {
    val DEFAULT_INCHES = listOf(0.001, 0.005, 1.0 / 64, 1.0 / 32, 1.0 / 16, 1.0 / 8, 1.0 / 4, 1.0 / 2)
    val DEFAULT_MM = listOf(0.1, 0.25, 0.5, 1.0, 5.0, 10.0, 25.0)

    /** The vendor app refuses heights beyond this; the lift's travel is shorter still. */
    const val MAX_TRAVEL_INCHES = 5.0

    fun maxTravel(inches: Boolean) = if (inches) MAX_TRAVEL_INCHES else MAX_TRAVEL_INCHES * LiftUnits.MM_PER_INCH

    /** "1/16 in", "0.005 in", "0.25 mm". Inch amounts that are whole 64ths read as fractions. */
    fun label(value: Double, inches: Boolean): String {
        if (!inches) return "${decimal(value)} mm"
        val sixtyFourths = value * 64
        val whole = sixtyFourths.roundToLong()
        if (value < 1 && whole >= 1 && abs(sixtyFourths - whole) < 1e-6) {
            var n = whole
            var d = 64L
            while (n % 2 == 0L) {
                n /= 2
                d /= 2
            }
            return "$n/$d in"
        }
        return "${decimal(value)} in"
    }

    private fun decimal(value: Double): String =
        String.format(Locale.US, "%.4f", value).trimEnd('0').trimEnd('.')

    fun encode(values: List<Double>): String = values.joinToString(",")

    fun decode(text: String?): List<Double>? =
        text?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() }?.filter { it > 0 }
}
