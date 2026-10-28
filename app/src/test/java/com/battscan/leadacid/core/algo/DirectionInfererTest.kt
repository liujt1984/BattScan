package com.battscan.leadacid.core.algo

import com.battscan.leadacid.core.model.PowerDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The WS-BLE frame carries no direction bit, so regen braking is only separable from motoring by
 * whether the terminal voltage sits above or below the learned EMF. These tests pin that behaviour,
 * including the two ways it can silently go wrong: a first frame that confirms its own assumption,
 * and a low-margin frame that flips the sign on noise.
 */
class DirectionInfererTest {

    private val emf = 74.5
    private val resistance = 0.25

    private fun dischargeVoltage(magnitudeA: Double) = emf - magnitudeA * resistance
    private fun chargeVoltage(magnitudeA: Double) = emf + magnitudeA * resistance

    @Test
    fun firstDischargeFrameIsNotForcedIntoTheChargeBucket() {
        val inferer = DirectionInferer()
        val result = inferer.infer(10.0, dischargeVoltage(10.0), resistance, 0.8, 0L)

        // Seeding the EMF as a charge would make errCharge exactly zero and mislabel every ride
        // as regenerating from the very first frame.
        assertEquals(PowerDirection.DISCHARGE, result.direction)
        assertEquals(-10.0, result.currentA, 1e-9)
        assertEquals(74.5, result.emfV, 1e-6)
        assertEquals(1.0, result.confidence, 1e-9)
    }

    @Test
    fun steadyDischargeStaysDischargeAndLearnsTheRealEmf() {
        val inferer = DirectionInferer()
        var last: DirectionInferer.Inferred? = null
        (0 until 20).forEach { k ->
            last = inferer.infer(10.0, dischargeVoltage(10.0) - 0.15, resistance, 0.8, k * 1160L)
        }

        assertEquals(PowerDirection.DISCHARGE, last!!.direction)
        // A constant 10 A draw 0.15 V below the ideal prototype still has to be read as discharge,
        // and the EMF tracker must sit near the true 74.35 V, not drift toward the terminals.
        assertTrue("emf ${last!!.emfV}", last!!.emfV in 74.3..74.55)
    }

    @Test
    fun voltageAboveTheEmfPrototypeIsRegen() {
        val inferer = DirectionInferer()
        // Prime the EMF with a discharge, then brake.
        inferer.infer(10.0, dischargeVoltage(10.0), resistance, 0.8, 0L)

        val result = inferer.infer(6.0, chargeVoltage(6.0), resistance, 0.8, 1160L)

        assertEquals(PowerDirection.CHARGE, result.direction)
        assertEquals(6.0, result.currentA, 1e-9)
        assertTrue(result.confidence > 0.5)
    }

    @Test
    fun currentInsideTheDeadbandIsIdleAndZeroed() {
        val inferer = DirectionInferer()
        inferer.infer(10.0, dischargeVoltage(10.0), resistance, 0.8, 0L)

        val idle = inferer.infer(0.3, emf, resistance, 0.8, 5000L)

        assertEquals(PowerDirection.IDLE, idle.direction)
        assertEquals(0.0, idle.currentA, 1e-9)
        assertEquals(1.0, idle.confidence, 1e-9)
    }

    @Test
    fun ambiguousFramesKeepTheLastDirectionInsteadOfFlippingOnNoise() {
        val inferer = DirectionInferer()
        (0 until 10).forEach { k ->
            inferer.infer(10.0, dischargeVoltage(10.0), resistance, 0.8, k * 1160L)
        }

        // Voltage exactly on the EMF line is maximally ambiguous: the margin collapses to zero.
        val ambiguous = inferer.infer(10.0, emf, resistance, 0.8, 11_600L)

        assertEquals(PowerDirection.DISCHARGE, ambiguous.direction)
        assertEquals(0.0, ambiguous.confidence, 1e-9)
    }

    @Test
    fun aLongGapDoesNotLetTheEmfTrackerJumpFurtherThanItsWindow() {
        val inferer = DirectionInferer()
        (0 until 5).forEach { k -> inferer.infer(10.0, dischargeVoltage(10.0), resistance, 0.8, k * 1160L) }
        assertEquals(74.5, inferer.infer(10.0, dischargeVoltage(10.0), resistance, 0.8, 5 * 1160L).emfV, 1e-6)

        // The bike has been parked for ten minutes and the pack now reads 76 V. The step is capped
        // at MAX_STEP_SEC, so the tracker nudges toward the new evidence instead of replacing the
        // baseline with a single rested voltage.
        val afterGap = inferer.infer(10.0, 76.0, resistance, 0.8, 600_000L)

        assertEquals(PowerDirection.CHARGE, afterGap.direction)
        assertTrue("emf ${afterGap.emfV}", afterGap.emfV in 74.25..74.40)
    }

    @Test
    fun resetClearsTheLearnedBaseline() {
        val inferer = DirectionInferer()
        val primed = inferer.infer(10.0, 77.0, resistance, 0.8, 0L)
        // The tie-break seeded a discharge reading, so 77 V is stored as a 79.5 V EMF.
        assertEquals(79.5, primed.emfV, 1e-6)

        inferer.reset()
        val afterReset = inferer.infer(10.0, dischargeVoltage(10.0), resistance, 0.8, 5000L)

        assertEquals(PowerDirection.DISCHARGE, afterReset.direction)
        assertEquals(74.5, afterReset.emfV, 1e-6)
    }

    @Test
    fun zeroResistanceFallsBackToTheDischargeSideOnTies() {
        val inferer = DirectionInferer()
        // With no resistance the two prototypes coincide, so the tie-break must pick discharge.
        val result = inferer.infer(10.0, emf, 0.0, 0.8, 0L)

        assertEquals(PowerDirection.DISCHARGE, result.direction)
        assertEquals(-10.0, result.currentA, 1e-9)
    }
}
