package com.battscan.leadacid.core.algo

import com.battscan.leadacid.core.Samples
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The five-minute block the dashboard shows. Every mean here is integrated over real elapsed time,
 * so these tests deliberately use uneven intervals - the 1.16 s poll drifts with link quality and a
 * plain arithmetic mean would quietly lie about average power.
 */
class RollingStatsTest {

    @Test
    fun emptyAndSingleSampleWindowsHaveNoStatistics() {
        val stats = RollingStats()
        assertEquals(StatsSummary.EMPTY, stats.summary(0L))

        stats.add(Samples.of(0L, -10.0, 72.0))
        assertEquals(StatsSummary.EMPTY, stats.summary(1000L))
    }

    @Test
    fun steadyDischargeProducesTimeWeightedMeansAndEnergy() {
        val stats = RollingStats()
        val start = 100_000L
        // 61 samples one second apart = 60 s of covered time.
        (0..60).forEach { stats.add(Samples.of(start + it * 1000L, -10.0, 72.0)) }

        val summary = stats.summary(start + 60_000L)
        assertEquals(60.0, summary.windowSeconds, 1e-6)
        assertEquals(-10.0, summary.avgCurrentA, 1e-6)
        assertEquals(10.0, summary.avgMagnitudeCurrentA, 1e-6)
        assertEquals(72.0, summary.avgVoltageV, 1e-6)
        assertEquals(-720.0, summary.avgPowerW, 1e-6)
        assertEquals(720.0, summary.avgDischargePowerW, 1e-6)
        assertEquals(12.0, summary.energyDischargedWh, 1e-6)
        assertEquals(0.0, summary.energyRecoveredWh, 1e-6)
        assertEquals(-12.0, summary.netEnergyWh, 1e-6)
        assertEquals(10.0, summary.peakDischargeA, 1e-6)
        assertEquals(0.0, summary.peakRegenA, 1e-6)
        assertEquals(61, summary.sampleCount)
    }

    @Test
    fun regenBrakingIsCountedAsRecoveredEnergyNotAsConsumption() {
        val stats = RollingStats()
        // 20 s of 10 A discharge, then 5 s of 8 A regen braking.
        (0..20).forEach { stats.add(Samples.of(1000L + it * 1000L, -10.0, 72.0)) }
        (1..5).forEach { stats.add(Samples.of(21_000L + it * 1000L, 8.0, 74.0)) }

        val summary = stats.summary(26_000L)
        assertEquals(25.0, summary.windowSeconds, 1e-6)
        assertEquals(8.0, summary.peakRegenA, 1e-6)
        assertTrue(summary.energyRecoveredWh > 0.0)
        // Signed average is smaller in magnitude than the magnitude average precisely because the
        // regen minutes cancel part of the discharge minutes.
        assertTrue(summary.avgCurrentA > -10.0 / 25.0 * 20.0)
        assertEquals(summary.energyDischargedWh - summary.energyRecoveredWh, -summary.netEnergyWh, 1e-9)
    }

    @Test
    fun averagesWeightByTimeNotBySampleCount() {
        val stats = RollingStats(windowMs = 60_000L)
        stats.add(Samples.of(0L, -10.0, 70.0))
        stats.add(Samples.of(1000L, -10.0, 70.0))
        // A 10 s jump to a much higher voltage: the arithmetic mean of the three points is 73.33 V,
        // the time-weighted mean is 74.55 V. Only the second one is what the rider experienced.
        stats.add(Samples.of(11_000L, -10.0, 80.0))

        val summary = stats.summary(11_000L)
        assertEquals(11.0, summary.windowSeconds, 1e-6)
        assertEquals(74.545, summary.avgVoltageV, 0.001)
        assertEquals(80.0, summary.maxVoltageV, 1e-9)
        assertEquals(70.0, summary.minVoltageV, 1e-9)
    }

    @Test
    fun gapsLongerThanAMaxSegmentAreExcludedRatherThanInvented() {
        val stats = RollingStats(windowMs = 600_000L)
        stats.add(Samples.of(0L, -10.0, 72.0))
        // A 60 s link outage exceeds MAX_SEGMENT_SEC, so it must not be integrated at all.
        stats.add(Samples.of(60_000L, -10.0, 72.0))

        assertEquals(StatsSummary.EMPTY, stats.summary(60_000L))
    }

    @Test
    fun windowEvictsSamplesOlderThanFiveMinutes() {
        val stats = RollingStats()
        val start = 0L
        (0..599).forEach { stats.add(Samples.of(start + it * 1000L, -10.0, 72.0)) }

        val summary = stats.summary(599_000L)
        assertEquals(301, summary.sampleCount)
        assertEquals(300.0, summary.windowSeconds, 1e-6)
        assertEquals(60.0, summary.energyDischargedWh, 1e-6)
    }

    @Test
    fun resetClearsTheWindow() {
        val stats = RollingStats(windowMs = 60_000L)
        (0..10).forEach { stats.add(Samples.of(it * 1000L, -10.0, 72.0)) }
        stats.reset()

        assertEquals(0, stats.size)
        assertEquals(StatsSummary.EMPTY, stats.summary(10_000L))
    }
}
