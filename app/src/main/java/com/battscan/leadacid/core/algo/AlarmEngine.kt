package com.battscan.leadacid.core.algo

import com.battscan.leadacid.core.model.BatteryConfig
import com.battscan.leadacid.core.model.BatterySample
import kotlin.math.abs

enum class AlarmSeverity { INFO, WARN, CRITICAL }

enum class AlarmKind {
    UNDER_VOLTAGE,
    OVER_VOLTAGE,
    OVER_CURRENT,
    OVER_TEMP,
    LOW_SOC,
    SOC_DIVERGENCE,
    MAPPING_SUSPECT,
    LINK_DEAD,
}

data class ActiveAlarm(
    val kind: AlarmKind,
    val severity: AlarmSeverity,
    val detail: String,
    val value: Double,
    val sinceMonotonicMs: Long,
)

private data class RuleHit(val severity: AlarmSeverity, val detail: String, val value: Double)

/**
 * Threshold monitor with hysteresis and debounce.
 *
 * Debounce matters more than usual here: the poll interval is around 1.2 s and an e-bike sagging
 * under a hard acceleration dips below the per-cell cutoff for one frame and recovers. Raising on a
 * single frame would make the alarm useless, so a condition must persist before it lights up and
 * must clear past a hysteresis band before it goes out.
 */
class AlarmEngine(
    private val debounceMs: Long = 2500L,
    private val hysteresisCellV: Double = 0.05,
    private val hysteresisA: Double = 1.5,
    private val hysteresisTempC: Double = 3.0,
) {
    private val pending = mutableMapOf<AlarmKind, Long>()
    private val active = mutableMapOf<AlarmKind, ActiveAlarm>()

    fun evaluate(
        sample: BatterySample,
        config: BatteryConfig,
        soc: SocState,
        fit: EmfFit?,
        linkDeadMs: Long,
    ): List<ActiveAlarm> {
        val now = sample.monotonicMs
        val hits = LinkedHashMap<AlarmKind, RuleHit>()
        val cells = config.seriesCellCount
        val perCell = if (cells > 0) sample.voltageV / cells else sample.voltageV

        when {
            perCell <= config.cellVoltageCutoffLow - hysteresisCellV -> hits[AlarmKind.UNDER_VOLTAGE] =
                RuleHit(AlarmSeverity.CRITICAL, "单体 %.3f V 已低于截止 %.2f V".format(perCell, config.cellVoltageCutoffLow), perCell)
            perCell <= config.cellVoltageWarnLow - hysteresisCellV -> hits[AlarmKind.UNDER_VOLTAGE] =
                RuleHit(AlarmSeverity.WARN, "单体 %.3f V 偏低".format(perCell), perCell)
        }
        if (perCell >= config.cellVoltageCutoffHigh + hysteresisCellV) {
            hits[AlarmKind.OVER_VOLTAGE] = RuleHit(
                AlarmSeverity.CRITICAL,
                "单体 %.3f V 过压".format(perCell),
                perCell,
            )
        }
        val magnitude = abs(sample.currentA)
        if (magnitude >= config.cutoffCurrentA + hysteresisA) {
            hits[AlarmKind.OVER_CURRENT] = RuleHit(AlarmSeverity.CRITICAL, "电流 %.1f A 超限".format(magnitude), magnitude)
        } else if (magnitude >= config.warnCurrentA + hysteresisA) {
            hits[AlarmKind.OVER_CURRENT] = RuleHit(AlarmSeverity.WARN, "电流 %.1f A 偏大".format(magnitude), magnitude)
        }
        sample.maxTemperatureC?.let { t ->
            if (t >= config.cutoffTempC + hysteresisTempC) {
                hits[AlarmKind.OVER_TEMP] = RuleHit(AlarmSeverity.CRITICAL, "温度 %.1f °C".format(t), t)
            } else if (t >= config.warnTempC + hysteresisTempC) {
                hits[AlarmKind.OVER_TEMP] = RuleHit(AlarmSeverity.WARN, "温度 %.1f °C 偏高".format(t), t)
            }
        }
        if (soc.socPercent <= 10.0) {
            hits[AlarmKind.LOW_SOC] = RuleHit(AlarmSeverity.CRITICAL, "剩余电量 %.1f%%".format(soc.socPercent), soc.socPercent)
        } else if (soc.socPercent <= 20.0) {
            hits[AlarmKind.LOW_SOC] = RuleHit(AlarmSeverity.WARN, "剩余电量 %.1f%%".format(soc.socPercent), soc.socPercent)
        }
        if (soc.anchored && abs(soc.socPercent - soc.ocvPercent) >= DIVERGENCE_PCT) {
            hits[AlarmKind.SOC_DIVERGENCE] = RuleHit(
                AlarmSeverity.INFO,
                "积分 %.1f%% 与电压推算 %.1f%% 分差 %.1f%%，建议静置后重标容量/内阻"
                    .format(soc.socPercent, soc.ocvPercent, abs(soc.socPercent - soc.ocvPercent)),
                abs(soc.socPercent - soc.ocvPercent),
            )
        }
        fit?.takeIf { it.trustworthy && it.slopeOhm > EmfFit.SUSPICIOUS_SLOPE_OHM }?.let {
            hits[AlarmKind.MAPPING_SUSPECT] = RuleHit(
                AlarmSeverity.INFO,
                "dV/dI = %.2f V/A 远超合理内阻：电流字段可能无符号或字节配对有误".format(it.slopeOhm),
                it.slopeOhm,
            )
        }
        if (linkDeadMs >= LINK_DEAD_MS) {
            hits[AlarmKind.LINK_DEAD] = RuleHit(AlarmSeverity.WARN, "无数据 %.1f s".format(linkDeadMs / 1000.0), linkDeadMs / 1000.0)
        }

        hits.forEach { (kind, hit) ->
            val first = pending.getOrPut(kind) { now }
            if (now - first >= debounceMs) {
                val existing = active[kind]
                active[kind] = ActiveAlarm(
                    kind = kind,
                    severity = hit.severity,
                    detail = hit.detail,
                    value = hit.value,
                    sinceMonotonicMs = existing?.sinceMonotonicMs ?: first,
                )
            }
        }
        pending.keys.filter { it !in hits }.forEach { pending.remove(it) }
        active.keys.filter { it !in hits }.forEach { active.remove(it) }
        return active.values.sortedWith(compareBy({ -it.severity.ordinal }, { it.kind.name }))
    }

    fun clear(kind: AlarmKind) {
        active.remove(kind)
        pending.remove(kind)
    }

    fun reset() {
        active.clear()
        pending.clear()
    }

    companion object {
        const val DIVERGENCE_PCT = 25.0
        const val LINK_DEAD_MS = 8000L
    }
}

/**
 * Converts stored energy into "how far can I still go".
 *
 * The bike gives us no wheel-speed signal over this BLE link, so distance rests on a configured
 * average consumption; the time-to-empty figure is the trustworthy one because it comes straight
 * from the measured recent power draw.
 */
class RangeEstimator {

    fun estimate(soc: SocState, recent: StatsSummary, config: BatteryConfig): RangeEstimate {
        val remainingWh = soc.remainingWh
        val avgDischargeW = if (recent.avgDischargePowerW > 5.0) recent.avgDischargePowerW else 0.0
        val minutes = if (avgDischargeW > 0.0) remainingWh / avgDischargeW * 60.0 else Double.NaN
        val km = if (config.avgConsumptionWhPerKm > 0.0) remainingWh / config.avgConsumptionWhPerKm else Double.NaN
        val measuredConsumption = if (recent.windowSeconds > 60.0 && km.isFinite()) {
            remainingWh / km
        } else {
            config.avgConsumptionWhPerKm
        }
        return RangeEstimate(
            remainingWh = remainingWh,
            minutesToEnd = minutes,
            kilometresToEnd = km,
            consumptionWhPerKm = measuredConsumption,
            basedOnMeasuredPower = avgDischargeW > 0.0,
        )
    }
}

data class RangeEstimate(
    val remainingWh: Double,
    val minutesToEnd: Double,
    val kilometresToEnd: Double,
    val consumptionWhPerKm: Double,
    val basedOnMeasuredPower: Boolean,
) {
    fun minutesText(): String = if (minutesToEnd.isFinite()) "%.0f 分钟".format(minutesToEnd) else "计算中"

    fun kmText(): String = if (kilometresToEnd.isFinite()) "%.1f km".format(kilometresToEnd) else "计算中"
}
