package com.battscan.leadacid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.battscan.leadacid.core.model.BatterySample
import com.battscan.leadacid.core.protocol.FieldReading
import com.battscan.leadacid.core.protocol.FieldRole
import com.battscan.leadacid.core.protocol.FieldSpec
import com.battscan.leadacid.core.protocol.ProtocolSpec
import com.battscan.leadacid.monitor.FrameLine
import com.battscan.leadacid.ui.MonitorViewModel
import kotlinx.coroutines.launch

private val TxColor = Color(0xFF64B5F6)
private val RxColor = Color(0xFF3DDC84)

@Composable
fun RawDebugScreen(vm: MonitorViewModel) {
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()
    // 清空只作用于显示层：引擎的帧缓冲不动，所以清空后新帧继续出现。
    var clearedAfterMs by remember { mutableStateOf(0L) }
    val lines = snapshot.frameLog.filter { it.wallClockMs > clearedAfterMs }

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.size + 3)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp),
        ) {
            ScreenHeader(
                title = "原始帧调试",
                subtitle = "TX 为下行轮询命令，RX 为上行数据帧；把字段范围叠在字节上就能核对配对。",
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 8.dp),
        ) {
            item {
                HexGridPanel(hex = snapshot.latest?.frameHex.orEmpty(), spec = snapshot.protocol)
            }
            item {
                DecodedPanel(latest = snapshot.latest, readings = snapshot.latestReadings)
            }
            item {
                TransportLogPanel(logs = snapshot.transportLog)
            }
            item {
                Text(text = "帧日志（${lines.size} 行，最新在底部）", style = MaterialTheme.typography.titleSmall)
            }
            items(lines) { line ->
                FrameLineRow(line)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { clearedAfterMs = System.currentTimeMillis() },
                modifier = Modifier.weight(1f),
            ) { Text("清空显示") }
            Button(
                onClick = {
                    val dump = logDump(snapshot.transportLog, lines)
                    scope.launch { clipboard.setText(AnnotatedString(dump)) }
                },
                modifier = Modifier.weight(1f),
            ) { Text("复制全部日志") }
        }
    }
}

private fun logDump(transportLog: List<String>, lines: List<FrameLine>): String = buildString {
    transportLog.forEach { append(it).append('\n') }
    append('\n')
    lines.forEach { line ->
        append(formatClockOnly(line.wallClockMs)).append(' ')
        append(if (line.outbound) "TX " else "RX ").append(line.hex)
        if (line.note.isNotBlank()) append("  ").append(line.note)
        append('\n')
    }
}

@Composable
private fun FrameLineRow(line: FrameLine) {
    val color = if (line.outbound) TxColor else RxColor
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = formatClockOnly(line.wallClockMs),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = if (line.outbound) "TX" else "RX",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = color,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = line.hex,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = color,
        )
    }
}

@Composable
private fun TransportLogPanel(logs: List<String>) {
    Panel(title = "链路日志", trailing = "${logs.size} 行") {
        if (logs.isEmpty()) {
            Text(text = "暂无日志。", style = MaterialTheme.typography.bodySmall)
        } else {
            logs.takeLast(40).forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DecodedPanel(latest: BatterySample?, readings: List<FieldReading>) {
    Panel(title = "最近帧解析", trailing = if (readings.isEmpty()) null else "${readings.size} 字段") {
        if (latest == null) {
            Text(
                text = "还没有解码成功的数据帧。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Panel
        }
        StatRow(label = "电流字段（判向后）", value = "%+.2f A".format(latest.currentA))
        StatRow(label = "方向", value = directionLabel(latest.direction))
        StatRow(label = "功率", value = "%.0f W".format(latest.powerW))
        latest.deviceSocPercent?.let { StatRow(label = "设备自报SOC", value = "%.1f %%".format(it)) }
        // 原始整数是标定用的真正线索：拿它除以观测到的物理量就能反推 scale。
        readings.forEach { reading ->
            StatRow(
                label = "${roleLabel(reading.role)} ${reading.key} · ${reading.label}",
                value = "%d → %.2f %s".format(reading.rawLong, reading.value, reading.unit.ifEmpty { "-" }),
            )
        }
        if (readings.isEmpty()) {
            Text(
                text = "本帧没有成功解码出字段，检查协议描述的偏移与帧长。",
                style = MaterialTheme.typography.bodySmall,
                color = WarnColor,
            )
        }
    }
}

@Composable
private fun HexGridPanel(hex: String, spec: ProtocolSpec?) {
    val bytes = hex.split(" ").mapNotNull { it.toIntOrNull(16) }
    val fields = spec?.fields.orEmpty()
    Panel(title = "字节栅格", trailing = if (spec == null) "无协议描述" else "帧长 ${spec.frameLength}") {
        if (bytes.isEmpty()) {
            Text(
                text = "暂无上行帧：先在 设置 里选中设备，等链路进入采集中。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Panel
        }
        RoleLegend(fields)
        bytes.chunked(8).forEachIndexed { rowIndex, rowBytes ->
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "%02d".format(rowIndex * 8),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                rowBytes.forEachIndexed { column, value ->
                    val index = rowIndex * 8 + column
                    ByteCell(value = value, role = roleCovering(fields, index))
                }
            }
        }
        if (fields.isEmpty()) {
            Text(
                text = "没有协议描述，无法标注字段范围；请到 标定 页应用一个预设。",
                style = MaterialTheme.typography.bodySmall,
                color = WarnColor,
            )
        } else {
            fields.forEach { field ->
                Text(
                    text = "${roleLabel(field.role)} ${field.key} [${field.offset}..${field.offset + field.length - 1}] " +
                        "×${field.scale} ${field.unit.ifEmpty { "-" }} ${if (field.signed) "有符号" else "无符号"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun roleCovering(fields: List<FieldSpec>, index: Int): FieldRole? =
    fields.firstOrNull { index in it.offset until (it.offset + it.length) }?.role

@Composable
private fun RoleLegend(fields: List<FieldSpec>) {
    if (fields.isEmpty()) return
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        fields.map { it.role }.distinct().forEach { role ->
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(10.dp).background(roleColor(role), RoundedCornerShape(2.dp)))
                Text(text = roleLabel(role), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun ByteCell(value: Int, role: FieldRole?) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .background(role?.let { roleColor(it) } ?: Color(0x0F000000), RoundedCornerShape(3.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "%02X".format(value),
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}
