package com.battscan.leadacid.core.algo

import com.battscan.leadacid.core.Samples
import com.battscan.leadacid.core.model.BatteryConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A 1.2 s poll on an e-bike that sags every time the throttle is twisted means a raw threshold
 * comparison would scream constantly. These tests pin the debounce, the hysteresis band and the
 * ordering the dashboard depends on.
 */
class AlarmEngineTest {

    private val config = BatteryConfig()

    private fun socState(percent: Double = 50.0, ocv: Double = 50.0, anchored: Boolean = false) = SocState(
        socPercent = percent,
        ocvPercent = ocv,
        anchorWeight = 0.0,
        estimatedEmfV = 74.5,
        perCellEmfV = 2.07,
        remainingAh = percent,
        remainingWh = percent * 72.0,
        dischargedAh = 0.0,
        recoveredAh = 0.0,
        effectiveCapacityAh = 20.0,
        quietSeconds = 0.0,
        anchored = anchored,
        fullDetected = false,
    )

    private fun trustworthyFit(slope: Double) = EmfFit(
        slopeOhm = slope,
        interceptV = 74.5,
        correlation = 0.95,
        sampleCount = 60,
        currentSpreadA = 8.0,
    )

    private fun check(
        engine: AlarmEngine,
        voltageV: Double = 72.0,
        currentA: Double = -10.0,
        tempC: Double? = null,
        at: Long,
        soc: SocState = socState(),
        fit: EmfFit? = null,
        linkDeadMs: Long = 0L,
    ): List<ActiveAlarm> = engine.evaluate(
        sample = Samples.of(at, currentA, voltageV, tempC),
        config = config,
        soc = soc,
        fit = fit,
        linkDeadMs = linkDeadMs,
    )

    @Test
    fun aHealthyFrameProducesNothing() {
        val engine = AlarmEngine()

        assertTrue(check(engine, at = 0L).isEmpty())
        assertTrue(check(engine, at = 3000L).isEmpty())
    }

    @Test
    fun oneSaggingFrameDoesNotTripTheLowVoltageAlarm() {
        val engine = AlarmEngine()

        // 60.0 V over 36 cells is 1.667 V per cell, deep under the 1.75 V cutoff.
        assertTrue(check(engine, voltageV = 60.0, at = 0L).isEmpty())
        assertTrue(check(engine, voltageV = 60.0, at = 2000L).isEmpty())
    }

    @Test
    fun sustainedSagTripsItOnceTheDebounceExpires() {
        val engine = AlarmEngine(debounceMs = 2500L)

        check(engine, voltageV = 60.0, at = 0L)
        check(engine, voltageV = 60.0, at = 1000L)
        val alarms = check(engine, voltageV = 60.0, at = 2600L)

        assertEquals(1, alarms.size)
        assertEquals(AlarmKind.UNDER_VOLTAGE, alarms.first().kind)
        assertEquals(AlarmSeverity.CRITICAL, alarms.first().severity)
        // The alarm remembers when the condition first appeared, not when it was raised.
        assertEquals(0L, alarms.first().sinceMonotonicMs)
    }

    @Test
    fun theAlarmStaysListedWhileVoltageIsInsideTheHysteresisBand() {
        val engine = AlarmEngine(debounceMs = 0L)

        check(engine, voltageV = 60.0, at = 0L)
        // 1.78 V per cell is above the 1.75 V cutoff but still under cutoff + 0.05 V hysteresis,
        // so it downgrades to WARN instead of blinking out.
        val stillOn = check(engine, voltageV = 36 * 1.78, at = 1000L)

        assertEquals(1, stillOn.size)
        assertEquals(AlarmKind.UNDER_VOLTAGE, stillOn.first().kind)
        assertEquals(AlarmSeverity.WARN, stillOn.first().severity)

        // Recovering past the band clears it.
        val cleared = check(engine, voltageV = 36 * 1.90, at = 2000L)
        assertTrue(cleared.isEmpty())
    }

    @Test
    fun warnAndCriticalBothPresentSortCriticalFirst() {
        val engine = AlarmEngine(debounceMs = 0L)

        // 55 C trips the warn temperature (55 + 3 hysteresis = 58) and 40 A trips warn current
        // (30 + 1.5 = 31.5), while the sagging voltage is critical.
        val alarms = check(engine, voltageV = 60.0, currentA = -40.0, tempC = 60.0, at = 0L)

        assertTrue(alarms.size >= 2)
        assertEquals(AlarmSeverity.CRITICAL, alarms.first().severity)
        assertTrue(alarms.last().severity != AlarmSeverity.CRITICAL)
    }

    @Test
    fun overCurrentUsesTheMagnitudeNotTheSignSoRegenSpikesCountToo() {
        val engine = AlarmEngine(debounceMs = 0L)

        val regen = check(engine, currentA = 50.0, at = 0L)

        assertEquals(1, regen.size)
        assertEquals(AlarmKind.OVER_CURRENT, regen.first().kind)
        assertEquals(AlarmSeverity.CRITICAL, regen.first().severity)
    }

    @Test
    fun normalRidingCurrentStaysBelowTheWarnThreshold() {
        val engine = AlarmEngine(debounceMs = 0L)

        // The capture peaked at 11.17 A; nothing here should fire.
        listOf(-7.29, -10.0, -11.17, 8.0).forEach { c ->
            assertTrue(check(engine, currentA = c, at = 0L).isEmpty())
        }
    }

    @Test
    fun missingTemperatureSensorIsNotTreatedAsColdOrHot() {
        val engine = AlarmEngine(debounceMs = 0L)

        assertTrue(check(engine, tempC = null, at = 0L).isEmpty())
        assertTrue(check(engine, tempC = 44.0, at = 0L).isEmpty())
        assertEquals(AlarmSeverity.WARN, check(engine, tempC = 60.0, at = 0L).first().severity)
        assertEquals(AlarmSeverity.CRITICAL, check(engine, tempC = 70.0, at = 0L).first().severity)
    }

    @Test
    fun lowStateOfChargeAlarmsEvenWhenThePackLooksHealthy() {
        val engine = AlarmEngine(debounceMs = 0L)

        assertEquals(AlarmSeverity.WARN, check(engine, at = 0L, soc = socState(18.0)).first().severity)
        assertEquals(AlarmKind.LOW_SOC, check(engine, at = 0L, soc = socState(18.0)).first().kind)
        assertEquals(AlarmSeverity.CRITICAL, check(engine, at = 0L, soc = socState(8.0)).first().severity)
        assertTrue(check(engine, at = 0L, soc = socState(25.0)).isEmpty())
    }

    @Test
    fun integratorAndVoltageEstimatesAreOnlyComparedOnceAnchoringIsTrusted() {
        val engine = AlarmEngine(debounceMs = 0L)

        // 20 % apart is under the 25 % divergence rule; 30 % apart is over it but unanchored.
        assertTrue(check(engine, at = 0L, soc = socState(50.0, 70.0, anchored = true)).isEmpty())
        assertTrue(check(engine, at = 0L, soc = socState(50.0, 80.0, anchored = false)).isEmpty())

        val diverging = check(engine, at = 0L, soc = socState(50.0, 80.0, anchored = true))
        assertEquals(1, diverging.size)
        assertEquals(AlarmKind.SOC_DIVERGENCE, diverging.first().kind)
        assertEquals(AlarmSeverity.INFO, diverging.first().severity)
    }

    @Test
    fun aPhysicallyImpossibleSlopeIsReportedAsAMappingSuspect() {
        val engine = AlarmEngine(debounceMs = 0L)

        assertTrue(check(engine, at = 0L, fit = trustworthyFit(1.79)).first().kind == AlarmKind.MAPPING_SUSPECT)
        // A plausible 0.25 ohm pack must not raise the alarm.
        assertTrue(check(engine, at = 0L, fit = trustworthyFit(0.25)).isEmpty())
        // And a fit the model itself distrusts must not either.
        val weak = EmfFit(3.0, 74.5, 0.2, 60, 8.0)
        assertTrue(check(engine, at = 0L, fit = weak).isEmpty())
    }

    @Test
    fun silenceOnTheLinkIsItsOwnAlarm() {
        val engine = AlarmEngine(debounceMs = 0L)

        val alarms = check(engine, at = 0L, linkDeadMs = 9000L)

        assertEquals(AlarmKind.LINK_DEAD, alarms.first().kind)
        assertEquals(AlarmSeverity.WARN, alarms.first().severity)
        assertTrue(check(engine, at = 0L, linkDeadMs = 7000L).isEmpty())
    }

    @Test
    fun clearingAndResettingDropBothPendingAndActiveState() {
        val engine = AlarmEngine(debounceMs = 2500L)

        check(engine, voltageV = 60.0, at = 0L)
        engine.reset()
        val afterReset = check(engine, voltageV = 60.0, at = 1000L)

        assertTrue(afterReset.isEmpty())

        check(engine, voltageV = 60.0, at = 5000L)
        check(engine, voltageV = 60.0, at = 8000L)
        assertTrue(check(engine, voltageV = 60.0, at = 9000L).isNotEmpty())
        engine.clear(AlarmKind.UNDER_VOLTAGE)
        assertTrue(check(engine, voltageV = 60.0, at = 10_000L).isEmpty())
    }

    @Test
    fun rangeEstimateFallsBackToTheConfiguredConsumptionUntilPowerIsKnown() {
        val estimator = RangeEstimator()
        val soc = socState(50.0) // remainingWh = 50 * 72 = 3600

        val noPower = estimator.estimate(soc, StatsSummary.EMPTY, config)
        assertEquals(200.0, noPower.kilometresToEnd, 1e-6)
        assertTrue(noPower.minutesToEnd.isNaN())
        assertFalse(noPower.basedOnMeasuredPower)

        val withPower = estimator.estimate(soc, fiveMinuteDischarge(720.0), config)
        assertTrue(withPower.basedOnMeasuredPower)
        assertEquals(300.0, withPower.minutesToEnd, 1e-6)
        // Text rendering is locale-dependent, so only the unit and presence are pinned.
        assertTrue(withPower.minutesText().contains("300"))
        assertTrue(withPower.kmText().contains("km"))

        val idle = estimator.estimate(soc, fiveMinuteDischarge(0.0), config)
        assertTrue(idle.minutesToEnd.isNaN())
        assertEquals("计算中", idle.minutesText())
    }

    private fun fiveMinuteDischarge(watts: Double) = StatsSummary(
        windowSeconds = 300.0,
        avgVoltageV = 72.0,
        avgCurrentA = -watts / 72.0,
        avgMagnitudeCurrentA = watts / 72.0,
        avgPowerW = -watts,
        avgDischargePowerW = watts,
        energyDischargedWh = watts * 300.0 / 3600.0,
        energyRecoveredWh = 0.0,
        minVoltageV = 71.0,
        maxVoltageV = 73.0,
        peakDischargeA = watts / 72.0,
        peakRegenA = 0.0,
        sampleCount = 260,
    )

    @Test
    fun anAccelBurstThatRecoversInsideTheDebounceNeverLightsTheBoard() {
        val engine = AlarmEngine(debounceMs = 2500L)

        // The exact pattern from the capture: a hard pull for 1.2 s, then back to normal.
        check(engine, voltageV = 60.0, at = 0L)
        val during = check(engine, voltageV = 60.0, at = 1200L)
        val after = check(engine, voltageV = 72.0, at = 2400L)

        assertTrue(during.isEmpty())
        assertTrue(after.isEmpty())
    }

    @Test
    fun aSingleHealthyFrameDropsATrippedAlarm() {
        val engine = AlarmEngine(debounceMs = 2500L)

        (0..3).forEach { check(engine, voltageV = 60.0, at = it * 1200L) }
        val tripped = engine.evaluate(
            sample = Samples.of(4800L, -10.0, 72.0),
            config = config,
            soc = socState(),
            fit = null,
            linkDeadMs = 0L,
        )

        // The debounce protects the onset, but recovery is immediate: one frame above the band
        // clears the alarm. Pinned here so a future hold-off timer is a deliberate change.
        assertTrue(tripped.isEmpty())
    }
}
