package com.battscan.leadacid.core.model

enum class Chemistry(val displayLabel: String) {
    /** 6 x 12 V blocks = 36 x 2 V cells, the e-bike pack this app is tuned for. */
    LEAD_ACID("72V 铅酸"),

    LI_ION("72V 锂电"),
}

/**
 * Physical description of the pack plus the thresholds the alarm engine uses.
 *
 * [blockCount] counts 12 V blocks for lead-acid (six internal 2 V cells each) and counts cells
 * directly for li-ion. Getting it wrong scales every per-cell voltage and the OCV anchor.
 */
data class BatteryConfig(
    val chemistry: Chemistry = Chemistry.LEAD_ACID,
    val blockCount: Int = 6,
    val ratedCapacityAh: Double = 20.0,
    /** Rate (hours) the label capacity was measured at; e-bike lead-acid is usually the 2 h rate. */
    val ratedAtHours: Double = 2.0,
    val peukertExponent: Double = 1.15,
    /** Ah put back per Ah removed; lead-acid is lossy, especially near full. */
    val chargeEfficiency: Double = 0.85,
    /** Pack ohmic resistance. Null means "learn it from the V/I regression". */
    val internalResistanceOhm: Double? = null,
    val tempCoefficientPerC: Double = 0.004,
    val referenceTempC: Double = 25.0,
    val nominalVoltageV: Double = 72.0,

    val cellVoltageWarnLow: Double = 1.90,
    val cellVoltageCutoffLow: Double = 1.75,
    val cellVoltageWarnHigh: Double = 2.40,
    val cellVoltageCutoffHigh: Double = 2.55,

    val warnCurrentA: Double = 30.0,
    val cutoffCurrentA: Double = 45.0,
    val warnTempC: Double = 55.0,
    val cutoffTempC: Double = 65.0,
    val idleDeadbandA: Double = 0.8,
    /** Average consumption the range estimate falls back on when no route distance exists. */
    val avgConsumptionWhPerKm: Double = 18.0,
) {
    /** Series cells the pack actually contains, which is what all per-cell math uses. */
    val seriesCellCount: Int
        get() = if (chemistry == Chemistry.LEAD_ACID) blockCount * 6 else blockCount

    fun cellVoltage(v: Double): Double = v / seriesCellCount.coerceAtLeast(1)

    fun encode(): String = buildString {
        line("chemistry", chemistry.name)
        line("blocks", blockCount.toString())
        line("capacity", ratedCapacityAh.toString())
        line("ratedHours", ratedAtHours.toString())
        line("peukert", peukertExponent.toString())
        line("efficiency", chargeEfficiency.toString())
        line("resistance", internalResistanceOhm?.toString() ?: "")
        line("tempCoef", tempCoefficientPerC.toString())
        line("refTemp", referenceTempC.toString())
        line("nominalV", nominalVoltageV.toString())
        line("warnLowCell", cellVoltageWarnLow.toString())
        line("cutoffLowCell", cellVoltageCutoffLow.toString())
        line("warnHighCell", cellVoltageWarnHigh.toString())
        line("cutoffHighCell", cellVoltageCutoffHigh.toString())
        line("warnCurrent", warnCurrentA.toString())
        line("cutoffCurrent", cutoffCurrentA.toString())
        line("warnTemp", warnTempC.toString())
        line("cutoffTemp", cutoffTempC.toString())
        line("deadband", idleDeadbandA.toString())
        line("whPerKm", avgConsumptionWhPerKm.toString())
    }

    private fun StringBuilder.line(key: String, value: String) {
        append(key).append('=').append(value).append('\n')
    }

    companion object {
        fun decode(text: String?): BatteryConfig {
            if (text.isNullOrBlank()) return BatteryConfig()
            val map = text.lines().mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) null else line.substring(0, idx) to line.substring(idx + 1)
            }.toMap()
            val base = BatteryConfig()
            fun dbl(key: String, fallback: Double) = map[key]?.toDoubleOrNull() ?: fallback
            fun int(key: String, fallback: Int) = map[key]?.toIntOrNull() ?: fallback
            return base.copy(
                chemistry = Chemistry.entries.firstOrNull { it.name == map["chemistry"] } ?: base.chemistry,
                blockCount = int("blocks", base.blockCount),
                ratedCapacityAh = dbl("capacity", base.ratedCapacityAh),
                ratedAtHours = dbl("ratedHours", base.ratedAtHours),
                peukertExponent = dbl("peukert", base.peukertExponent),
                chargeEfficiency = dbl("efficiency", base.chargeEfficiency),
                internalResistanceOhm = map["resistance"]?.toDoubleOrNull(),
                tempCoefficientPerC = dbl("tempCoef", base.tempCoefficientPerC),
                referenceTempC = dbl("refTemp", base.referenceTempC),
                nominalVoltageV = dbl("nominalV", base.nominalVoltageV),
                cellVoltageWarnLow = dbl("warnLowCell", base.cellVoltageWarnLow),
                cellVoltageCutoffLow = dbl("cutoffLowCell", base.cellVoltageCutoffLow),
                cellVoltageWarnHigh = dbl("warnHighCell", base.cellVoltageWarnHigh),
                cellVoltageCutoffHigh = dbl("cutoffHighCell", base.cellVoltageCutoffHigh),
                warnCurrentA = dbl("warnCurrent", base.warnCurrentA),
                cutoffCurrentA = dbl("cutoffCurrent", base.cutoffCurrentA),
                warnTempC = dbl("warnTemp", base.warnTempC),
                cutoffTempC = dbl("cutoffTemp", base.cutoffTempC),
                idleDeadbandA = dbl("deadband", base.idleDeadbandA),
                avgConsumptionWhPerKm = dbl("whPerKm", base.avgConsumptionWhPerKm),
            )
        }
    }
}
