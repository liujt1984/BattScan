package com.battscan.leadacid.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.battscan.leadacid.ble.LinkStatus
import com.battscan.leadacid.core.algo.EmfFit
import com.battscan.leadacid.core.algo.EmfModel
import com.battscan.leadacid.core.algo.RangeEstimate
import com.battscan.leadacid.core.algo.StatsSummary
import com.battscan.leadacid.core.model.BatteryConfig
import com.battscan.leadacid.core.model.BatterySample
import com.battscan.leadacid.monitor.ChartPoint
import com.battscan.leadacid.monitor.MonitorSnapshot
import com.battscan.leadacid.monitor.SessionTotals
import com.battscan.leadacid.ui.MonitorViewModel
import com.battscan.leadacid.ui.components.BatterySparkline
import com.battscan.leadacid.ui.components.voltageBounds
import kotlin.math.abs

@Composable
fun DashboardScreen(
    vm: MonitorViewModel,
    onOpenSettings: () -> Unit,
    onOpenRaw: () -> Unit,
    onOpenCalibration: () -> Unit,
    onOpenSessions: () -> Unit,
) {
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val latest = snapshot.latest

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ScreenHeader("骑行监控", "72V 铅酸 · WS-BLE 库仑计")
            LinkStrip(snapshot.link)
            if (snapshot.alarms.isNotEmpty()) {
                AlarmBanner(alarms = snapshot.alarms, onClear = { vm.clearAlarms() })
            }
            MappingWarning(snapshot.fit)
            if (latest == null) {
                EmptyState(
                    "暂无采样数据。请打开 设置 → 设备，扫描并选中 WS-BLE 模块；" +
                        "链路状态进入“采集中”后，这里会实时显示电压、电流与 SOC。",
                )
            } else {
                CoreReadings(snapshot, latest)
                DirectionCard(snapshot.directionConfidence, latest)
                RecentFiveMinutes(snapshot.recent5Min)
                TrendCard(snapshot.chart)
                RangeCard(snapshot.range, snapshot.battery)
                SessionCard(snapshot.sessionTotals)
            }
        }
        DashboardNavRow(
            onOpenSettings = onOpenSettings,
            onOpenRaw = onOpenRaw,
            onOpenCalibration = onOpenCalibration,
            onOpenSessions = onOpenSessions,
        )
    }
}

@Composable
private fun LinkStrip(link: LinkStatus) {
    Panel(title = "链路", trailing = phaseLabel(link.phase)) {
        StatRow(
            label = "设备",
            value = link.deviceName ?: link.deviceAddress ?: "未选择设备",
        )
        if (link.detail.isNotEmpty()) StatRow(label = "状态说明", value = link.detail)
        StatRow(label = "轮询 / 收帧", value = "${link.pollsSent} / ${link.framesReceived}")
        StatRow(label = "超时 / 重连", value = "${link.pollTimeouts} / ${link.reconnects}")
        StatRow(
            label = "应答成功率",
            value = "%.0f%%".format(link.successRate * 100.0),
            valueColor = if (link.successRate < 0.8 && link.pollsSent > 5) WarnColor else MaterialTheme.colorScheme.onSurface,
        )
        StatRow(label = "RSSI", value = link.rssi?.let { "%d dBm".format(it) } ?: "未读取")
    }
}

@Composable
private fun MappingWarning(fit: EmfFit) {
    // The same band EmfModel uses to decide whether a learned slope is believable: a negative
    // verdict means the sign convention is wrong, so it must not render as the green row.
    if (fit.trustworthy && fit.slopeOhm in EmfModel.LEARNABLE_MIN_OHM..EmfFit.SUSPICIOUS_SLOPE_OHM) {
        StatRow(label = "字段映射自检", value = fit.verdict(), valueColor = RegenColor)
    } else {
        WarningCard(text = "协议字段映射未证实：${fit.verdict()}")
    }
}

@Composable
private fun CoreReadings(snapshot: MonitorSnapshot, latest: BatterySample) {
    val battery = snapshot.battery
    val blockCount = battery.blockCount.coerceAtLeast(1)
    val socText = snapshot.soc?.let { "%.1f".format(it.socPercent) } ?: "--"
    Panel(title = "实时读数", trailing = formatClockOnly(latest.wallClockMs)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard(
                label = "SOC 剩余电量",
                value = socText,
                unit = "%",
                modifier = Modifier.weight(1f),
                valueColor = socColor(snapshot),
                big = true,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard(
                    label = "瞬时功率",
                    value = "%.0f".format(latest.powerW),
                    unit = "W",
                    valueColor = directionColor(latest.direction),
                )
                MetricCard(
                    label = "电流（带符号，正=回收）",
                    value = "%+.2f".format(latest.currentA),
                    unit = "A",
                    valueColor = directionColor(latest.direction),
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard(
                label = "总电压",
                value = "%.2f".format(latest.voltageV),
                unit = "V",
                modifier = Modifier.weight(1f),
            )
            MetricCard(
                label = "每块12V（总电压/${blockCount}块）",
                value = "%.2f".format(latest.voltageV / blockCount),
                unit = "V",
                modifier = Modifier.weight(1f),
            )
        }
        StatRow(label = "单体2V（告警阈值口径）", value = "%.3f V".format(battery.cellVoltage(latest.voltageV)))
        StatRow(label = "帧内原始电流字段", value = "%.2f A".format(latest.rawCurrentA))
        latest.maxTemperatureC?.let { StatRow(label = "温度", value = "%.1f °C".format(it)) }
        latest.deviceSocPercent?.let { StatRow(label = "设备自报SOC", value = "%.1f %%".format(it)) }
        snapshot.soc?.let { state ->
            StatRow(label = "电压推算SOC", value = "%.1f %%（锚定权重 %.2f）".format(state.ocvPercent, state.anchorWeight))
            if (state.fullDetected) StatRow(label = "满充判定", value = "已触发", valueColor = RegenColor)
        }
    }
}

private fun socColor(snapshot: MonitorSnapshot): Color {
    val percent = snapshot.soc?.socPercent
    return when {
        percent == null -> IdleColor
        percent <= 20.0 -> CriticalColor
        percent <= 40.0 -> WarnColor
        else -> RegenColor
    }
}

@Composable
private fun DirectionCard(confidence: Double, latest: BatterySample) {
    Panel(title = "电流方向") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            DirectionChip(latest.direction)
            Text(
                text = "幅值 %.2f A".format(abs(latest.currentA)),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        StatRow(label = "方向置信度", value = "%.0f%%".format(confidence * 100.0))
        ProgressBar(fraction = confidence.toFloat(), color = directionColor(latest.direction))
        Text(
            text = "WS-BLE 的电流字段是无符号幅值，方向靠电压与推算 EMF 的比较得出；" +
                "松油门回收与加速交替时置信度会下降，SOC 曲线可能短暂偏差。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RecentFiveMinutes(stats: StatsSummary) {
    Panel(title = "最近 5 分钟", trailing = "${stats.sampleCount} 帧 / %.0f s".format(stats.windowSeconds)) {
        if (stats.sampleCount < 2) {
            Text(
                text = "窗口内样本不足，所有平均值需要至少两帧才能按时间加权计算。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Panel
        }
        StatRow(label = "平均电压", value = "%.2f V".format(stats.avgVoltageV))
        StatRow(label = "平均净电流", value = "%+.2f A".format(stats.avgCurrentA), valueColor = if (stats.avgCurrentA > 0) RegenColor else DischargeColor)
        StatRow(label = "平均电流幅值", value = "%.2f A".format(stats.avgMagnitudeCurrentA))
        StatRow(label = "平均功耗（净）", value = "%+.1f W".format(stats.avgPowerW))
        StatRow(label = "平均放电功率", value = "%.1f W".format(stats.avgDischargePowerW))
        StatRow(label = "回收电量", value = "%.2f Wh".format(stats.energyRecoveredWh), valueColor = RegenColor)
        StatRow(label = "放电电量", value = "%.2f Wh".format(stats.energyDischargedWh), valueColor = DischargeColor)
        StatRow(label = "峰值放电电流", value = "%.2f A".format(stats.peakDischargeA))
        StatRow(label = "峰值回收电流", value = "%.2f A".format(stats.peakRegenA))
        StatRow(label = "窗口电压区间", value = "%.2f ~ %.2f V".format(stats.minVoltageV, stats.maxVoltageV))
    }
}

@Composable
private fun TrendCard(points: List<ChartPoint>) {
    Panel(title = "电压 / 电流曲线", trailing = "最近 ${points.size} 点") {
        BatterySparkline(points = points, modifier = Modifier.fillMaxWidth())
        if (points.size >= 2) {
            val bounds = voltageBounds(points)
            StatRow(label = "电压轴", value = "%.2f ~ %.2f V".format(bounds.first, bounds.second))
        }
        Text(
            text = "绿=总电压，橙=带符号电流；两条线各自独立缩放，零线为电流正负分界。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RangeCard(range: RangeEstimate?, battery: BatteryConfig) {
    Panel(title = "续航估算") {
        if (range == null) {
            Text(
                text = "等待 SOC 估算结果。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Panel
        }
        StatRow(label = "剩余能量", value = "%.0f Wh".format(range.remainingWh))
        StatRow(label = "预计还可用", value = range.minutesText())
        StatRow(label = "预计还能跑", value = range.kmText())
        StatRow(label = "能耗口径", value = "%.1f Wh/km".format(range.consumptionWhPerKm))
        if (!range.basedOnMeasuredPower) {
            val hint = "提示：窗口内没有可信的平均放电功率，距离按设置的 %.1f Wh/km 假设折算；可用时间只在有实测放电功率时才有意义。"
            Text(
                text = hint.format(battery.avgConsumptionWhPerKm),
                style = MaterialTheme.typography.bodySmall,
                color = WarnColor,
            )
        }
    }
}

@Composable
private fun SessionCard(totals: SessionTotals) {
    Panel(title = "本次会话", trailing = if (totals.sessionId == 0L) "未开始" else "#${totals.sessionId}") {
        StatRow(label = "时长", value = formatDuration(totals.elapsedMs))
        StatRow(label = "采样帧数", value = "${totals.sampleCount}")
        StatRow(label = "放电容量", value = "%.2f Ah".format(totals.dischargeAh))
        StatRow(label = "回收容量", value = "%.2f Ah".format(totals.recoveredAh))
        StatRow(label = "放电能量", value = "%.1f Wh".format(totals.dischargeWh))
        StatRow(label = "回收能量", value = "%.1f Wh".format(totals.recoveredWh))
        StatRow(label = "最低电压", value = totals.minVoltageText)
        StatRow(label = "峰值电流幅值", value = "%.2f A".format(totals.maxCurrentA))
    }
}

@Composable
private fun DashboardNavRow(
    onOpenSettings: () -> Unit,
    onOpenRaw: () -> Unit,
    onOpenCalibration: () -> Unit,
    onOpenSessions: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = onOpenSettings, modifier = Modifier.weight(1f)) { Text("设置") }
        OutlinedButton(onClick = onOpenCalibration, modifier = Modifier.weight(1f)) { Text("标定") }
        OutlinedButton(onClick = onOpenRaw, modifier = Modifier.weight(1f)) { Text("原始帧") }
        OutlinedButton(onClick = onOpenSessions, modifier = Modifier.weight(1f)) { Text("记录") }
    }
}
