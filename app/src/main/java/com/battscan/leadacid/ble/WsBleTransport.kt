package com.battscan.leadacid.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.SystemClock
import com.battscan.leadacid.core.protocol.ProtocolSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** 0xFFF0 vendor service; FFF1 is the app-to-module write channel, FFF2 the uplink channel. */
object WsBleUuids {
    val SERVICE: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    val CHAR_WRITE: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
    val CHAR_NOTIFY: UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

enum class LinkPhase {
    IDLE,
    SCANNING,
    CONNECTING,
    DISCOVERING_SERVICES,
    SUBSCRIBING,
    POLLING,
    WAITING_RECONNECT,
    STOPPED,
}

data class LinkStatus(
    val phase: LinkPhase = LinkPhase.IDLE,
    val deviceAddress: String? = null,
    val deviceName: String? = null,
    val detail: String = "",
    val pollsSent: Long = 0,
    val framesReceived: Long = 0,
    val pollTimeouts: Long = 0,
    val reconnects: Int = 0,
    val rssi: Int? = null,
    val lastFrameMonotonicMs: Long = 0L,
) {
    val online: Boolean get() = phase == LinkPhase.POLLING
    val successRate: Double get() = if (pollsSent == 0L) 0.0 else framesReceived.toDouble() / pollsSent
}

sealed interface TransportEvent {
    data class RxFrame(val bytes: ByteArray, val monotonicMs: Long, val wallClockMs: Long) : TransportEvent
    data class TxFrame(val bytes: ByteArray, val monotonicMs: Long) : TransportEvent
    data class Log(val line: String, val monotonicMs: Long) : TransportEvent

    companion object {
        fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02X".format(it) }
    }
}

private class LinkException(message: String) : Exception(message)

/**
 * Owns one BLE link to the WS-BLE module and keeps it alive.
 *
 * Behaviour forced by the 2026-10-28 capture:
 *  - the module declares Notify but actually transmits **Indication**, so the CCCD is written 0x0003
 *    (both bits) - a 0x0001-only subscription can leave the app with a healthy link and no data.
 *  - both teardowns in the log were 0x08 connection timeout, meaning the module vanished silently,
 *    so two unanswered polls declare the link dead and force a reconnect rather than blind-firing
 *    polls for another twelve seconds the way the original app did.
 */
@SuppressLint("MissingPermission")
class WsBleTransport(
    private val context: Context,
    private val scope: CoroutineScope,
    private val specProvider: () -> ProtocolSpec,
) {
    private val _status = MutableStateFlow(LinkStatus())
    val status: StateFlow<LinkStatus> = _status.asStateFlow()

    private val _events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 512)
    val events: Flow<TransportEvent> = _events.asSharedFlow()

    private val writeLock = Mutex()
    private var gatt: BluetoothGatt? = null
    private var sessionJob: Job? = null
    private var targetAddress: String? = null
    private var pollIntervalMs = DEFAULT_POLL_INTERVAL_MS
    private var responseTimeoutMs = DEFAULT_RESPONSE_TIMEOUT_MS
    private var autoReconnect = true

    private var connectResume: Continuation<BluetoothGatt>? = null
    private var stepResume: Continuation<Unit>? = null
    private var frameSignal: Channel<Unit>? = null
    private var lastRssi = 0

    private val adapter: BluetoothAdapter? get() = BluetoothAdapter.getDefaultAdapter()

    fun configure(address: String?, pollIntervalMs: Long, responseTimeoutMs: Long, autoReconnect: Boolean) {
        val changed = this.targetAddress != address
        this.targetAddress = address
        this.pollIntervalMs = pollIntervalMs.coerceIn(200L, 10_000L)
        this.responseTimeoutMs = responseTimeoutMs.coerceIn(600L, 15_000L)
        val wasDisabled = !this.autoReconnect
        this.autoReconnect = autoReconnect
        // Re-enabling it after a refused reconnect is the only way back to a live link, so the
        // supervisor has to be relaunched even though nothing else changed.
        if (changed || (autoReconnect && (wasDisabled || sessionJob?.isActive != true))) restart()
    }

    fun start() {
        if (sessionJob?.isActive == true) return
        sessionJob = scope.launch { supervise() }
    }

    fun stop() {
        sessionJob?.cancel()
        sessionJob = null
        closeGatt()
        _status.value = _status.value.copy(phase = LinkPhase.STOPPED, detail = "已停止")
    }

    fun restart() {
        sessionJob?.cancel()
        sessionJob = scope.launch { supervise() }
    }

    private suspend fun supervise() {
        var backoff = INITIAL_BACKOFF_MS
        while (scope.isActive) {
            val bluetooth = adapter
            val address = targetAddress
            if (bluetooth == null || !bluetooth.isEnabled || address.isNullOrBlank()) {
                _status.value = _status.value.copy(
                    phase = LinkPhase.IDLE,
                    detail = when {
                        bluetooth == null -> "本机无蓝牙"
                        !bluetooth.isEnabled -> "蓝牙未开启"
                        else -> "未选择设备"
                    },
                )
                delay(2000)
                continue
            }
            backoff = try {
                openSession(bluetooth, address)
                INITIAL_BACKOFF_MS
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!autoReconnect) {
                    log("链路结束: ${e.message}；自动重连已关闭，停在断开状态")
                    _status.value = _status.value.copy(
                        phase = LinkPhase.STOPPED,
                        detail = "${e.message ?: "断开"}（自动重连已关闭）",
                        reconnects = _status.value.reconnects + 1,
                    )
                    return
                }
                log("链路结束: ${e.message}，${backoff / 1000}s 后重连")
                _status.value = _status.value.copy(
                    phase = LinkPhase.WAITING_RECONNECT,
                    detail = e.message ?: "断开",
                    reconnects = _status.value.reconnects + 1,
                )
                minOf(backoff * 2, MAX_BACKOFF_MS)
            }
            delay(backoff)
        }
    }

    private suspend fun openSession(bluetooth: BluetoothAdapter, address: String) {
        val device = bluetooth.getRemoteDevice(address)
        _status.value = _status.value.copy(phase = LinkPhase.CONNECTING, deviceAddress = address, detail = "连接中")
        val link = connect(device)
        gatt = link
        try {
            val spec = specProvider()
            _status.value = _status.value.copy(phase = LinkPhase.DISCOVERING_SERVICES, detail = "发现服务")
            discover(link)
            val service = link.getService(WsBleUuids.SERVICE) ?: throw LinkException("设备无 0xFFF0 服务")
            val notifyChar = service.getCharacteristic(WsBleUuids.CHAR_NOTIFY) ?: throw LinkException("无 FFF2")
            val writeChar = service.getCharacteristic(WsBleUuids.CHAR_WRITE) ?: throw LinkException("无 FFF1")

            _status.value = _status.value.copy(phase = LinkPhase.SUBSCRIBING, detail = "订阅上报")
            subscribe(link, notifyChar)

            if (spec.sessionStartCommand.isNotEmpty()) {
                send(link, writeChar, spec.sessionStartCommand.toByteArray())
            }
            _status.value = _status.value.copy(
                phase = LinkPhase.POLLING,
                deviceName = runCatching { device.name }.getOrNull(),
                detail = "已连接",
            )
            pollLoop(link, writeChar)
        } finally {
            closeGatt()
        }
    }

    private suspend fun pollLoop(link: BluetoothGatt, writeChar: BluetoothGattCharacteristic) {
        var timeouts = 0L
        while (scope.isActive) {
            val spec = specProvider()
            if (spec.pollCommand.isEmpty()) throw LinkException("协议未定义轮询命令")
            val startedAt = SystemClock.elapsedRealtime()
            val signal = Channel<Unit>(Channel.CONFLATED)
            frameSignal = signal
            send(link, writeChar, spec.pollCommand.toByteArray())
            _status.value = _status.value.copy(pollsSent = _status.value.pollsSent + 1)

            val arrived = withTimeoutOrNull(responseTimeoutMs) { signal.receiveCatching().isSuccess }
            frameSignal = null
            if (arrived == true) {
                timeouts = 0
                _status.value = _status.value.copy(
                    framesReceived = _status.value.framesReceived + 1,
                    lastFrameMonotonicMs = SystemClock.elapsedRealtime(),
                    rssi = lastRssi.takeIf { lastRssi != 0 },
                )
            } else {
                timeouts++
                _status.value = _status.value.copy(pollTimeouts = _status.value.pollTimeouts + 1)
                log("轮询无应答 #$timeouts")
                if (timeouts >= MAX_CONSECUTIVE_TIMEOUTS) {
                    throw LinkException("连续 $timeouts 次无应答，判定链路失效")
                }
            }
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            delay((pollIntervalMs - elapsed).coerceAtLeast(MIN_GAP_MS))
        }
    }

    private suspend fun connect(device: BluetoothDevice): BluetoothGatt =
        suspendCancellableCoroutine { cont ->
            connectResume = cont
            cont.invokeOnCancellation { connectResume = null }
            val started = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            if (!started) {
                connectResume = null
                if (cont.isActive) cont.resumeWithException(LinkException("connectGatt 返回 false"))
            }
        }

    private suspend fun discover(link: BluetoothGatt) = awaitStep(CONNECT_STEP_TIMEOUT_MS, "服务发现") {
        if (!link.discoverServices()) throw LinkException("discoverServices 返回 false")
    }

    /**
     * Enables notification *and* indication (0x0003). The captured module declares Notify but sends
     * Indication, so subscribing with only the notify bit yields a connected link with no payloads.
     */
    private suspend fun subscribe(link: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        if (!link.setCharacteristicNotification(characteristic, true)) {
            throw LinkException("setCharacteristicNotification 失败")
        }
        val cccd = characteristic.getDescriptor(WsBleUuids.CCCD) ?: throw LinkException("FFF2 无 CCCD")
        cccd.value = ENABLE_NOTIFICATION_AND_INDICATION
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        awaitStep(CONNECT_STEP_TIMEOUT_MS, "订阅") {
            if (!link.writeDescriptor(cccd)) throw LinkException("writeDescriptor 返回 false")
        }
    }

    private suspend fun send(link: BluetoothGatt, characteristic: BluetoothGattCharacteristic, payload: ByteArray) {
        writeLock.withLock {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.value = payload
            _events.tryEmit(TransportEvent.TxFrame(payload, SystemClock.elapsedRealtime()))
            awaitStep(WRITE_STEP_TIMEOUT_MS, "写 ${TransportEvent.hex(payload)}") {
                if (!link.writeCharacteristic(characteristic)) throw LinkException("writeCharacteristic 返回 false")
            }
        }
    }

    private suspend fun awaitStep(timeoutMs: Long, label: String, start: () -> Unit) {
        val outcome = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Unit> { cont ->
                stepResume = cont
                cont.invokeOnCancellation { if (stepResume === cont) stepResume = null }
                try {
                    start()
                } catch (e: Exception) {
                    if (cont.isActive) {
                        stepResume = null
                        cont.resumeWithException(e)
                    }
                }
            }
            true
        }
        stepResume = null
        if (outcome != true) throw LinkException("${label}超时")
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                val cont = connectResume
                connectResume = null
                if (cont != null && cont.isActive) cont.resume(g)
                return
            }
            val reason = "链路断开 status=0x%02X".format(status)
            log(reason)
            val cont = connectResume
            connectResume = null
            if (cont != null && cont.isActive) cont.resumeWithException(LinkException(reason))
            val step = stepResume
            stepResume = null
            if (step != null && step.isActive) step.resumeWithException(LinkException(reason))
            frameSignal?.close()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            completeStep(if (status == BluetoothGatt.GATT_SUCCESS) Result.success(Unit) else fail("服务发现 0x%02X".format(status)))
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            completeStep(
                if (status == BluetoothGatt.GATT_SUCCESS) Result.success(Unit)
                else fail("写特征 0x%02X".format(status)),
            )
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            completeStep(
                if (status == BluetoothGatt.GATT_SUCCESS) Result.success(Unit)
                else fail("写 CCCD 0x%02X".format(status)),
            )
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) lastRssi = rssi
        }

        @Deprecated("Required below API 33; the platform still routes indications here.")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleUplink(characteristic.value)
        }
    }

    private fun fail(message: String): Result<Unit> = Result.failure(LinkException(message))

    private fun completeStep(result: Result<Unit>) {
        val cont = stepResume
        stepResume = null
        if (cont == null || !cont.isActive) return
        result.fold(
            onSuccess = { cont.resume(Unit) },
            onFailure = { cont.resumeWithException(it) },
        )
    }

    private fun handleUplink(value: ByteArray?) {
        if (value == null || value.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        frameSignal?.trySend(Unit)
        _events.tryEmit(TransportEvent.RxFrame(value, now, System.currentTimeMillis()))
    }

    private fun closeGatt() {
        runCatching { gatt?.close() }
        gatt = null
    }

    private fun log(line: String) {
        _events.tryEmit(TransportEvent.Log(line, SystemClock.elapsedRealtime()))
    }

    private fun List<Int>.toByteArray(): ByteArray = ByteArray(size) { idx -> this[idx].toByte() }

    companion object {
        val ENABLE_NOTIFICATION_AND_INDICATION = byteArrayOf(0x03, 0x00)
        const val DEFAULT_POLL_INTERVAL_MS = 1160L
        const val DEFAULT_RESPONSE_TIMEOUT_MS = 2200L
        const val MAX_CONSECUTIVE_TIMEOUTS = 2L
        const val MIN_GAP_MS = 50L
        const val INITIAL_BACKOFF_MS = 1000L
        const val MAX_BACKOFF_MS = 20_000L
        const val CONNECT_STEP_TIMEOUT_MS = 12_000L
        const val WRITE_STEP_TIMEOUT_MS = 6_000L
    }
}
