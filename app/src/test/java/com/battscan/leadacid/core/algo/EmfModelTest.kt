package com.battscan.leadacid.core.algo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmfModelTest {

    private fun feed(model: EmfModel, emfV: Double, resistance: Double, currents: List<Double>, stepMs: Long) {
        currents.forEachIndexed { index, i ->
            model.add(i, emfV + i * resistance, index * stepMs)
        }
    }

    @Test
    fun recoversResistanceAndEmfFromASignedVoltageCurrentPair() {
        val model = EmfModel()
        // Positive current flows into the pack, so a healthy battery has V = EMF + I*R.
        val currents = (-25..5).map { it.toDouble() / 2.0 }
        feed(model, 74.5, 0.25, currents, 1160L)

        val fit = model.fit()
        assertTrue(fit.trustworthy)
        assertEquals(0.25, fit.slopeOhm, 1e-6)
        assertEquals(74.5, fit.interceptV, 1e-6)
        assertEquals(1.0, fit.correlation, 1e-6)
        assertEquals(15.0, fit.currentSpreadA, 1e-6)
        assertEquals(74.5, fit.emfV, 1e-6)
    }

    @Test
    fun refusesToJudgeUntilTheCurrentActuallyVaries() {
        val model = EmfModel()
        feed(model, 74.5, 0.25, List(10) { -10.0 }, 1160L)

        val fit = model.fit()
        assertFalse(fit.trustworthy)
        assertEquals("样本不足或电流变化太小，无法判定", fit.verdict())
    }

    @Test
    fun fewerThanFourPointsYieldNoFitAtAll() {
        val model = EmfModel()
        feed(model, 74.5, 0.25, listOf(-10.0, -12.0, -8.0), 1160L)

        assertEquals(EmfFit.EMPTY, model.fit())
    }

    @Test
    fun aPositiveSlopeOfOdometerSizeIsFlaggedAsAMagnitudeField() {
        // This is exactly the 2026-10-28 capture: unsigned current, +1.79 V/A.
        val model = EmfModel()
        (0 until 150).forEach { index ->
            val current = 7.29 + (index % 40) * 0.097
            model.add(current, 58.15 + 1.7933 * current, index * 1160L)
        }

        val fit = model.fit()
        assertTrue(fit.trustworthy)
        assertEquals(1.7933, fit.slopeOhm, 0.01)
        assertTrue(fit.verdict().contains("无符号幅值"))
    }

    @Test
    fun aSmallPositiveSlopeIsTheHealthyCaseForSignedCurrent() {
        val model = EmfModel()
        feed(model, 74.5, 0.25, (-25..5).map { it.toDouble() / 2.0 }, 1160L)

        assertTrue(model.fit().verdict().contains("量级合理"))
    }

    @Test
    fun aNegativeSlopeContradictsTheChargePositiveConvention() {
        val model = EmfModel()
        feed(model, 74.5, -0.30, (-25..5).map { it.toDouble() / 2.0 }, 1160L)

        assertTrue(model.fit().verdict().contains("符号模式"))
    }

    @Test
    fun explicitResistanceAlwaysWinsOverTheRegression() {
        val model = EmfModel()
        feed(model, 74.5, 0.25, (-25..5).map { it.toDouble() / 2.0 }, 1160L)

        assertEquals(0.10, model.resistanceFor(0.10), 1e-9)
        assertEquals(0.25, model.resistanceFor(null), 1e-6)
    }

    @Test
    fun untrustworthyFitsFallBackToTheDefaultPackResistance() {
        val model = EmfModel()
        feed(model, 74.5, 0.25, List(5) { -10.0 }, 1160L)

        assertEquals(EmfModel.DEFAULT_RESISTANCE_OHM, model.resistanceFor(null), 1e-9)
    }

    @Test
    fun anImplausiblyLargeLearnedSlopeIsRejected() {
        // Exactly the 2026-10-28 capture: unsigned current fitting to +1.79 V/A. Adopting that as
        // the pack resistance would sit the charge/discharge prototypes 18 V apart and mislabel
        // every frame, so the guard keeps the default instead.
        val model = EmfModel()
        (0 until 150).forEach { index ->
            val current = 7.29 + (index % 40) * 0.097
            model.add(current, 58.15 + 1.7933 * current, index * 1160L)
        }

        assertEquals(EmfModel.DEFAULT_RESISTANCE_OHM, model.resistanceFor(null), 1e-9)
    }

    @Test
    fun anImplausiblySmallLearnedSlopeIsRejectedToo() {
        val model = EmfModel()
        // Current sweeps 15 A while the voltage barely moves: a trustworthy fit with a 0.004 ohm
        // slope, which no 36-cell lead-acid pack has. The floor rejects it.
        (0 until 60).forEach { index ->
            model.add(-10.0 - index * 0.25, 72.0 - index * 0.001, index * 1160L)
        }

        assertTrue(model.fit().trustworthy)
        assertEquals(0.004, model.fit().slopeOhm, 1e-6)
        assertEquals(EmfModel.DEFAULT_RESISTANCE_OHM, model.resistanceFor(null), 1e-9)
    }

    @Test
    fun windowOnlyKeepsTheMostRecentPoints() {
        val model = EmfModel(windowSize = 10)
        (0 until 100).forEach { model.add(-10.0, 72.0, it * 1160L) }

        assertEquals(10, model.fit().sampleCount)
    }

    @Test
    fun zeroAndNegativeVoltagesAreNeverFedIntoTheFit() {
        val model = EmfModel()
        model.add(-10.0, 0.0, 0L)
        model.add(-10.0, -5.0, 1160L)

        assertEquals(EmfFit.EMPTY, model.fit())
    }

    @Test
    fun cellEmfVoltageCorrectsTheTerminalReadingAcrossTheSeriesCount() {
        val model = EmfModel()

        // 10 A of discharge out of a 36-cell pack with 0.25 ohm: 72 V at the terminals is 74.5 V EMF.
        assertEquals(2.06944, model.cellEmfVoltage(72.0, -10.0, 36, 0.25), 1e-5)
        assertEquals(2.125, model.cellEmfVoltage(76.5, 0.0, 36, 0.25), 1e-9)
        // A pack with no configured series count must not be divided by zero.
        assertEquals(72.0, model.cellEmfVoltage(72.0, -10.0, 0, 0.25), 1e-9)
    }

    @Test
    fun quietSecondsCountsFromTheLastFrameAboveTheDeadband() {
        val model = EmfModel()
        feed(model, 74.5, 0.25, listOf(-10.0, -10.0, 0.0, 0.0, 0.0), 1000L)

        // The last loaded frame sits at index 1, i.e. 1000 ms; now is 4000 ms.
        assertEquals(3.0, model.quietSeconds(0.8, 4000L), 1e-9)
    }

    @Test
    fun anEntirelyQuietWindowMeasuresItsOwnSpan() {
        val model = EmfModel()
        feed(model, 74.5, 0.25, listOf(0.0, 0.0, 0.0), 1000L)

        // Nothing ever crossed the deadband, so the whole three-second span counts as rest.
        assertEquals(3.0, model.quietSeconds(0.8, 3000L), 1e-9)
    }

    @Test
    fun resetEmptiesTheRegressionWindow() {
        val model = EmfModel()
        feed(model, 74.5, 0.25, (-25..5).map { it.toDouble() / 2.0 }, 1160L)
        model.reset()

        assertEquals(EmfFit.EMPTY, model.fit())
        assertEquals(0.0, model.quietSeconds(0.8, 10_000L), 1e-9)
    }
}
