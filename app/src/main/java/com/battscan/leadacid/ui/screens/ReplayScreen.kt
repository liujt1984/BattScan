package com.battscan.leadacid.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.battscan.leadacid.core.model.PowerDirection
import com.battscan.leadacid.data.db.SampleEntity
import com.battscan.leadacid.data.db.SessionDetail
import com.battscan.leadacid.monitor.ChartPoint
import com.battscan.leadacid.ui.MonitorViewModel
import com.battscan.leadacid.ui.components.BatterySparkline
import kotlin.math.abs

private const val PAGE_SIZE = 40

@Composable
fun ReplayScreen(vm: MonitorViewModel, sessionId: Long, onBack: () -> Unit) {
    LaunchedEffect(sessionId) { vm.loadDetail(sessionId) }

    val detail by vm.detail.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    var page by remember(sessionId) { mutableStateOf(0) }
    var note by remember(detail?.session?.id) { mutableStateOf(detail?.session?.note ?: "") }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ScreenHeader("会话回放", if (sessionId == 0L) "缺少会话编号" else "会话 #$sessionId")
            OutlinedButton(onClick = onBack) { Text("返回") }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val data = detail
            if (data == null) {
                EmptyState(if (ui.busy) "正在读取会话采样明细…" else "找不到会话 #$sessionId，可能已被删除。")
            } else if (data.samples.isEmpty()) {
                EmptyState(
                    "这段会话没有采样明细：采集时 记录采样 可能被关闭，或链路在写入第一批数据前就中断了。",
                )
            } else {
                SessionMetaCard(data)
                SummaryCard(data.samples)
                ChartCard(data.samples, page, PAGE_SIZE, onPageChange = { page = it })
                TableCard(data.samples, page, PAGE_SIZE)
                NoteCard(
                    value = note,
                    onValueChange = { note = it },
                    onSave = { vm.setSessionNote(sessionId, note) },
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = { vm.export(sessionId) }, modifier = Modifier.weight(1f)) { Text("导出CSV") }
            OutlinedButton(onClick = { vm.loadDetail(sessionId) }, modifier = Modifier.weight(1f)) { Text("重新载入") }
        }
    }
}

@Composable
private fun SessionMetaCard(data: SessionDetail) {
    val session = data.session
    val elapsedMs = (session.endedAtMs ?: System.currentTimeMillis()) - session.startedAtMs
    Panel(title = "会话信息", trailing = formatDateTime(session.startedAtMs)) {
        StatRow(label = "时长", value = formatDuration(elapsedMs))
        StatRow(label = "协议", value = session.protocolId)
        StatRow(label = "设备", value = session.deviceAddress ?: "未记录")
        StatRow(label = "SOC", value = "%.1f%% → %.1f%%".format(session.startSoc, session.endSoc))
        StatRow(label = "放电 / 回收", value = "%.2f Ah / %.2f Ah".format(session.dischargeAh, session.recoveredAh))
        StatRow(label = "入库帧数", value = "${data.samples.size}")
    }
}

private class ReplaySummary(
    val maxVoltageV: Double,
    val minVoltageV: Double,
    val meanVoltageV: Double,
    val peakDischargeA: Double,
    val peakRegenA: Double,
    val dischargeWh: Double,
    val recoveredWh: Double,
    val windowSec: Double,
)

/** Recomputed from the stored rows so a replay can disagree with the totals written while riding. */
private fun summarize(samples: List<SampleEntity>): ReplaySummary {
    var maxV = samples.first().voltageV
    var minV = maxV
    var sumV = 0.0
    var peakDischarge = 0.0
    var peakRegen = 0.0
    var dischargeWh = 0.0
    var recoveredWh = 0.0
    var previous: SampleEntity? = null
    samples.forEach { sample ->
        if (sample.voltageV > maxV) maxV = sample.voltageV
        if (sample.voltageV < minV) minV = sample.voltageV
        sumV += sample.voltageV
        if (sample.currentA < 0.0) peakDischarge = maxOf(peakDischarge, abs(sample.currentA))
        if (sample.currentA > 0.0) peakRegen = maxOf(peakRegen, sample.currentA)
        val prev = previous
        previous = sample
        if (prev == null) return@forEach
        val dtSec = ((sample.elapsedMs - prev.elapsedMs) / 1000.0).coerceIn(0.0, 20.0)
        if (dtSec <= 0.0) return@forEach
        val powerW = (prev.powerW + sample.powerW) / 2.0
        val wh = powerW * dtSec / 3600.0
        if (wh < 0.0) dischargeWh += -wh else recoveredWh += wh
    }
    val spanSec = ((samples.last().elapsedMs - samples.first().elapsedMs) / 1000.0).coerceAtLeast(0.0)
    return ReplaySummary(
        maxVoltageV = maxV,
        minVoltageV = minV,
        meanVoltageV = sumV / samples.size,
        peakDischargeA = peakDischarge,
        peakRegenA = peakRegen,
        dischargeWh = dischargeWh,
        recoveredWh = recoveredWh,
        windowSec = spanSec,
    )
}

@Composable
private fun SummaryCard(samples: List<SampleEntity>) {
    val summary = summarize(samples)
    Panel(title = "本地重算汇总", trailing = "%.0f s".format(summary.windowSec)) {
        StatRow(label = "最高电压", value = "%.2f V".format(summary.maxVoltageV))
        StatRow(label = "最低电压", value = "%.2f V".format(summary.minVoltageV))
        StatRow(label = "平均电压", value = "%.2f V".format(summary.meanVoltageV))
        StatRow(label = "峰值放电电流", value = "%.2f A".format(summary.peakDischargeA), valueColor = DischargeColor)
        StatRow(label = "峰值回收电流", value = "%.2f A".format(summary.peakRegenA), valueColor = RegenColor)
        StatRow(label = "放电能量", value = "%.2f Wh".format(summary.dischargeWh))
        StatRow(label = "回收能量", value = "%.2f Wh".format(summary.recoveredWh))
        StatRow(label = "净消耗", value = "%.2f Wh".format(summary.dischargeWh - summary.recoveredWh))
    }
}

private fun SampleEntity.toChartPoint() = ChartPoint(
    elapsedMs = elapsedMs,
    voltageV = voltageV,
    currentA = currentA,
    powerW = powerW,
    socPercent = socPercent,
)

private fun pageCount(total: Int, pageSize: Int): Int = if (total <= 0) 1 else (total + pageSize - 1) / pageSize

@Composable
private fun ChartCard(
    samples: List<SampleEntity>,
    page: Int,
    pageSize: Int,
    onPageChange: (Int) -> Unit,
) {
    val pages = pageCount(samples.size, pageSize)
    val current = page.coerceIn(0, pages - 1)
    val window = samples.drop(current * pageSize).take(pageSize)
    Panel(title = "曲线", trailing = "第 ${current + 1} / $pages 段") {
        Text(
            text = "整段会话（点迹被压缩，用于看趋势）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BatterySparkline(points = samples.map { it.toChartPoint() }, modifier = Modifier.fillMaxWidth())
        Text(
            text = "本段放大（%s ~ %s）".format(
                formatDuration(window.first().elapsedMs),
                formatDuration(window.last().elapsedMs),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BatterySparkline(points = window.map { it.toChartPoint() }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onPageChange((current - 1).coerceAtLeast(0)) }, modifier = Modifier.weight(1f)) {
                Text("上一段")
            }
            OutlinedButton(onClick = { onPageChange((current + 1).coerceAtMost(pages - 1)) }, modifier = Modifier.weight(1f)) {
                Text("下一段")
            }
        }
    }
}

@Composable
private fun TableCard(samples: List<SampleEntity>, page: Int, pageSize: Int) {
    val pages = pageCount(samples.size, pageSize)
    val current = page.coerceIn(0, pages - 1)
    val rows = samples.drop(current * pageSize).take(pageSize)
    Panel(title = "采样明细", trailing = "第 ${current + 1} / $pages 页，每页 $pageSize 行") {
        Text(
            text = "相对时刻      电压V    电流A    功率W    SOC%   方向",
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
        )
        rows.forEach { row ->
            Text(
                text = "%10s  %7.2f  %+7.2f  %7.0f  %5.1f  %s".format(
                    formatDuration(row.elapsedMs),
                    row.voltageV,
                    row.currentA,
                    row.powerW,
                    row.socPercent,
                    directionFromName(row.direction),
                ),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = if (row.currentA > 0.0) RegenColor else Color.Unspecified,
            )
        }
        Text(
            text = "电流为正表示回收制动方向；该列取自入库时的推断结果，若协议字段有误会成批反向，" +
                "请对照 标定 页的 dV/dI 判断。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun directionFromName(name: String): String =
    PowerDirection.entries.firstOrNull { it.name == name }?.let { directionLabel(it) } ?: name

@Composable
private fun NoteCard(
    value: String,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    Panel(title = "备注") {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("路况、气温、换电池等", style = MaterialTheme.typography.labelMedium) },
            minLines = 2,
            maxLines = 4,
        )
        Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) { Text("保存备注") }
    }
}
