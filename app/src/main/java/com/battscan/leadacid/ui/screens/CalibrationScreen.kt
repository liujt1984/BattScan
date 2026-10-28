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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.battscan.leadacid.core.algo.EmfFit
import com.battscan.leadacid.core.algo.EmfModel
import com.battscan.leadacid.core.protocol.CurrentSignMode
import com.battscan.leadacid.core.protocol.Endianness
import com.battscan.leadacid.core.protocol.FieldRole
import com.battscan.leadacid.core.protocol.FieldSpec
import com.battscan.leadacid.core.protocol.ProtocolSpec
import com.battscan.leadacid.core.protocol.WsBlePresets
import com.battscan.leadacid.monitor.MonitorSnapshot
import com.battscan.leadacid.ui.MonitorViewModel

@Composable
fun CalibrationScreen(vm: MonitorViewModel) {
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val protocol = snapshot.protocol

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ScreenHeader("协议标定", "字段映射未经实骑证实；这一页要用 dV/dI 与未知字段的实时变化把它证明。")
        FitPanel(snapshot.fit)
        if (protocol == null) {
            EmptyState("设置里还没有可用的协议描述（协议文本解析失败），先用下方预设恢复一份再编辑。")
        } else {
            FrameShapePanel(protocol)
            RoleSummaryPanel(protocol)
            EmfSignPanel(snapshot, protocol) { vm.setProtocol(it) }
            UnknownFieldsPanel(snapshot)
            DecodeHealthPanel(snapshot)
            FieldListPanel(protocol) { vm.setProtocol(it) }
            PresetPanel(protocol) { vm.setProtocol(it) }
        }
        SpecTextPanel(protocol) { vm.setProtocolText(it) }
    }
}

@Composable
private fun FitPanel(fit: EmfFit) {
    Panel(title = "V/I 拟合诊断", trailing = if (fit.trustworthy) "样本可用" else "样本不足") {
        StatRow(
            label = "dV/dI（拟合斜率）",
            value = "%+.3f Ω".format(fit.slopeOhm),
            valueColor = if (fit.slopeOhm in EmfModel.LEARNABLE_MIN_OHM..EmfFit.SUSPICIOUS_SLOPE_OHM) RegenColor else CriticalColor,
        )
        StatRow(label = "推算 EMF（截距）", value = "%.2f V".format(fit.interceptV))
        StatRow(label = "相关系数 r", value = "%.3f".format(fit.correlation))
        StatRow(label = "拟合样本数", value = "${fit.sampleCount}")
        StatRow(label = "电流跨度", value = "%.2f A".format(fit.currentSpreadA))
        Text(text = fit.verdict(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(
            text = ("本 App 全链路约定“正电流 = 流入电池（充电为正）”，所以端电压随电流增大而升高，" +
                "健康的映射应当拟合出一个小的正斜率，它就是内阻 +R，合理区间 %.2f~%.2f Ω。负斜率说明电流符号取反了；" +
                "斜率为正但远大于 %.1f Ω（抓包拟合出 +1.79 Ω）也不是内阻，而是电流字段按无符号幅值上报，" +
                "把放电与回收混进了同一个数，或电压与电流的字节配对搞错了。" +
                "必须用一次已知工况的实骑采集把它重新证明，SOC 与续航数字才可信。")
                .format(EmfModel.LEARNABLE_MIN_OHM, EmfFit.SUSPICIOUS_SLOPE_OHM, EmfFit.SUSPICIOUS_SLOPE_OHM),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FrameShapePanel(spec: ProtocolSpec) {
    Panel(title = "帧结构", trailing = "${spec.frameLength} 字节") {
        StatRow(label = "编号 / 名称", value = "${spec.id} · ${spec.displayName}")
        StatRow(label = "magic", value = if (spec.magic.isEmpty()) "未定义" else spec.magic.joinToString(" ") { "${it.first}=%02X".format(it.second) })
        StatRow(label = "校验", value = "${spec.checksumMode.name} @ ${spec.checksumOffset?.toString() ?: "-"}")
        StatRow(label = "轮询命令", value = spec.pollCommand.joinToString(" ") { "%02X".format(it) }.ifEmpty { "未定义" })
        StatRow(label = "会话起始命令", value = spec.sessionStartCommand.joinToString(" ") { "%02X".format(it) }.ifEmpty { "无" })
        val over = spec.highestByteRead > spec.frameLength
        StatRow(
            label = "字段读取最高字节", value = "${spec.highestByteRead} / ${spec.frameLength}",
            valueColor = if (over) CriticalColor else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun RoleSummaryPanel(spec: ProtocolSpec) {
    Panel(title = "当前角色分配") {
        FieldRole.entries.filter { it != FieldRole.UNKNOWN }.forEach { role ->
            val keys = spec.fields.filter { it.role == role }.map { it.key }
            StatRow(
                label = "${roleLabel(role)}（$role）",
                value = keys.joinToString("、").ifEmpty { "未分配" },
                valueColor = if (keys.isEmpty() && role != FieldRole.SOC) WarnColor else MaterialTheme.colorScheme.onSurface,
            )
        }
        val unknownKeys = spec.fields.filter { it.role == FieldRole.UNKNOWN }.map { it.key }
        StatRow(label = "未知（UNKNOWN）", value = unknownKeys.joinToString("、").ifEmpty { "无" })
        if (spec.fieldByRole(FieldRole.CURRENT) == null || spec.fieldByRole(FieldRole.VOLTAGE) == null) {
            WarningCard(text = "缺少电流或电压角色，引擎不会产出任何采样数据。", tint = CriticalColor)
        }
    }
}

@Composable
private fun EmfSignPanel(snapshot: MonitorSnapshot, spec: ProtocolSpec, onApply: (ProtocolSpec) -> Unit) {
    Panel(title = "EMF 与电流符号模式") {
        StatRow(label = "本帧推算 EMF（方向推断用）", value = "%.2f V".format(snapshot.estimatedEmfV))
        StatRow(label = "拟合 EMF（零电流截距）", value = "%.2f V".format(snapshot.fit.interceptV))
        val soc = snapshot.soc
        if (soc != null) {
            StatRow(label = "单体 EMF", value = "%.3f V/2V格".format(soc.perCellEmfV))
            StatRow(label = "静置时长", value = "%.0f s（OCV 锚定权重 %.2f）".format(soc.quietSeconds, soc.anchorWeight))
        }
        ChoiceRow(
            label = "电流字段",
            options = CurrentSignMode.entries,
            selected = spec.currentSignMode,
            labelOf = { if (it == CurrentSignMode.SIGNED) "有符号" else "无符号幅值" },
            onSelect = { onApply(spec.copy(currentSignMode = it)) },
        )
        Text(
            text = "无符号幅值：引擎按电压相对推算 EMF 的高低判向（回收为正）。有符号：直接采用电流字段的符号，" +
                "此时必须把下方 CURRENT 字段的“有符号”一起打开，否则负半轴会被读成大正数。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UnknownFieldsPanel(snapshot: MonitorSnapshot) {
    Panel(title = "未知字段实时值", trailing = "刹车 / 加速时看哪一行跟着动") {
        val unknowns = snapshot.unknownFields
        if (unknowns.isEmpty()) {
            Text(
                text = "当前帧没有 UNKNOWN 角色字段可读，或还没有数据。把可疑字段的角色改成 UNKNOWN 就能在这里持续观察。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            unknowns.forEach { reading ->
                StatRow(
                    label = "${reading.key} · ${reading.label}",
                    value = "%.3f %s".format(reading.value, reading.unit.ifEmpty { "-" }),
                )
            }
        }
        snapshot.latest?.let { latest ->
            Text(
                text = "判据：加速时上升、松油门回收时下降的是电流幅值；只在回收时变正的可能是方向位或充电标志；" +
                    "全程缓慢单调下降的多为容量/累计安时；恒定值属于保留字段。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StatRow(label = "本帧原始电流字段", value = "%.2f A".format(latest.rawCurrentA))
            StatRow(label = "本帧电压字段", value = "%.2f V".format(latest.voltageV))
        }
    }
}

@Composable
private fun DecodeHealthPanel(snapshot: MonitorSnapshot) {
    Panel(title = "解码健康") {
        val checksum = snapshot.checksumOk
        StatRow(
            label = "校验结果",
            value = when {
                checksum == null -> "协议未启用校验（字段仍可见）"
                checksum -> "通过"
                else -> "不通过"
            },
            valueColor = if (checksum == false) CriticalColor else MaterialTheme.colorScheme.onSurface,
        )
        if (snapshot.decodeProblems.isEmpty()) {
            StatRow(label = "异常", value = "无越界或解析问题")
        } else {
            snapshot.decodeProblems.forEach { problem ->
                Text(text = problem, style = MaterialTheme.typography.bodySmall, color = CriticalColor)
            }
        }
        StatRow(label = "已解码帧数", value = "${snapshot.link.framesReceived}")
    }
}

@Composable
private fun FieldListPanel(spec: ProtocolSpec, onApply: (ProtocolSpec) -> Unit) {
    SectionHeader("字段表", "改偏移/长度即刻生效：引擎会用新协议重新解码随后的每一帧。长度必须 1~8，越界会在解码健康里报错。")
    spec.fields.forEach { field ->
        FieldEditorPanel(
            field = field,
            frameLength = spec.frameLength,
            onChange = { updated ->
                onApply(spec.copy(fields = spec.fields.map { if (it.key == field.key) updated else it }))
            },
            onDelete = { onApply(spec.copy(fields = spec.fields.filter { it.key != field.key })) },
        )
    }
    Button(
        onClick = {
            val nextOffset = spec.highestByteRead.coerceAtMost((spec.frameLength - 1).coerceAtLeast(0))
            val fresh = FieldSpec(
                key = "b%02d_new".format(nextOffset), label = "新字段", offset = nextOffset, length = 1,
                endianness = Endianness.LITTLE, scale = 1.0, unit = "", role = FieldRole.UNKNOWN,
            )
            onApply(spec.copy(fields = spec.fields + fresh))
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("新增字段") }
}

@Composable
private fun FieldEditorPanel(
    field: FieldSpec,
    frameLength: Int,
    onChange: (FieldSpec) -> Unit,
    onDelete: () -> Unit,
) {
    Panel(title = field.key, trailing = roleLabel(field.role)) {
        Text(text = field.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(
                label = "偏移",
                value = field.offset.toString(),
                decimal = false,
                modifier = Modifier.weight(1f),
                onCommit = { raw -> raw.toIntOrNull()?.coerceIn(0, 63)?.let { onChange(field.copy(offset = it)) } },
            )
            NumberField(
                label = "长度",
                value = field.length.toString(),
                decimal = false,
                modifier = Modifier.weight(1f),
                onCommit = { raw -> raw.toIntOrNull()?.coerceIn(1, 8)?.let { onChange(field.copy(length = it)) } },
            )
        }
        NumberField(
            label = "倍率 scale",
            value = field.scale.toString(),
            modifier = Modifier.fillMaxWidth(),
            onCommit = { raw -> raw.toDoubleOrNull()?.let { onChange(field.copy(scale = it)) } },
        )
        ChoiceRow(
            label = "字节序",
            options = Endianness.entries,
            selected = field.endianness,
            labelOf = { if (it == Endianness.LITTLE) "LE" else "BE" },
            onSelect = { onChange(field.copy(endianness = it)) },
        )
        ChoiceRow(
            label = "角色",
            options = FieldRole.entries,
            selected = field.role,
            labelOf = { roleLabel(it) },
            onSelect = { onChange(field.copy(role = it)) },
        )
        LabeledSwitch(
            label = "有符号读取",
            checked = field.signed,
            onChange = { onChange(field.copy(signed = it)) },
            description = if (field.signed) "按补码取负" else "按无符号读取",
        )
        Text(
            text = "读取范围 ${field.offset}..${field.offset + field.length - 1}，单位 ${field.unit.ifEmpty { "-" }}" +
                if (field.offset + field.length > frameLength) "（已超出帧长 ${frameLength}）" else "",
            style = MaterialTheme.typography.bodySmall,
            color = if (field.offset + field.length > frameLength) CriticalColor else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onDelete) { Text("删除字段", color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun PresetPanel(spec: ProtocolSpec, onApply: (ProtocolSpec) -> Unit) {
    val currentText = spec.encode()
    val matched = WsBlePresets.all.filter { it.encode() == currentText }
    Panel(title = "预设") {
        WsBlePresets.all.forEach { preset ->
            val active = preset.encode() == currentText
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (active) {
                    FilledTonalButton(onClick = { onApply(preset) }, modifier = Modifier.weight(1f)) { Text(preset.displayName) }
                } else {
                    OutlinedButton(onClick = { onApply(preset) }, modifier = Modifier.weight(1f)) { Text(preset.displayName) }
                }
                Text(
                    text = if (active) "使用中" else "应用",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (active) RegenColor else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = if (matched.isEmpty()) {
                "当前自定义（编号 ${spec.id}）：与任何预设都不完全一致，字段改动只存在设置的协议文本里；" +
                    "改字段不会改编号，所以以文本内容为准判断是否还在用预设。"
            } else {
                "当前布局与预设“${matched.first().displayName}”完全一致。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SpecTextPanel(spec: ProtocolSpec?, onApplyText: (String) -> Unit) {
    var draft by remember { mutableStateOf<String?>(null) }
    val shown = draft ?: spec?.encode() ?: ""
    Panel(title = "协议文本", trailing = if (draft == null) "与设置同步" else "未应用") {
        Text(
            text = "SPEC|id|名称|帧长|校验|校验偏移|符号模式|轮询命令|起始命令|magic，之后每行一个 FIELD。" +
                "这是存储格式本身，可直接粘贴实骑证明后的布局。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = shown,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            minLines = 6,
            maxLines = 14,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    draft?.let { onApplyText(it) }
                    draft = null
                },
                modifier = Modifier.weight(1f),
                enabled = draft != null,
            ) { Text("应用文本") }
            OutlinedButton(onClick = { draft = null }, modifier = Modifier.weight(1f)) { Text("放弃修改") }
        }
    }
}
