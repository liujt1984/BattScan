package com.battscan.leadacid.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.battscan.leadacid.data.db.RideSessionEntity
import com.battscan.leadacid.ui.MonitorViewModel

@Composable
fun SessionsScreen(
    vm: MonitorViewModel,
    onReplay: (Long) -> Unit,
    onExport: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<RideSessionEntity?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ScreenHeader("历史会话", "每次链路稳定采集到中断为一段会话；删除只清库内记录，不影响正在进行的采集。")
            OutlinedButton(onClick = onBack) { Text("返回") }
        }
        if (sessions.isEmpty()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(12.dp),
            ) {
                EmptyState(
                    "还没有任何会话记录。会话只在链路进入“采集中”并收到第一帧数据后才创建，" +
                        "所以先在 设置 里选中 WS-BLE 设备并确认 记录采样 已打开。",
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(sessions, key = { it.id }) { session ->
                    SessionRow(
                        session = session,
                        onReplay = { onReplay(session.id) },
                        onExport = { onExport(session.id) },
                        onDelete = { pendingDelete = session },
                    )
                }
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除会话") },
            text = {
                Text(
                    "将删除 ${formatDateTime(target.startedAtMs)} 开始的会话" +
                        "（${target.sampleCount} 帧、采样明细一并级联删除），导出过的 CSV 不受影响。此操作不可撤销。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteSession(target.id)
                        pendingDelete = null
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun SessionRow(
    session: RideSessionEntity,
    onReplay: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val elapsedMs = (session.endedAtMs ?: System.currentTimeMillis()) - session.startedAtMs
    val running = session.endedAtMs == null
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = formatDateTime(session.startedAtMs),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = if (running) "进行中" else "已结束",
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.End,
                    color = if (running) WarnColor else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatRow(label = "时长", value = formatDuration(elapsedMs))
            StatRow(label = "SOC 变化", value = "%.1f%% → %.1f%%".format(session.startSoc, session.endSoc))
            StatRow(label = "放电 / 回收", value = "%.2f Ah / %.2f Ah".format(session.dischargeAh, session.recoveredAh))
            StatRow(label = "放电 / 回收能量", value = "%.1f Wh / %.1f Wh".format(session.dischargeWh, session.recoveredWh))
            StatRow(label = "最低电压", value = "%.2f V".format(session.minVoltage))
            StatRow(label = "峰值电流幅值", value = "%.2f A".format(session.maxCurrent))
            StatRow(label = "采样帧数", value = "${session.sampleCount}")
            StatRow(label = "协议", value = session.protocolId)
            if (session.note.isNotBlank()) {
                StatRow(label = "备注", value = session.note)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onReplay, modifier = Modifier.weight(1f)) { Text("回放") }
                OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f)) { Text("导出CSV") }
                TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
