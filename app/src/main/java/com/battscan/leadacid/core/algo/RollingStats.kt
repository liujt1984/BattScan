package com.battscan.leadacid.core.algo

import com.battscan.leadacid.core.model.BatterySample
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Time-weighted averages over a rolling window. The default window is the five minutes the
 * dashboard asks for, and every mean here is integrated over real elapsed time rather than a plain
 * arithmetic mean, because poll intervals drift with link quality.
 */
class RollingStats(private val windowMs: Long = DEFAULT_WINDOW_MS) {

    private data class Entry(
        val monotonicMs: Long,
        val voltageV: Double,
        val currentA: Double,
        val magnitudeA: Double,
    )

    private val entries = ArrayDeque<Entry>()

    fun add(sample: BatterySample) {
        entries.addLast(
            Entry(
                monotonicMs = sample.monotonicMs,
                voltageV = sample.voltageV,
                currentA = sample.currentA,
                magnitudeA = abs(sample.currentA),
            ),
        )
        evict(sample.monotonicMs)
    }

    private fun evict(nowMs: Long) {
        val cutoff = nowMs - windowMs
        while (entries.isNotEmpty() && entries.first().monotonicMs < cutoff) entries.removeFirst()
    }

    fun reset() = entries.clear()

    val size: Int get() = entries.size

    fun summary(nowMs: Long): StatsSummary {
        evict(nowMs)
        if (entries.size < 2) return StatsSummary.EMPTY
        var seconds = 0.0
        var voltSec = 0.0
        var signedCurrentSec = 0.0
        var magnitudeSec = 0.0
        var signedPowerSec = 0.0
        var dischargeWh = 0.0
        var recoverWh = 0.0
        var minV = Double.MAX_VALUE
        var maxV = -Double.MAX_VALUE
        var peakDischargeA = 0.0
        var peakRegenA = 0.0

        var prev: Entry? = null
        for (e in entries) {
            val a = prev
            prev = e
            if (a == null) {
                minV = min(minV, e.voltageV)
                maxV = max(maxV, e.voltageV)
                continue
            }
            val dt = (e.monotonicMs - a.monotonicMs) / 1000.0
            if (dt <= 0.0 || dt > MAX_SEGMENT_SEC) continue
            val v = (a.voltageV + e.voltageV) / 2.0
            val c = (a.currentA + e.currentA) / 2.0
            val m = (a.magnitudeA + e.magnitudeA) / 2.0
            seconds += dt
            voltSec += v * dt
            signedCurrentSec += c * dt
            magnitudeSec += m * dt
            val p = v * c
            signedPowerSec += p * dt
            if (p < 0.0) dischargeWh += -p * dt / 3600.0 else recoverWh += p * dt / 3600.0
            minV = min(minV, e.voltageV)
            maxV = max(maxV, e.voltageV)
            if (c < 0.0) peakDischargeA = max(peakDischargeA, -c)
            if (c > 0.0) peakRegenA = max(peakRegenA, c)
        }
        if (seconds <= 0.0) return StatsSummary.EMPTY
        return StatsSummary(
            windowSeconds = seconds,
            avgVoltageV = voltSec / seconds,
            avgCurrentA = signedCurrentSec / seconds,
            avgMagnitudeCurrentA = magnitudeSec / seconds,
            avgPowerW = signedPowerSec / seconds,
            avgDischargePowerW = dischargeWh * 3600.0 / seconds,
            energyDischargedWh = dischargeWh,
            energyRecoveredWh = recoverWh,
            minVoltageV = if (minV == Double.MAX_VALUE) 0.0 else minV,
            maxVoltageV = if (maxV == -Double.MAX_VALUE) 0.0 else maxV,
            peakDischargeA = peakDischargeA,
            peakRegenA = peakRegenA,
            sampleCount = entries.size,
        )
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 5 * 60 * 1000L
        const val MAX_SEGMENT_SEC = 20.0
    }
}

data class StatsSummary(
    val windowSeconds: Double,
    val avgVoltageV: Double,
    val avgCurrentA: Double,
    val avgMagnitudeCurrentA: Double,
    val avgPowerW: Double,
    val avgDischargePowerW: Double,
    val energyDischargedWh: Double,
    val energyRecoveredWh: Double,
    val minVoltageV: Double,
    val maxVoltageV: Double,
    val peakDischargeA: Double,
    val peakRegenA: Double,
    val sampleCount: Int,
) {
    val netEnergyWh: Double get() = energyRecoveredWh - energyDischargedWh

    companion object {
        val EMPTY = StatsSummary(
            windowSeconds = 0.0,
            avgVoltageV = 0.0,
            avgCurrentA = 0.0,
            avgMagnitudeCurrentA = 0.0,
            avgPowerW = 0.0,
            avgDischargePowerW = 0.0,
            energyDischargedWh = 0.0,
            energyRecoveredWh = 0.0,
            minVoltageV = 0.0,
            maxVoltageV = 0.0,
            peakDischargeA = 0.0,
            peakRegenA = 0.0,
            sampleCount = 0,
        )
    }
}
