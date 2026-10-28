package com.battscan.leadacid.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

data class ScannedDevice(
    val address: String,
    val name: String?,
    val advertisedName: String?,
    val rssi: Int,
    val lastSeenMs: Long,
) {
    /** The captured module advertises as eLZW102z but exposes WS-BLE over GATT. */
    val looksLikeCoulombCounter: Boolean
        get() = listOfNotNull(name, advertisedName).any { it.contains("WS-BLE", true) || it.contains("eLZW", true) }
}

/**
 * Low-energy scan exposed as a flow so the settings screen can list candidates while riding.
 *
 * Reports are aggregated per address: BLE advertises repeatedly, and a raw per-advertisement list
 * would make the picker flicker and hide which devices are actually still present.
 */
class BleScanner(private val context: Context) {

    @SuppressLint("MissingPermission")
    fun scan(): Flow<List<ScannedDevice>> = callbackFlow {
        val manager = context.getSystemService(BluetoothManager::class.java)
        val scanner = manager?.adapter?.bluetoothLeScanner
        if (scanner == null) {
            close(IllegalStateException("蓝牙不可用"))
            return@callbackFlow
        }
        val found = LinkedHashMap<String, ScannedDevice>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val entry = ScannedDevice(
                    address = device.address,
                    name = runCatching { device.name }.getOrNull(),
                    advertisedName = runCatching { result.scanRecord?.deviceName }.getOrNull(),
                    rssi = result.rssi,
                    lastSeenMs = SystemClock.elapsedRealtime(),
                )
                found[device.address] = entry
                trySend(found.values.sortedWith(compareByDescending<ScannedDevice> { it.looksLikeCoulombCounter }
                    .thenByDescending { it.rssi }))
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("扫描失败 code=$errorCode"))
            }
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()
        scanner.startScan(null, settings, callback)
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }.distinctUntilChanged()

    @SuppressLint("MissingPermission")
    fun knownOrEmpty(): List<ScannedDevice> {
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return emptyList()
        return runCatching { manager.adapter?.bondedDevices }.getOrNull()?.map {
            ScannedDevice(it.address, runCatching { it.name }.getOrNull(), null, 0, SystemClock.elapsedRealtime())
        } ?: emptyList()
    }
}
