package com.routerlift.control.lift

import kotlin.math.roundToLong

/**
 * Wire protocol of the MLCS PowerLift Pro, recovered from the vendor app (see PROTOCOL.md).
 * Plain ASCII lines; the newline terminator is added by the transport.
 */
object LiftCommand {
    const val WAKE = "W"
    const val REQUEST_VERSION = "V"
    const val REQUEST_SWITCH_STATE = "SS"
    const val REQUEST_POSITION = "P"
    const val STOP = "O"
    const val CANCEL = "C"
    const val ZERO = "Z"

    /** The lift takes 1-99 as a percentage; the vendor app sends 130 for "100%". */
    fun wireSpeed(percent: Int): Int = if (percent >= 100) 130 else percent.coerceIn(1, 99)

    fun setSpeed(percent: Int) = "S:${wireSpeed(percent)}"

    /** Runs until [STOP] is sent. */
    fun jog(up: Boolean, percent: Int) = "R:${dir(up)}&S:${wireSpeed(percent)}"

    /** Runs to the upper or lower limit switch. */
    fun runToLimit(up: Boolean, percent: Int) = "X:${dir(up)}&S:${wireSpeed(percent)}"

    /** Relative move; positive is up. Uses the speed last set with [setSpeed]. */
    fun moveBy(steps: Long) = "A:$steps"

    fun travelTo(steps: Long, percent: Int) = "T:$steps&S:${wireSpeed(percent)}"

    /** Tells the lift what its current position is, without moving. */
    fun setPosition(steps: Long) = "E:$steps"

    private fun dir(up: Boolean) = if (up) 1 else 0
}

data class SwitchState(
    val upperLimit: Boolean = false,
    val lowerLimit: Boolean = false,
    val runningUp: Boolean = false,
    val runningDown: Boolean = false,
) {
    val moving get() = runningUp || runningDown
}

sealed interface LiftEvent {
    data class Switches(val state: SwitchState) : LiftEvent
    data class Position(val steps: Long) : LiftEvent
    data class MemoryReached(val steps: Long) : LiftEvent
    data class Version(val text: String) : LiftEvent
    data class Error(val code: String) : LiftEvent
    data class Unknown(val line: String) : LiftEvent
}

object LiftParser {
    fun parse(raw: String): LiftEvent? {
        val line = raw.trim()
        if (line.isEmpty()) return null
        return when {
            line.startsWith("^SS") -> parseSwitches(line)
            line.startsWith("^MB") -> line.substring(3).trim().toLongOrNull()?.let(LiftEvent::MemoryReached)
            line.startsWith("^L") -> line.substring(2).trim().toLongOrNull()?.let(LiftEvent::Position)
            line.startsWith("^V") -> LiftEvent.Version(line.substring(2).trim())
            line.startsWith("^E") -> LiftEvent.Error(line.substring(1))
            else -> null
        } ?: LiftEvent.Unknown(line)
    }

    /** `^SSabcd`: a flag is active when it is '0'. */
    private fun parseSwitches(line: String): LiftEvent? {
        if (line.length < 7) return null
        return LiftEvent.Switches(
            SwitchState(
                upperLimit = line[3] == '0',
                lowerLimit = line[4] == '0',
                runningUp = line[5] == '0',
                runningDown = line[6] == '0',
            )
        )
    }

    fun describeError(code: String): String = when (code) {
        "E099" -> "E099: the lift reports that its motor has no power. Check that the controller is switched on and its fuse and power supply are good."
        else -> "Lift error $code"
    }
}

object LiftUnits {
    const val DEFAULT_STEPS_PER_INCH = 1705f
    const val MM_PER_INCH = 25.4

    fun toSteps(value: Double, inches: Boolean, stepsPerInch: Float): Long {
        val asInches = if (inches) value else value / MM_PER_INCH
        return (asInches * stepsPerInch).roundToLong()
    }

    fun fromSteps(steps: Long, inches: Boolean, stepsPerInch: Float): Double {
        val asInches = steps.toDouble() / stepsPerInch
        return if (inches) asInches else asInches * MM_PER_INCH
    }

    fun format(steps: Long, inches: Boolean, stepsPerInch: Float): String {
        val value = fromSteps(steps, inches, stepsPerInch)
        return if (inches) String.format(java.util.Locale.US, "%.3f", value)
        else String.format(java.util.Locale.US, "%.2f", value)
    }
}
