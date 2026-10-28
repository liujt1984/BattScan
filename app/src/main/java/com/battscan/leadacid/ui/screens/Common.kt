package com.battscan.leadacid.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.battscan.leadacid.ble.LinkPhase
import com.battscan.leadacid.core.algo.ActiveAlarm
import com.battscan.leadacid.core.algo.AlarmKind
import com.battscan.leadacid.core.algo.AlarmSeverity
import com.battscan.leadacid.core.model.PowerDirection
import com.battscan.leadacid.core.protocol.FieldRole
import java.text.DateFormat
import java.util.Date
import java.util.Locale

val DischargeColor = Color(0xFFE8833A)
val RegenColor = Color(0xFF26A69A)
val IdleColor = Color(0xFF9AA0A6)
val CriticalColor = Color(0xFFD32F2F)
val WarnColor = Color(0xFFF57C00)
val InfoColor = Color(0xFF607D8B)

fun directionLabel(direction: PowerDirection): String = when {
    direction == PowerDirection.DISCHARGE -> "放电"
    direction == PowerDirection.CHARGE -> "回收充电"
    direction == PowerDirection.IDLE -> "待机"
    else -> "方向未知"
}

fun directionColor(direction: PowerDirection): Color = when {
    direction == PowerDirection.DISCHARGE -> DischargeColor
    direction == PowerDirection.CHARGE -> RegenColor
    direction == PowerDirection.IDLE -> IdleColor
    else -> InfoColor
}

fun phaseLabel(phase: LinkPhase): String = when {
    phase == LinkPhase.IDLE -> "空闲"
    phase == LinkPhase.SCANNING -> "搜索中"
    phase == LinkPhase.CONNECTING -> "连接中"
    phase == LinkPhase.DISCOVERING_SERVICES -> "发现服务"
    phase == LinkPhase.SUBSCRIBING -> "订阅中"
    phase == LinkPhase.POLLING -> "采集中"
    phase == LinkPhase.WAITING_RECONNECT -> "等待重连"
    phase == LinkPhase.STOPPED -> "已停止"
    else -> phase.name
}

fun roleLabel(role: FieldRole): String = when {
    role == FieldRole.CURRENT -> "电流"
    role == FieldRole.VOLTAGE -> "电压"
    role == FieldRole.TEMPERATURE -> "温度"
    role == FieldRole.SOC -> "设备SOC"
    else -> "未知"
}

fun roleColor(role: FieldRole): Color = when {
    role == FieldRole.CURRENT -> Color(0x66E8833A)
    role == FieldRole.VOLTAGE -> Color(0x663DDC84)
    role == FieldRole.TEMPERATURE -> Color(0x66FFC14D)
    role == FieldRole.SOC -> Color(0x6642A5F5)
    else -> Color(0x1F000000)
}

fun severityColor(severity: AlarmSeverity): Color = when {
    severity == AlarmSeverity.CRITICAL -> CriticalColor
    severity == AlarmSeverity.WARN -> WarnColor
    else -> InfoColor
}

fun alarmKindLabel(kind: AlarmKind): String = when {
    kind == AlarmKind.UNDER_VOLTAGE -> "欠压"
    kind == AlarmKind.OVER_VOLTAGE -> "过压"
    kind == AlarmKind.OVER_CURRENT -> "过流"
    kind == AlarmKind.OVER_TEMP -> "过温"
    kind == AlarmKind.LOW_SOC -> "低电量"
    kind == AlarmKind.SOC_DIVERGENCE -> "SOC分歧"
    kind == AlarmKind.MAPPING_SUSPECT -> "映射可疑"
    else -> "链路"
}

fun formatDateTime(ms: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.CHINA).format(Date(ms))

fun formatClockOnly(ms: Long): String =
    DateFormat.getTimeInstance(DateFormat.MEDIUM, Locale.CHINA).format(Date(ms))

fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val hours = totalSec / 3600L
    val minutes = (totalSec % 3600L) / 60L
    val seconds = totalSec % 60L
    return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, seconds) else "%02d:%02d".format(minutes, seconds)
}

@Composable
fun ScreenHeader(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (subtitle != null) {
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SectionHeader(title: String, note: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (note != null) {
            Text(text = note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun Panel(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (trailing != null) {
                    Text(text = trailing, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            content()
        }
    }
}

@Composable
fun MetricCard(
    label: String,
    value: String,
    unit: String = "",
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    big: Boolean = false,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = value,
                    color = valueColor,
                    fontWeight = FontWeight.Bold,
                    style = if (big) MaterialTheme.typography.displaySmall else MaterialTheme.typography.titleLarge,
                )
                if (unit.isNotEmpty()) {
                    Text(text = unit, style = MaterialTheme.typography.labelMedium, color = valueColor)
                }
            }
            Text(text = label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun StatRow(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, color = valueColor, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun ProgressBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(Color(0x22000000), RoundedCornerShape(3.dp)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(6.dp)
                .background(color, RoundedCornerShape(3.dp)),
        )
    }
}

@Composable
fun DirectionChip(direction: PowerDirection) {
    val color = directionColor(direction)
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.28f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text = directionLabel(direction), fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
fun WarningCard(text: String, modifier: Modifier = Modifier, tint: Color = WarnColor) {
    Card(modifier = modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = tint.copy(alpha = 0.16f))) {
        Text(
            text = text,
            modifier = Modifier.padding(10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = tint,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
fun AlarmBanner(alarms: List<ActiveAlarm>, onClear: () -> Unit) {
    if (alarms.isEmpty()) return
    val top = alarms.first().severity
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = severityColor(top).copy(alpha = 0.18f)),
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "告警 ${alarms.size} 条",
                    fontWeight = FontWeight.Bold,
                    color = severityColor(top),
                    style = MaterialTheme.typography.titleSmall,
                )
                TextButton(onClick = onClear) { Text("清除") }
            }
            alarms.forEach { alarm ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .background(severityColor(alarm.severity), RoundedCornerShape(2.dp)),
                    )
                    Text(
                        text = "${alarmKindLabel(alarm.kind)} · ${alarm.detail}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
fun EmptyState(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun LabeledSlider(
    label: String,
    valueText: String,
    fraction: Float,
    onFractionChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(text = valueText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
        Slider(value = fraction.coerceIn(0f, 1f), onValueChange = onFractionChange, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun LabeledSwitch(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    description: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun NumberField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    decimal: Boolean = true,
    onCommit: (String) -> Unit,
) {
    var text by remember { mutableStateOf(value) }
    // 只有当输入与外部规范值在数值上不一致时才回填，否则刚敲的 2 会被立刻改写成 2.0，没法接着输入小数点。
    LaunchedEffect(value) {
        if (text.toDoubleOrNull() != value.toDoubleOrNull()) text = value
    }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onCommit(it)
        },
        modifier = modifier.fillMaxWidth(),
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
    )
}

@Composable
fun <T : Any> ChoiceRow(
    label: String,
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        options.forEach { option ->
            if (option == selected) {
                FilledTonalButton(onClick = { onSelect(option) }) { Text(labelOf(option)) }
            } else {
                TextButton(onClick = { onSelect(option) }) { Text(labelOf(option)) }
            }
        }
    }
}
