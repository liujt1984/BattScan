package com.battscan.leadacid.core

import com.battscan.leadacid.core.model.BatterySample
import com.battscan.leadacid.core.model.NamedReading
import com.battscan.leadacid.core.model.PowerDirection
import com.battscan.leadacid.core.model.TemperatureReading
import kotlin.math.abs

/**
 * Sample factory for the algorithm tests. Sign convention throughout the app: positive current
 * flows *into* the pack, so discharge is negative.
 */
object Samples {
    fun of(
        monotonicMs: Long,
        currentA: Double,
        voltageV: Double,
        temperatureC: Double? = null,
        direction: PowerDirection = if (abs(currentA) < 0.8) PowerDirection.IDLE
        else if (currentA > 0) PowerDirection.CHARGE else PowerDirection.DISCHARGE,
        wallClockMs: Long = 1_760_000_000_000L + monotonicMs,
    ): BatterySample = BatterySample(
        wallClockMs = wallClockMs,
        monotonicMs = monotonicMs,
        rawCurrentA = abs(currentA),
        currentA = currentA,
        voltageV = voltageV,
        direction = direction,
        temperatures = temperatureC?.let { listOf(TemperatureReading("b10", "b10 温度?", it)) } ?: emptyList(),
        deviceSocPercent = null,
        unknowns = temperatureC?.let { listOf(NamedReading("b08", "b8 缓降", it, "?")) } ?: emptyList(),
        frameHex = "TEST",
    )

    /** A steady discharge leg: current drawn, terminal voltage sagged below EMF. */
    fun dischargeSeries(
        startMs: Long,
        count: Int,
        stepMs: Long,
        currentA: Double,
        emfV: Double,
        resistanceOhm: Double,
    ): List<BatterySample> = (0 until count).map { index ->
        val at = startMs + index * stepMs
        of(at, -abs(currentA), emfV - abs(currentA) * resistanceOhm)
    }
}
