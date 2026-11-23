package com.routerlift.control.lift

import org.junit.Assert.assertEquals
import org.junit.Test

class LiftProtocolTest {
    @Test
    fun commands() {
        assertEquals("R:1&S:45", LiftCommand.jog(up = true, percent = 45))
        assertEquals("R:0&S:130", LiftCommand.jog(up = false, percent = 100))
        assertEquals("X:1&S:99", LiftCommand.runToLimit(up = true, percent = 99))
        assertEquals("T:-3410&S:60", LiftCommand.travelTo(-3410, 60))
        assertEquals("A:-171", LiftCommand.moveBy(-171))
        assertEquals("E:1705", LiftCommand.setPosition(1705))
        assertEquals("S:1", LiftCommand.setSpeed(0))
    }

    @Test
    fun switchStateFlagsAreActiveLow() {
        assertEquals(LiftEvent.Switches(SwitchState()), LiftParser.parse("^SS1111"))
        assertEquals(LiftEvent.Switches(SwitchState(upperLimit = true)), LiftParser.parse("^SS0111"))
        assertEquals(LiftEvent.Switches(SwitchState(lowerLimit = true)), LiftParser.parse("^SS1011"))
        assertEquals(LiftEvent.Switches(SwitchState(runningUp = true)), LiftParser.parse("^SS1101\r"))
        assertEquals(LiftEvent.Switches(SwitchState(runningDown = true)), LiftParser.parse("^SS1110"))
        assertEquals(LiftEvent.Unknown("^SS11"), LiftParser.parse("^SS11"))
    }

    @Test
    fun positionAndOtherReplies() {
        assertEquals(LiftEvent.Position(1234), LiftParser.parse("^L001234"))
        assertEquals(LiftEvent.Position(-123), LiftParser.parse("^L-00123"))
        assertEquals(LiftEvent.Position(42), LiftParser.parse("^L    42"))
        assertEquals(LiftEvent.Unknown("^L-"), LiftParser.parse("^L-"))
        assertEquals(LiftEvent.MemoryReached(1705), LiftParser.parse("^MB001705"))
        assertEquals(LiftEvent.Version("2.1"), LiftParser.parse("^V2.1"))
        assertEquals(LiftEvent.Error("E099"), LiftParser.parse("^E099"))
        assertEquals(null, LiftParser.parse("  "))
    }

    @Test
    fun units() {
        assertEquals(1705L, LiftUnits.toSteps(1.0, inches = true, stepsPerInch = 1705f))
        assertEquals(1705L, LiftUnits.toSteps(25.4, inches = false, stepsPerInch = 1705f))
        assertEquals("0.500", LiftUnits.format(853, inches = true, stepsPerInch = 1706f))
        assertEquals("25.40", LiftUnits.format(1705, inches = false, stepsPerInch = 1705f))
    }
}
