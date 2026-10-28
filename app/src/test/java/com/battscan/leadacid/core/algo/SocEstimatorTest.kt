package com.battscan.leadacid.core.algo

import com.battscan.leadacid.core.Samples
import com.battscan.leadacid.core.model.BatterySample
import com.battscan.leadacid.core.model.PowerDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression numbers for a 20 Ah / 2 h-rate pack with 36 series cells and a 0.25 ohm internal
 * resistance. The expected values come from the integration, derating and anchoring laws the class
 * implements, so changing any of them fails here rather than on a ride.
 */
class SocEstimatorTest {

    private class Harness(peukert: Double = 1.0, initialSoc: Double = 0.5) {
        val emfModel = EmfModel()
        val estimator = SocEstimator(OcvTable.LEAD_ACID_2V, emfModel, initialSoc)
        val config = com.battscan.leadacid.core.model.BatteryConfig(
            ratedCapacityAh = 20.0,
            ratedAtHours = 2.0,
            peukertExponent = peukert,
            internalResistanceOhm = 0.25,
        )

        /** Mirrors MonitorEngine: the V/I window is fed before the SOC update reads it. */
        fun step(sample: BatterySample): SocState {
            emfModel.add(sample.currentA, sample.voltageV, sample.monotonicMs)
            return estimator.update(sample, config, 0.25)
        }

        fun drive(currentA: Double, voltageV: Double, steps: Int, stepMs: Long, direction: PowerDirection? = null): SocState {
            var last = estimator.state
            (0 until steps).forEach { k ->
                val sample = if (direction == null) Samples.of(k * stepMs, currentA, voltageV)
                else Samples.of(k * stepMs, currentA, voltageV, direction = direction)
                last = step(sample)
            }
            return last
        }
    }

    @Test
    fun firstFrameOnlyEstablishesTheBaselineAndIntegratesNothing() {
        val state = Harness().drive(-10.0, 72.0, steps = 1, stepMs = 8000L)

        assertEquals(50.0, state.socPercent, 1e-9)
        assertEquals(0.0, state.dischargedAh, 1e-9)
        assertEquals(0.0, state.recoveredAh, 1e-9)
        assertEquals(2.06944, state.perCellEmfV, 1e-5)
        assertEquals(74.5, state.estimatedEmfV, 1e-3)
    }

    @Test
    fun steadyDischargeIntegratesAmpHoursAndDropsStateOfCharge() {
        val state = Harness().drive(-10.0, 72.0, steps = 20, stepMs = 8000L)

        // 19 integrated steps of 8 s at 10 A = 0.42222 Ah out of a 20 Ah pack.
        assertEquals(0.422222, state.dischargedAh, 1e-5)
        assertEquals(0.0, state.recoveredAh, 1e-9)
        assertEquals(20.0, state.effectiveCapacityAh, 1e-9)
        // Pure coulomb counting would read 47.89 %; the load-damped OCV pull lifts it to 48.66 %.
        assertEquals(48.6618, state.socPercent, 0.01)
        assertFalse(state.anchored)
    }

    @Test
    fun regenBrakingReturnsChargeAtTheConfiguredEfficiency() {
        val state = Harness().drive(8.0, 74.0, steps = 20, stepMs = 8000L)

        // 19 x 8 s at 8 A = 0.33778 Ah in, times the 0.85 lead-acid efficiency.
        assertEquals(0.287111, state.recoveredAh, 1e-5)
        assertEquals(0.0, state.dischargedAh, 1e-9)
        assertEquals(51.2548, state.socPercent, 0.01)
    }

    @Test
    fun alternatingLoadAndRegenSettlesOnTheNetLossAtTheChargeEfficiency() {
        // Ten alternations: five discharge steps and five charge steps of equal magnitude.
        val harness = Harness()
        var state = harness.estimator.state
        (0 until 11).forEach { k ->
            state = harness.step(Samples.of(k * 8000L, if (k % 2 == 0) -10.0 else 10.0, 72.0))
        }

        assertEquals(0.111111, state.dischargedAh, 1e-5)
        assertEquals(state.dischargedAh * 0.85, state.recoveredAh, 1e-5)
        assertTrue("soc was ${state.socPercent}", state.socPercent < 50.0)
    }

    @Test
    fun peukertDeratesUsableCapacityAboveTheRatedDischargeCurrent() {
        val atRated = Harness(peukert = 1.3).drive(-10.0, 72.0, steps = 20, stepMs = 8000L)
        val atDouble = Harness(peukert = 1.3).drive(-20.0, 70.0, steps = 20, stepMs = 8000L)

        // 10 A is the 2 h-rate current, so Peukert costs nothing there but bites hard at 20 A.
        assertEquals(20.0, atRated.effectiveCapacityAh, 1e-6)
        assertEquals(16.245, atDouble.effectiveCapacityAh, 0.01)
        assertEquals(atRated.dischargedAh * 2.0, atDouble.dischargedAh, 1e-4)
        assertEquals(48.6618, atRated.socPercent, 0.01)
        assertEquals(45.1914, atDouble.socPercent, 0.01)
    }

    @Test
    fun restingVoltageAnchorsTheIntegratorTowardOpenCircuitStateOfCharge() {
        val state = Harness().drive(0.2, 74.5, steps = 60, stepMs = 15_000L)

        assertTrue(state.anchored)
        assertEquals(78.207, state.ocvPercent, 0.05)
        assertEquals(73.3472, state.socPercent, 0.05)
        assertTrue(state.socPercent < state.ocvPercent)
        assertEquals(0.0, state.dischargedAh, 1e-9)
        assertTrue(state.quietSeconds > 300.0)
    }

    @Test
    fun loadedVoltageIsRefusedAsAnAnchor() {
        val state = Harness().drive(-20.0, 70.0, steps = 20, stepMs = 8000L)

        // A sagging 70 V under 20 A back-computes to 84.7 %; trusting it would hide a flat pack.
        assertEquals(84.722, state.ocvPercent, 0.05)
        assertFalse(state.anchored)
        assertTrue("weight ${state.anchorWeight}", state.anchorWeight < 0.10)
    }

    @Test
    fun taperAtFullVoltageClosesTheChargeAndSnapsToHundredPercent() {
        val state = Harness().drive(
            currentA = 0.5,
            voltageV = 77.0,
            steps = 10,
            stepMs = 8000L,
            direction = PowerDirection.CHARGE,
        )

        assertTrue(state.fullDetected)
        assertEquals(100.0, state.socPercent, 1e-9)
    }

    @Test
    fun hardDeepDischargeIsClampedNearZeroInsteadOfGoingNegative() {
        val state = Harness().drive(-60.0, 60.0, steps = 60, stepMs = 15_000L)

        assertTrue("soc ${state.socPercent}", state.socPercent in 0.0..0.05)
        assertTrue(state.remainingAh < 0.01)
        assertEquals(14.75, state.dischargedAh, 0.01)
    }

    @Test
    fun manualRecalibrationAndResetReplaceTheIntegrator() {
        val harness = Harness()
        harness.drive(-10.0, 72.0, steps = 20, stepMs = 8000L)

        harness.estimator.forceSet(0.9)
        val afterForce = harness.step(Samples.of(200_000L, -10.0, 72.0))
        assertTrue(afterForce.socPercent > 88.0)

        harness.estimator.reset(0.30)
        assertEquals(30.0, harness.estimator.state.socPercent, 1e-9)
        assertEquals(0.0, harness.estimator.state.dischargedAh, 1e-9)
    }

    @Test
    fun remainingEnergyIsReportedInBothAmpHoursAndWattHours() {
        val harness = Harness()
        harness.estimator.forceSet(0.5)
        val state = harness.step(Samples.of(0L, -10.0, 72.0))

        assertEquals(10.0, state.remainingAh, 1e-9)
        assertEquals(720.0, state.remainingWh, 1e-9)
        // The EMF correction lifts the 72 V terminal reading to 74.5 V across 36 cells.
        assertEquals(74.5, state.estimatedEmfV, 1e-3)
        assertEquals(2.06944, state.perCellEmfV, 1e-5)
    }

    @Test
    fun socSeedsFromTheConfiguredManualValue() {
        val state = Harness(initialSoc = 0.75).drive(-10.0, 72.0, steps = 1, stepMs = 8000L)

        assertEquals(75.0, state.socPercent, 1e-9)
    }
}
