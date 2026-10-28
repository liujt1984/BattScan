package com.battscan.leadacid.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.battscan.leadacid.ble.ScannedDevice
import com.battscan.leadacid.core.model.BatteryConfig
import com.battscan.leadacid.core.model.Chemistry
import com.battscan.leadacid.data.MonitorSettings
import com.battscan.leadacid.ui.MonitorViewModel
import com.battscan.leadacid.ui.UiState
import kotlin.math.roundToInt

private const val POLL_MIN_MS = 200L
private const val POLL_MAX_MS = 3000L
private const val TIMEOUT_MIN_MS = 600L
private const val TIMEOUT_MAX_MS = 6000L
private const val DEFAULT_POLL_MS = 1160L
private const val DEFAULT_TIMEOUT_MS = 2200L

@Composable
fun SettingsScreen(vm: MonitorViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val host = remember { SnackbarHostState() }
    var confirmFullCharge by remember { mutableStateOf(false) }

    LaunchedEffect(ui.toast) {
        val message = ui.toast
        if (message != null) {
            host.showSnackbar(message)
            vm.consumeToast()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ScreenHeader("设置", "设备、采样节奏与电池参数；改动即时写入 DataStore 并热更新采集引擎。")
            DevicePanel(vm, ui)
            PollingPanel(vm, ui.settings)
            BatteryPanel(vm, ui.settings.battery)
            SocPanel(vm, ui, snapshot.soc?.socPercent, onConfirmFull = { confirmFullCharge = true })
            RecordPanel(vm, ui.settings)
            OutlinedButton(onClick = { vm.resetSession() }, modifier = Modifier.fillMaxWidth()) { Text("结束当前会话") }
        }
        SnackbarHost(hostState = host, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp))
    }

    if (confirmFullCharge) {
        AlertDialog(
            onDismissRequest = { confirmFullCharge = false },
            title = { Text("确认已充满？") },
            text = {
                Text(
                    "只有充电器已转入涓流、单体电压到达终止电压并且静置过之后，才该把 SOC 置为 100%。" +
                        "过早置满会让随后几十公里的放电读数整体偏高。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.recalibrateSoc(100.0)
                        confirmFullCharge = false
                    },
                ) { Text("确认置为 100%") }
            },
            dismissButton = { TextButton(onClick = { confirmFullCharge = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun DevicePanel(vm: MonitorViewModel, ui: UiState) {
    val selected = ui.settings.deviceAddress
    Panel(title = "设备", trailing = if (selected == null) "未选择" else ui.settings.deviceName.ifEmpty { selected }) {
        if (selected != null) {
            StatRow(label = "地址", value = selected)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.startScan() }, modifier = Modifier.weight(1f), enabled = !ui.scanning) { Text("开始扫描") }
            OutlinedButton(onClick = { vm.stopScan() }, modifier = Modifier.weight(1f), enabled = ui.scanning) { Text("停止扫描") }
        }
        if (ui.scanning) {
            Text(text = "扫描中…靠近模块可以提高 RSSI 排序。", style = MaterialTheme.typography.bodySmall)
        }
        if (ui.scanResults.isEmpty() && !ui.scanning) {
            Text(
                text = "列表为空。GATT 名称是 WS-BLE，但广播名多为 eLZW102z，所以按广播名标记“疑似”。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ui.scanResults.forEach { device ->
            DeviceRow(
                device = device,
                isCurrent = device.address == selected,
                onPick = { vm.selectDevice(device.address, device.name ?: device.advertisedName ?: "") },
            )
        }
        if (selected != null) {
            TextButton(onClick = { vm.selectDevice(null, "") }) { Text("清除设备") }
        }
        Text(
            text = "选中后引擎立即按新地址重连；若一直停在“发现服务/订阅中”，说明该外设没有 0xFFF0 服务或 FFF2 特征。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DeviceRow(device: ScannedDevice, isCurrent: Boolean, onPick: () -> Unit) {
    val title = listOfNotNull(device.name, device.advertisedName).firstOrNull() ?: "未命名设备"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "${device.address} · ${device.rssi} dBm",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (device.looksLikeCoulombCounter) TagText("疑似计量模块", RegenColor)
        if (isCurrent) {
            TagText("已选", InfoColor)
        } else {
            TextButton(onClick = onPick) { Text("选择") }
        }
    }
}

@Composable
private fun TagText(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun PollingPanel(vm: MonitorViewModel, settings: MonitorSettings) {
    val interval = settings.pollIntervalMs
    val timeout = settings.responseTimeoutMs
    Panel(title = "采样节奏", trailing = "%.2f Hz".format(1000.0 / interval.coerceAtLeast(1L))) {
        LabeledSlider(
            label = "轮询间隔",
            valueText = "$interval ms",
            fraction = (interval - POLL_MIN_MS).toFloat() / (POLL_MAX_MS - POLL_MIN_MS),
            onFractionChange = { f -> vm.setPolling(fromFraction(f, POLL_MIN_MS, POLL_MAX_MS, 20L), timeout) },
        )
        LabeledSlider(
            label = "应答超时",
            valueText = "$timeout ms",
            fraction = (timeout - TIMEOUT_MIN_MS).toFloat() / (TIMEOUT_MAX_MS - TIMEOUT_MIN_MS),
            onFractionChange = { f -> vm.setPolling(interval, fromFraction(f, TIMEOUT_MIN_MS, TIMEOUT_MAX_MS, 100L)) },
        )
        StatRow(label = "超时 / 间隔", value = "%.2f".format(timeout.toDouble() / interval.coerceAtLeast(1L)))
        Text(
            text = "抓包模块实测约每 1.16 s 回一帧，所以默认间隔 1160 ms、超时 2200 ms（约两倍余量）。" +
                "间隔拉太长（低于 0.72 Hz）会漏掉加速瞬间的电流尖峰，回收制动也只剩几帧窗口；" +
                "间隔压太短会连续超时，反而更丢帧。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = { vm.setPolling(DEFAULT_POLL_MS, DEFAULT_TIMEOUT_MS) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("恢复默认 1160 / 2200 ms") }
        LabeledSwitch(
            label = "断开后自动重连",
            checked = settings.autoReconnect,
            description = "抓包里的模块会以 0x08 连接超时静默失联，所以默认开着；关掉可把一段骑行钉在一次连接上，" +
                "中断后停在断开状态，需要手动重新采集",
            onChange = { vm.setAutoReconnect(it) },
        )
    }
}

private fun fromFraction(fraction: Float, min: Long, max: Long, step: Long): Long {
    val span = (max - min).toFloat()
    val raw = min + (fraction.coerceIn(0f, 1f) * span).roundToInt()
    val snapped = ((raw + step / 2) / step) * step
    return snapped.coerceIn(min, max)
}

@Composable
private fun BatteryPanel(vm: MonitorViewModel, battery: BatteryConfig) {
    val explicitResistance = battery.internalResistanceOhm
    Panel(title = "电池参数", trailing = "${battery.seriesCellCount} 个 2V 单体") {
        Text(
            text = "块数按 12V 铅酸块计数（6 块 = 36 格），单体阈值都落在 2V 格上；" +
                "块数填错会同时错算每格电压和 OCV 锚点。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ChoiceRow(
            label = "体系",
            options = Chemistry.entries,
            selected = battery.chemistry,
            labelOf = { it.displayLabel },
            onSelect = { vm.setBattery(battery.copy(chemistry = it)) },
        )
        IntRow(label = "12V 块数", value = battery.blockCount) { vm.setBattery(battery.copy(blockCount = it)) }
        DoubleRow("标称容量 Ah", battery.ratedCapacityAh) { vm.setBattery(battery.copy(ratedCapacityAh = it)) }
        DoubleRow("标称率小时数", battery.ratedAtHours) { vm.setBattery(battery.copy(ratedAtHours = it)) }
        DoubleRow("Peukert 指数", battery.peukertExponent) { vm.setBattery(battery.copy(peukertExponent = it)) }
        DoubleRow("充电效率", battery.chargeEfficiency) { vm.setBattery(battery.copy(chargeEfficiency = it)) }
        LabeledSwitch(
            label = "内阻自动学习（留空）",
            checked = explicitResistance == null,
            description = explicitResistance?.let { "手动值 %.3f Ω".format(it) } ?: "由 V/I 拟合自动给出",
            onChange = { auto -> vm.setBattery(battery.copy(internalResistanceOhm = if (auto) null else 0.25)) },
        )
        if (explicitResistance != null) {
            DoubleRow("整组内阻 Ω", explicitResistance) { vm.setBattery(battery.copy(internalResistanceOhm = it)) }
        }
        DoubleRow("单体欠压告警 V", battery.cellVoltageWarnLow) { vm.setBattery(battery.copy(cellVoltageWarnLow = it)) }
        DoubleRow("单体欠压截止 V", battery.cellVoltageCutoffLow) { vm.setBattery(battery.copy(cellVoltageCutoffLow = it)) }
        DoubleRow("单体过压告警 V", battery.cellVoltageWarnHigh) { vm.setBattery(battery.copy(cellVoltageWarnHigh = it)) }
        DoubleRow("单体过压截止 V", battery.cellVoltageCutoffHigh) { vm.setBattery(battery.copy(cellVoltageCutoffHigh = it)) }
        DoubleRow("电流告警 A", battery.warnCurrentA) { vm.setBattery(battery.copy(warnCurrentA = it)) }
        DoubleRow("电流截止 A", battery.cutoffCurrentA) { vm.setBattery(battery.copy(cutoffCurrentA = it)) }
        DoubleRow("温度告警 °C", battery.warnTempC) { vm.setBattery(battery.copy(warnTempC = it)) }
        DoubleRow("温度截止 °C", battery.cutoffTempC) { vm.setBattery(battery.copy(cutoffTempC = it)) }
        DoubleRow("待机死区 A", battery.idleDeadbandA) { vm.setBattery(battery.copy(idleDeadbandA = it)) }
        DoubleRow("平均能耗 Wh/km", battery.avgConsumptionWhPerKm) { vm.setBattery(battery.copy(avgConsumptionWhPerKm = it)) }
        Text(
            text = "整组：标称 %.0f V，额定 %.2f Ah，死区 %.2f A。".format(
                battery.nominalVoltageV,
                battery.ratedCapacityAh,
                battery.idleDeadbandA,
            ),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun DoubleRow(label: String, value: Double, onCommit: (Double) -> Unit) {
    NumberField(label = label, value = value.toString(), modifier = Modifier.fillMaxWidth()) { raw ->
        raw.toDoubleOrNull()?.let(onCommit)
    }
}

@Composable
private fun IntRow(label: String, value: Int, onCommit: (Int) -> Unit) {
    NumberField(label = label, value = value.toString(), decimal = false, modifier = Modifier.fillMaxWidth()) { raw ->
        raw.toIntOrNull()?.coerceIn(1, 64)?.let(onCommit)
    }
}

@Composable
private fun SocPanel(
    vm: MonitorViewModel,
    ui: UiState,
    currentPercent: Double?,
    onConfirmFull: () -> Unit,
) {
    var manualSoc by remember { mutableStateOf("") }
    Panel(title = "SOC 标定", trailing = currentPercent?.let { "%.1f %%".format(it) } ?: "无数据") {
        StatRow(label = "上次手动值", value = ui.settings.manualSocPercent?.let { "%.1f %%".format(it) } ?: "未设置")
        Button(onClick = onConfirmFull, modifier = Modifier.fillMaxWidth()) { Text("置为 100%（确认已充满后）") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FreeTextField(
                label = "任意值 %",
                value = manualSoc,
                modifier = Modifier.weight(1f),
                onChange = { manualSoc = it },
            )
            OutlinedButton(
                onClick = { manualSoc.toDoubleOrNull()?.let { vm.recalibrateSoc(it.coerceIn(0.0, 100.0)) } },
                enabled = manualSoc.toDoubleOrNull() != null,
            ) { Text("应用") }
        }
        Text(
            text = "手动置值只改写积分器，不改容量与内阻；若积分值与电压推算长期分叉，先静置再核对参数。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FreeTextField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun RecordPanel(vm: MonitorViewModel, settings: MonitorSettings) {
    Panel(title = "记录") {
        LabeledSwitch(
            label = "记录采样",
            checked = settings.loggingEnabled,
            description = "关闭后不写库，历史会话与回放都会变空；实时显示不受影响",
            onChange = { vm.setLogging(it) },
        )
        LabeledSwitch(
            label = "抓取原始帧",
            checked = settings.rawFrameCapture,
            description = "把每帧收发原样存库，用于证明协议字段；实骑建议开着，库体积会明显增大",
            onChange = { vm.setRawCapture(it) },
        )
    }
}
