package com.battscan.leadacid.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewModelScope
import com.battscan.leadacid.BattScanApp
import com.battscan.leadacid.ble.BleScanner
import com.battscan.leadacid.ble.ScannedDevice
import com.battscan.leadacid.core.model.BatteryConfig
import com.battscan.leadacid.core.protocol.ProtocolSpec
import com.battscan.leadacid.data.CsvExporter
import com.battscan.leadacid.data.MonitorSettings
import com.battscan.leadacid.data.db.SessionDetail
import com.battscan.leadacid.monitor.MonitorSnapshot
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class UiState(
    val settings: MonitorSettings = MonitorSettings(),
    val scanning: Boolean = false,
    val scanResults: List<ScannedDevice> = emptyList(),
    val toast: String? = null,
    val busy: Boolean = false,
)

/**
 * Bridges the engine and the stores to Compose. Screens read exactly three things from here: the
 * live snapshot, the persisted history, and the editable settings document.
 */
class MonitorViewModel(private val app: BattScanApp) : ViewModel() {

    val snapshot: StateFlow<MonitorSnapshot> = app.engine.snapshot

    val sessions = app.database.sessionDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _detail = MutableStateFlow<SessionDetail?>(null)
    val detail: StateFlow<SessionDetail?> = _detail.asStateFlow()

    private val scanner = BleScanner(app)
    private var scanJob: Job? = null

    init {
        viewModelScope.launch {
            app.settings.settings
                .catch { _ui.value = _ui.value.copy(toast = "读取设置失败: ${it.message}") }
                .collect { _ui.value = _ui.value.copy(settings = it) }
        }
    }

    fun startScan() {
        if (scanJob?.isActive == true) return
        _ui.value = _ui.value.copy(scanning = true, scanResults = emptyList(), toast = "扫描中…")
        scanJob = viewModelScope.launch {
            scanner.scan()
                .catch {
                    _ui.value = _ui.value.copy(scanning = false, toast = "扫描失败: ${it.message}")
                }
                .collect { list ->
                    _ui.value = _ui.value.copy(scanResults = list)
                }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _ui.value = _ui.value.copy(scanning = false)
    }

    fun selectDevice(address: String?, name: String) {
        viewModelScope.launch {
            app.settings.setDevice(address, name)
            _ui.value = _ui.value.copy(scanning = false, toast = if (address == null) "已清除设备" else "已选择 ${name.ifBlank { address }}")
        }
    }

    fun setPolling(intervalMs: Long, timeoutMs: Long) {
        viewModelScope.launch { app.settings.setPolling(intervalMs, timeoutMs) }
    }

    fun setProtocol(spec: ProtocolSpec) {
        viewModelScope.launch { app.settings.setProtocol(spec.encode()) }
    }

    fun setProtocolText(text: String) {
        viewModelScope.launch {
            if (ProtocolSpec.decode(text) == null) {
                _ui.value = _ui.value.copy(toast = "协议文本无法解析，已忽略")
            } else {
                app.settings.setProtocol(text)
                _ui.value = _ui.value.copy(toast = "协议已保存")
            }
        }
    }

    fun setBattery(config: BatteryConfig) {
        viewModelScope.launch { app.settings.setBattery(config.encode()) }
    }

    fun setLogging(enabled: Boolean) {
        viewModelScope.launch { app.settings.setLogging(enabled) }
    }

    fun setRawCapture(enabled: Boolean) {
        viewModelScope.launch { app.settings.setRawCapture(enabled) }
    }

    fun setAutoReconnect(enabled: Boolean) {
        viewModelScope.launch { app.settings.setAutoReconnect(enabled) }
    }

    fun recalibrateSoc(percent: Double) {
        app.engine.recalibrateSoc(percent)
        viewModelScope.launch { app.settings.setManualSoc(percent) }
        _ui.value = _ui.value.copy(toast = "SOC 已置为 %.1f%%".format(percent))
    }

    fun resetSession() = app.engine.resetSession()

    fun clearAlarms() = app.engine.clearAlarms()

    fun loadDetail(sessionId: Long) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(busy = true)
            val session = app.database.sessionDao().byId(sessionId)
            val samples = if (session == null) emptyList() else app.database.sampleDao().forSession(sessionId)
            _detail.value = session?.let { SessionDetail(it, samples) }
            _ui.value = _ui.value.copy(busy = false)
        }
    }

    fun export(sessionId: Long) {
        viewModelScope.launch {
            val exporter = CsvExporter(app, app.database.sessionDao(), app.database.sampleDao())
            runCatching { exporter.export(sessionId) }
                .onSuccess { _ui.value = _ui.value.copy(toast = "已导出 ${it.name}") }
                .onFailure { _ui.value = _ui.value.copy(toast = "导出失败: ${it.message}") }
        }
    }

    fun deleteSession(id: Long) {
        viewModelScope.launch { app.database.sessionDao().delete(id) }
    }

    fun setSessionNote(id: Long, note: String) {
        viewModelScope.launch { app.database.sessionDao().setNote(id, note) }
    }

    fun consumeToast() {
        _ui.value = _ui.value.copy(toast = null)
    }

    val engine get() = app.engine

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as BattScanApp
                return MonitorViewModel(app) as T
            }
        }
    }
}
