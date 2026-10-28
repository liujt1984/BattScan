package com.battscan.leadacid.core.algo

import com.battscan.leadacid.core.model.PowerDirection
import kotlin.math.abs
import kotlin.math.max

/**
 * Splits an unsigned current magnitude into charge and discharge.
 *
 * The WS-BLE frame carries no direction bit, so the only usable signal is the voltage itself: a
 * pack being drawn from sits below its EMF, a pack being pushed by regen sits above it. Two
 * prototypes are therefore maintained (EMF minus the I-R drop, EMF plus the I-R rise) and each frame
 * is assigned to whichever it lies nearer, while the EMF estimate tracks the chosen interpretation.
 *
 * This is an estimator, not a measurement. It only earns trust when current actually varies, and it
 * must be verified against a known-工况 capture before the resulting SOC figure is relied on.
 */
class DirectionInferer(
    private val emfTimeConstantSec: Double = 90.0,
    private val minMargin: Double = 0.12,
) {
    data class Inferred(
        val currentA: Double,
        val direction: PowerDirection,
        val confidence: Double,
        val emfV: Double,
    )

    private var emfV: Double? = null
    private var lastMonotonicMs: Long? = null
    private var lastDirection = PowerDirection.UNKNOWN

    fun reset() {
        emfV = null
        lastMonotonicMs = null
        lastDirection = PowerDirection.UNKNOWN
    }

    fun infer(
        magnitudeA: Double,
        voltageV: Double,
        resistanceOhm: Double,
        deadbandA: Double,
        monotonicMs: Long,
    ): Inferred {
        val dt = lastMonotonicMs?.let { ((monotonicMs - it) / 1000.0).coerceIn(0.0, MAX_STEP_SEC) } ?: 0.0
        lastMonotonicMs = monotonicMs

        if (magnitudeA < deadbandA) {
            // Nearly open circuit: the terminal voltage is the best EMF sample available.
            val tracked = emfV ?: voltageV
            emfV = advance(tracked, voltageV, dt)
            lastDirection = PowerDirection.IDLE
            return Inferred(0.0, lastDirection, 1.0, emfV ?: voltageV)
        }

        // Seeded as a discharge, because a ride is mostly discharge; seeding as a charge would make
        // the very first frame self-confirm the wrong interpretation.
        val tracked = emfV ?: (voltageV + magnitudeA * resistanceOhm)

        val errDischarge = abs(voltageV - (tracked - magnitudeA * resistanceOhm))
        val errCharge = abs(voltageV - (tracked + magnitudeA * resistanceOhm))
        val spread = max(errDischarge, errCharge)
        val margin = if (spread < 1e-6) 0.0 else abs(errDischarge - errCharge) / spread

        val direction = when {
            margin >= minMargin && errCharge < errDischarge -> PowerDirection.CHARGE
            margin >= minMargin -> PowerDirection.DISCHARGE
            lastDirection == PowerDirection.CHARGE -> PowerDirection.CHARGE
            else -> PowerDirection.DISCHARGE
        }
        lastDirection = direction

        val impliedEmf = if (direction == PowerDirection.CHARGE) {
            voltageV - magnitudeA * resistanceOhm
        } else {
            voltageV + magnitudeA * resistanceOhm
        }
        emfV = advance(tracked, impliedEmf, dt)

        val signed = if (direction == PowerDirection.CHARGE) magnitudeA else -magnitudeA
        return Inferred(signed, direction, margin.coerceIn(0.0, 1.0), emfV ?: tracked)
    }

    private fun advance(current: Double, target: Double, dtSec: Double): Double {
        if (dtSec <= 0.0) return current
        val alpha = dtSec / (emfTimeConstantSec + dtSec)
        return current + alpha * (target - current)
    }

    companion object {
        const val MAX_STEP_SEC = 20.0
    }
}
