package com.battscan.leadacid.core.algo

import com.battscan.leadacid.core.model.BatteryConfig
import com.battscan.leadacid.core.model.BatterySample
import com.battscan.leadacid.core.model.PowerDirection
import kotlin.math.abs
import kotlin.math.max

/**
 * Everything the dashboard and the log need to know about the state-of-charge calculation, kept
 * together because a bare percentage is not auditable.
 */
data class SocState(
    val socPercent: Double,
    val ocvPercent: Double,
    val anchorWeight: Double,
    val estimatedEmfV: Double,
    val perCellEmfV: Double,
    val remainingAh: Double,
    val remainingWh: Double,
    val dischargedAh: Double,
    val recoveredAh: Double,
    val effectiveCapacityAh: Double,
    val quietSeconds: Double,
    val anchored: Boolean,
    val fullDetected: Boolean,
) {
    val lowVoltageRisk: Boolean get() = socPercent <= 10.0
}

/**
 * Bidirectional coulomb counting with open-circuit-voltage anchoring.
 *
 * Three properties matter for this specific pack and are why a naive counter fails:
 *  1. Regen braking pushes current *back*, so the integrator must be signed or every brake
 *     application is counted as extra consumption.
 *  2. Lead-acid usable amp-hours collapse at high discharge current, hence Peukert derating on the
 *     divisor rather than on the measured current.
 *  3. Terminal voltage under a 10 A load says almost nothing about SOC, so OCV only earns weight
 *     after the pack has been quiet, and even then it nudges rather than overwrites.
 */
class SocEstimator(
    private val ocvTable: OcvTable,
    private val emfModel: EmfModel,
    initialSoc: Double = 0.5,
) {
    private var integrator = initialSoc.coerceIn(0.0, 1.0)
    private var dischargedAh = 0.0
    private var recoveredAh = 0.0
    private var lastMonotonicMs: Long? = null
    private var fullDetected = false

    var state: SocState = idleState(initialSoc)
        private set

    fun reset(soc: Double) {
        integrator = soc.coerceIn(0.0, 1.0)
        dischargedAh = 0.0
        recoveredAh = 0.0
        lastMonotonicMs = null
        fullDetected = false
        state = idleState(integrator)
    }

    /** Sets the integrator directly; backs the "手动标定 SOC" action on the settings screen. */
    fun forceSet(soc: Double) {
        integrator = soc.coerceIn(0.0, 1.0)
    }

    fun update(sample: BatterySample, config: BatteryConfig, resistanceOhm: Double): SocState {
        val previous = lastMonotonicMs
        val dtSec = previous?.let { (sample.monotonicMs - it) / 1000.0 }?.coerceIn(0.0, MAX_STEP_SEC) ?: 0.0
        lastMonotonicMs = sample.monotonicMs

        val currentA = sample.currentA
        val effectiveCapacity = CapacityModel.effectiveCapacityAh(config, abs(currentA), sample.maxTemperatureC)

        if (dtSec > 0.0) {
            var deltaAh = currentA * dtSec / 3600.0
            if (deltaAh > 0.0) {
                deltaAh *= config.chargeEfficiency
                recoveredAh += deltaAh
            } else {
                dischargedAh += -deltaAh
            }
            integrator = (integrator + deltaAh / effectiveCapacity).coerceIn(0.0, 1.0)
        }

        val cells = config.seriesCellCount
        val perCellEmf = emfModel.cellEmfVoltage(sample.voltageV, currentA, cells, resistanceOhm)
        val estimatedEmf = perCellEmf * cells
        val socFromOcv = ocvTable.socFor(perCellEmf)
        val quiet = emfModel.quietSeconds(config.idleDeadbandA, sample.monotonicMs)
        val weight = anchorWeight(abs(currentA), config, quiet)

        if (weight > 0.0 && dtSec > 0.0) {
            val pull = (dtSec / ANCHOR_TAU_SEC) * weight * (socFromOcv - integrator)
            integrator = (integrator + pull).coerceIn(0.0, 1.0)
        }

        detectFullCharge(sample, config, perCellEmf)

        val remainingAh = max(integrator, 0.0) * effectiveCapacity
        state = SocState(
            socPercent = integrator * 100.0,
            ocvPercent = socFromOcv * 100.0,
            anchorWeight = weight,
            estimatedEmfV = estimatedEmf,
            perCellEmfV = perCellEmf,
            remainingAh = remainingAh,
            remainingWh = remainingAh * sample.voltageV,
            dischargedAh = dischargedAh,
            recoveredAh = recoveredAh,
            effectiveCapacityAh = effectiveCapacity,
            quietSeconds = quiet,
            anchored = weight >= MIN_REPORTABLE_ANCHOR,
            fullDetected = fullDetected,
        )
        return state
    }

    private fun anchorWeight(magnitudeA: Double, config: BatteryConfig, quietSeconds: Double): Double {
        val loadNorm = magnitudeA / max(config.ratedCapacityAh * 0.5, 1.0)
        val loadDamping = 1.0 / (1.0 + loadNorm * loadNorm)
        val restBonus = when {
            quietSeconds > 300.0 -> 1.0
            quietSeconds > 120.0 -> 0.7
            quietSeconds > 45.0 -> 0.4
            else -> 0.15
        }
        return (MAX_ANCHOR_WEIGHT * loadDamping * restBonus).coerceIn(0.0, MAX_ANCHOR_WEIGHT)
    }

    private fun detectFullCharge(sample: BatterySample, config: BatteryConfig, perCellEmf: Double) {
        if (sample.direction != PowerDirection.CHARGE) return
        if (abs(sample.currentA) > config.ratedCapacityAh * TAPER_FRACTION) return
        if (perCellEmf >= ocvTable.voltageFor(0.99)) {
            integrator = 1.0
            fullDetected = true
        }
    }

    private fun idleState(soc: Double) = SocState(
        socPercent = soc * 100.0,
        ocvPercent = soc * 100.0,
        anchorWeight = 0.0,
        estimatedEmfV = 0.0,
        perCellEmfV = 0.0,
        remainingAh = 0.0,
        remainingWh = 0.0,
        dischargedAh = 0.0,
        recoveredAh = 0.0,
        effectiveCapacityAh = 0.0,
        quietSeconds = 0.0,
        anchored = false,
        fullDetected = false,
    )

    companion object {
        const val MAX_STEP_SEC = 15.0
        const val ANCHOR_TAU_SEC = 240.0
        const val MAX_ANCHOR_WEIGHT = 0.55
        const val MIN_REPORTABLE_ANCHOR = 0.05
        const val TAPER_FRACTION = 0.05
    }
}
