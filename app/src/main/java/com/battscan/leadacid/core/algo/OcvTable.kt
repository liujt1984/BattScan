package com.battscan.leadacid.core.algo

import com.battscan.leadacid.core.model.Chemistry
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Open-circuit voltage per series cell mapped to state of charge.
 *
 * Values are resting-OCV, i.e. what a well-behaved pack shows after minutes without current, so
 * they must only be applied to an EMF estimate rather than a raw terminal voltage under load.
 */
data class OcvTable(val name: String, val points: List<Pair<Double, Double>>) {

    private val sorted: List<Pair<Double, Double>> = points.sortedBy { it.first }

    fun socFor(cellVoltageV: Double): Double {
        if (cellVoltageV <= sorted.first().first) return sorted.first().second
        if (cellVoltageV >= sorted.last().first) return sorted.last().second
        for (i in 1 until sorted.size) {
            val (x1, y1) = sorted[i - 1]
            val (x2, y2) = sorted[i]
            if (cellVoltageV <= x2) {
                val f = (cellVoltageV - x1) / (x2 - x1)
                return y1 + f * (y2 - y1)
            }
        }
        return sorted.last().second
    }

    fun voltageFor(soc: Double): Double {
        val target = soc.coerceIn(0.0, 1.0)
        for (i in 1 until sorted.size) {
            val (x1, y1) = sorted[i - 1]
            val (x2, y2) = sorted[i]
            if (target <= y2) {
                val span = y2 - y1
                if (span == 0.0) return x2
                val f = (target - y1) / span
                return x1 + f * (x2 - x1)
            }
        }
        return sorted.last().first
    }

    companion object {
        /** Classic antimony/AGM 2 V cell curve, the shape used by e-bike 6-DZ blocks. */
        val LEAD_ACID_2V = OcvTable(
            "铅酸 2V 单格",
            listOf(
                1.910 to 0.00, 1.930 to 0.10, 1.950 to 0.20, 1.970 to 0.30,
                1.990 to 0.40, 2.010 to 0.50, 2.030 to 0.60, 2.050 to 0.70,
                2.072 to 0.80, 2.096 to 0.90, 2.125 to 1.00,
            ),
        )

        val LI_ION_3P7 = OcvTable(
            "三元锂 3.7V 标称",
            listOf(
                3.00 to 0.00, 3.30 to 0.02, 3.50 to 0.08, 3.60 to 0.15, 3.70 to 0.30,
                3.80 to 0.50, 3.85 to 0.65, 3.95 to 0.80, 4.05 to 0.90, 4.20 to 1.00,
            ),
        )

        val LI_FE_PO4_3P2 = OcvTable(
            "磷酸铁锂 3.2V 标称",
            listOf(
                2.50 to 0.00, 3.00 to 0.02, 3.10 to 0.05, 3.15 to 0.10, 3.20 to 0.25,
                3.25 to 0.50, 3.30 to 0.70, 3.33 to 0.85, 3.35 to 0.95, 3.40 to 0.99, 3.65 to 1.00,
            ),
        )

        fun forChemistry(chemistry: Chemistry): OcvTable = when (chemistry) {
            Chemistry.LEAD_ACID -> LEAD_ACID_2V
            Chemistry.LI_ION -> LI_ION_3P7
        }
    }
}

/**
 * Peukert and temperature derating of usable amp-hours.
 *
 * Lead-acid capacity is strongly current-dependent, which is exactly the error that makes a naive
 * coulomb counter drift on an e-bike pulling 10-30 A from a pack labelled at the 2 h rate.
 */
object CapacityModel {

    fun peukertFactor(ratedAh: Double, ratedAtHours: Double, exponent: Double, currentA: Double): Double {
        if (currentA <= 0.05 || ratedAh <= 0.0 || ratedAtHours <= 0.0) return 1.0
        val ratedCurrent = ratedAh / ratedAtHours
        val ratio = ratedCurrent / max(currentA, 0.05)
        return min(ratio.pow(exponent - 1.0), 4.0)
    }

    fun temperatureFactor(tempC: Double?, referenceC: Double, coefficientPerC: Double): Double {
        val t = tempC ?: return 1.0
        return (1.0 + coefficientPerC * (t - referenceC)).coerceIn(0.35, 1.25)
    }

    fun effectiveCapacityAh(
        config: com.battscan.leadacid.core.model.BatteryConfig,
        currentA: Double,
        tempC: Double?,
    ): Double {
        val p = peukertFactor(config.ratedCapacityAh, config.ratedAtHours, config.peukertExponent, currentA)
        val f = temperatureFactor(tempC, config.referenceTempC, config.tempCoefficientPerC)
        return (config.ratedCapacityAh * p * f).coerceAtLeast(0.5)
    }
}
