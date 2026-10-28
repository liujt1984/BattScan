package com.battscan.leadacid.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.battscan.leadacid.core.model.BatteryConfig
import com.battscan.leadacid.core.protocol.ProtocolSpec
import com.battscan.leadacid.core.protocol.WsBlePresets
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class MonitorSettings(
    val deviceAddress: String? = null,
    val deviceName: String = "",
    val pollIntervalMs: Long = 1160L,
    val responseTimeoutMs: Long = 2200L,
    val protocolText: String = WsBlePresets.WS_BLE_18B.encode(),
    val batteryText: String = BatteryConfig().encode(),
    val loggingEnabled: Boolean = true,
    val rawFrameCapture: Boolean = false,
    val autoReconnect: Boolean = true,
    val manualSocPercent: Double? = null,
) {
    val protocol: ProtocolSpec get() = ProtocolSpec.decode(protocolText) ?: WsBlePresets.WS_BLE_18B
    val battery: BatteryConfig get() = BatteryConfig.decode(batteryText)
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "battscan_settings")

/**
 * Single source of truth for user-editable configuration. The protocol and battery descriptions are
 * stored as their own line format rather than per-column keys so an edited spec round-trips exactly
 * and can be copy-pasted between phones while the field mapping is still being proven.
 */
class SettingsStore(private val context: Context) {

    val settings: Flow<MonitorSettings> = context.dataStore.data.map { prefs ->
        MonitorSettings(
            deviceAddress = prefs[KEY_ADDRESS],
            deviceName = prefs[KEY_NAME] ?: "",
            pollIntervalMs = prefs[KEY_POLL] ?: 1160L,
            responseTimeoutMs = prefs[KEY_TIMEOUT] ?: 2200L,
            protocolText = prefs[KEY_PROTOCOL] ?: WsBlePresets.WS_BLE_18B.encode(),
            batteryText = prefs[KEY_BATTERY] ?: BatteryConfig().encode(),
            loggingEnabled = prefs[KEY_LOGGING] ?: true,
            rawFrameCapture = prefs[KEY_RAW] ?: false,
            autoReconnect = prefs[KEY_AUTO_RECONNECT] ?: true,
            manualSocPercent = prefs[KEY_MANUAL_SOC]?.toDoubleOrNull(),
        )
    }

    suspend fun setDevice(address: String?, name: String) = context.dataStore.edit {
        if (address == null) it.remove(KEY_ADDRESS) else it[KEY_ADDRESS] = address
        it[KEY_NAME] = name
    }

    suspend fun setPolling(intervalMs: Long, timeoutMs: Long) = context.dataStore.edit {
        it[KEY_POLL] = intervalMs
        it[KEY_TIMEOUT] = timeoutMs
    }

    suspend fun setProtocol(text: String) = context.dataStore.edit { it[KEY_PROTOCOL] = text }

    suspend fun setBattery(text: String) = context.dataStore.edit { it[KEY_BATTERY] = text }

    suspend fun setLogging(enabled: Boolean) = context.dataStore.edit { it[KEY_LOGGING] = enabled }

    suspend fun setRawCapture(enabled: Boolean) = context.dataStore.edit { it[KEY_RAW] = enabled }

    suspend fun setAutoReconnect(enabled: Boolean) = context.dataStore.edit { it[KEY_AUTO_RECONNECT] = enabled }

    suspend fun setManualSoc(percent: Double?) = context.dataStore.edit {
        if (percent == null) it.remove(KEY_MANUAL_SOC) else it[KEY_MANUAL_SOC] = percent.toString()
    }

    companion object {
        private val KEY_ADDRESS = stringPreferencesKey("device_address")
        private val KEY_NAME = stringPreferencesKey("device_name")
        private val KEY_POLL = longPreferencesKey("poll_interval_ms")
        private val KEY_TIMEOUT = longPreferencesKey("response_timeout_ms")
        private val KEY_PROTOCOL = stringPreferencesKey("protocol_text")
        private val KEY_BATTERY = stringPreferencesKey("battery_text")
        private val KEY_LOGGING = booleanPreferencesKey("logging_enabled")
        private val KEY_RAW = booleanPreferencesKey("raw_capture")
        private val KEY_AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        private val KEY_MANUAL_SOC = stringPreferencesKey("manual_soc")
    }
}
