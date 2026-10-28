package com.battscan.leadacid.core.algo

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Result of a windowed least-squares fit of terminal voltage against signed current.
 *
 * The app's convention is positive current flowing *into* the pack, so a healthy battery must come
 * back with a small *positive* slope: pushing current in lifts the terminal voltage above EMF and
 * drawing it out drops the voltage below EMF. [slopeOhm] is therefore the apparent pack resistance
 * when the mapping is right, and the single most diagnostic number in the app when it is not - a
 * value far above any plausible pack resistance means the current field is an unsigned magnitude
 * (charge and discharge frames merged into one axis) or the bytes behind "current" and "voltage"
 * are not what the preset claims.
 */
data class EmfFit(
    val slopeOhm: Double,
    val interceptV: Double,
    val correlation: Double,
    val sampleCount: Int,
    val currentSpreadA: Double,
) {
    val trustworthy: Boolean get() = sampleCount >= 12 && correlation.abs() >= 0.55 && currentSpreadA >= 1.5

    /** EMF implied at zero current. */
    val emfV: Double get() = interceptV

    fun verdict(): String = when {
        !trustworthy -> "样本不足或电流变化太小，无法判定"
        slopeOhm > SUSPICIOUS_SLOPE_OHM ->
            "dV/dI = %.2f V/A，远大于任何合理整组内阻：电流字段应为无符号幅值，或电压/电流字节配对有误".format(slopeOhm)
        slopeOhm >= 0.01 ->
            "dV/dI = %.3f Ω 为正且量级合理：符合『回充抬高、放电压低』的有符号电流，等效内阻约 %.3f Ω".format(slopeOhm, slopeOhm)
        slopeOhm > -0.05 -> "dV/dI 接近零：字段映射可疑，铅酸整组不应几乎没有内阻压降"
        else ->
            "dV/dI = %.3f Ω 为负：与『正电流=充电』的约定相反，请检查协议的电流符号模式".format(slopeOhm)
    }

    companion object {
        val EMPTY = EmfFit(0.0, 0.0, 0.0, 0, 0.0)
        const val SUSPICIOUS_SLOPE_OHM = 0.8
    }
}

/**
 * Sliding-window V/I regression. Kept allocation-light because it runs on every uplink frame at
 * roughly 1 Hz while the bike is moving.
 */
class EmfModel(private val windowSize: Int = 120) {

    private data class Point(val currentA: Double, val voltageV: Double, val monotonicMs: Long)

    private val points = ArrayDeque<Point>()

    fun add(currentA: Double, voltageV: Double, monotonicMs: Long) {
        if (voltageV <= 0.0) return
        points.addLast(Point(currentA, voltageV, monotonicMs))
        while (points.size > windowSize) points.removeFirst()
    }

    fun reset() = points.clear()

    fun fit(): EmfFit {
        val n = points.size
        if (n < 4) return EmfFit.EMPTY
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        var minI = Double.MAX_VALUE; var maxI = -Double.MAX_VALUE
        points.forEach { p ->
            sx += p.currentA; sy += p.voltageV
            sxx += p.currentA * p.currentA; syy += p.voltageV * p.voltageV
            sxy += p.currentA * p.voltageV
            if (p.currentA < minI) minI = p.currentA
            if (p.currentA > maxI) maxI = p.currentA
        }
        val denom = n * sxx - sx * sx
        if (abs(denom) < 1e-9) return EmfFit(0.0, sy / n, 0.0, n, 0.0)
        val slope = (n * sxy - sx * sy) / denom
        val intercept = (sy - slope * sx) / n
        val rDenom = sqrt((n * sxx - sx * sx) * (n * syy - sy * sy))
        val r = if (rDenom < 1e-9) 0.0 else (n * sxy - sx * sy) / rDenom
        return EmfFit(slope, intercept, r, n, maxI - minI)
    }

    /**
     * Best resistance to use for the EMF correction. An explicit operator value wins, because the
     * regression cannot separate charge frames from discharge frames when the sign is inferred.
     *
     * A learned slope is only accepted inside a physically plausible band: the 2026-10-28 capture
     * fits to +1.79 V/A, and letting that become the pack resistance would push the charge/discharge
     * prototypes 18 V apart and corrupt every direction decision downstream.
     */
    fun resistanceFor(explicitOhm: Double?): Double {
        explicitOhm?.let { return it }
        val fit = fit()
        if (!fit.trustworthy) return DEFAULT_RESISTANCE_OHM
        val magnitude = abs(fit.slopeOhm)
        return if (magnitude in LEARNABLE_MIN_OHM..EmfFit.SUSPICIOUS_SLOPE_OHM) magnitude else DEFAULT_RESISTANCE_OHM
    }

    /**
     * Turns a terminal voltage into an EMF estimate, then into per-cell voltage for the OCV table.
     */
    fun cellEmfVoltage(voltageV: Double, currentA: Double, cells: Int, resistanceOhm: Double): Double {
        if (cells <= 0) return voltageV
        return (voltageV - currentA * resistanceOhm) / cells
    }

    /**
     * Estimates how long a window has been quiescent, which is what gates OCV anchoring: a lead-acid
     * pack needs several minutes at near-zero current before terminal voltage means open circuit.
     */
    fun quietSeconds(deadbandA: Double, nowMs: Long): Double {
        var lastLoud = -1L
        points.forEach { p -> if (abs(p.currentA) >= deadbandA) lastLoud = maxOf(lastLoud, p.monotonicMs) }
        if (lastLoud < 0) return (nowMs - (points.firstOrNull()?.monotonicMs ?: nowMs)) / 1000.0
        return ((nowMs - lastLoud) / 1000.0).coerceAtLeast(0.0)
    }

    companion object {
        const val DEFAULT_RESISTANCE_OHM = 0.25

        /** Below this, the current swing is too small for the fit to mean anything. */
        const val LEARNABLE_MIN_OHM = 0.02
    }
}
