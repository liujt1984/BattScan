package com.battscan.leadacid.monitor

import android.content.Context
import android.os.SystemClock
import com.battscan.leadacid.ble.LinkPhase
import com.battscan.leadacid.ble.LinkStatus
import com.battscan.leadacid.ble.TransportEvent
import com.battscan.leadacid.ble.WsBleTransport
import com.battscan.leadacid.core.algo.ActiveAlarm
import com.battscan.leadacid.core.algo.AlarmEngine
import com.battscan.leadacid.core.algo.DirectionInferer
import com.battscan.leadacid.core.algo.EmfFit
import com.battscan.leadacid.core.algo.EmfModel
import com.battscan.leadacid.core.algo.OcvTable
import com.battscan.leadacid.core.algo.AlarmSeverity
import com.battscan.leadacid.core.algo.RangeEstimate
import com.battscan.leadacid.core.algo.RangeEstimator
import com.battscan.leadacid.core.algo.RollingStats
import com.battscan.leadacid.core.algo.SocEstimator
import com.battscan.leadacid.core.algo.SocState
import com.battscan.leadacid.core.algo.StatsSummary
import com.battscan.leadacid.core.model.BatteryConfig
import com.battscan.leadacid.core.model.BatterySample
import com.battscan.leadacid.core.model.NamedReading
import com.battscan.leadacid.core.model.PowerDirection
import com.battscan.leadacid.core.model.TemperatureReading
import com.battscan.leadacid.core.protocol.CurrentSignMode
import com.battscan.leadacid.core.protocol.FieldReading
import com.battscan.leadacid.core.protocol.FieldRole
import com.battscan.leadacid.core.protocol.FrameDecodeResult
import com.battscan.leadacid.core.protocol.FrameDecoder
import com.battscan.leadacid.core.protocol.FrameStatus
import com.battscan.leadacid.core.protocol.ProtocolSpec
import com.battscan.leadacid.core.protocol.WsBlePresets
import com.battscan.leadacid.data.SettingsStore
import com.battscan.leadacid.data.db.AppDatabase
import com.battscan.leadacid.data.db.RawFrameEntity
import com.battscan.leadacid.data.db.RideSessionEntity
import com.battscan.leadacid.data.db.SampleEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

data class ChartPoint(
    val elapsedMs: Long,
    val voltageV: Double,
    val currentA: Double,
    val powerW: Double,
    val socPercent: Double,
)

data class FrameLine(
    val wallClockMs: Long,
    val outbound: Boolean,
    val hex: String,
    val note: String,
)

data class SessionTotals(
    val sessionId: Long = 0L,
    val startedAtMs: Long = 0L,
    val elapsedMs: Long = 0L,
    val sampleCount: Int = 0,
    val dischargeAh: Double = 0.0,
    val recoveredAh: Double = 0.0,
    val dischargeWh: Double = 0.0,
    val recoveredWh: Double = 0.0,
    val minVoltageV: Double = Double.MAX_VALUE,
    val maxCurrentA: Double = 0.0,
) {
    val minVoltageText: String get() = if (minVoltageV == Double.MAX_VALUE) "-" else "%.2f V".format(minVoltageV)
}

/**
 * Everything the UI renders, in one immutable object so a Composable can depend on a single flow.
 */
data class MonitorSnapshot(
    val link: LinkStatus = LinkStatus(),
    val latest: BatterySample? = null,
    val soc: SocState? = null,
    val recent5Min: StatsSummary = StatsSummary.EMPTY,
    val sessionTotals: SessionTotals = SessionTotals(),
    val alarms: List<ActiveAlarm> = emptyList(),
    val chart: List<ChartPoint> = emptyList(),
    val fit: EmfFit = EmfFit.EMPTY,
    val directionConfidence: Double = 0.0,
    val estimatedEmfV: Double = 0.0,
    val protocol: ProtocolSpec? = null,
    val battery: BatteryConfig = BatteryConfig(),
    val frameLog: List<FrameLine> = emptyList(),
    val transportLog: List<String> = emptyList(),
    val unknownFields: List<NamedReading> = emptyList(),
    /** Every field of the newest frame, including the typed ones, so the debug screen can show raw integers beside the scaled value. */
    val latestReadings: List<FieldReading> = emptyList(),
    val checksumOk: Boolean? = null,
    val decodeProblems: List<String> = emptyList(),
) {
    val range: RangeEstimate? get() = soc?.let { RangeEstimator().estimate(it, recent5Min, battery) }

    val hasData: Boolean get() = latest != null

    val criticalAlarm: ActiveAlarm? get() = alarms.firstOrNull { it.severity == AlarmSeverity.CRITICAL }
}

/**
 * Wires transport to decoding to estimation to storage.
 *
 * This is deliberately the only orchestration class that touches Android types, so every
 * calculation feeding it stays unit-testable and the same decode path can be replayed offline over
 * the captured frames.
 */
class MonitorEngine(
    context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    private val db: AppDatabase,
) {
    private val _snapshot = MutableStateFlow(MonitorSnapshot())
    val snapshot: StateFlow<MonitorSnapshot> = _snapshot.asStateFlow()

    private var spec: ProtocolSpec = WsBlePresets.WS_BLE_18B
    private var decoder = FrameDecoder(spec)
    private var battery = BatteryConfig()

    val transport = WsBleTransport(context, scope) { spec }

    private val emfModel = EmfModel()
    private val inferer = DirectionInferer()
    private val alarmEngine = AlarmEngine()
    private val rollingStats = RollingStats()
    private var socEstimator = SocEstimator(OcvTable.LEAD_ACID_2V, emfModel)
    private val rangeEstimator = RangeEstimator()

    private val chart = ArrayDeque<ChartPoint>()
    private val frameLog = ArrayDeque<FrameLine>()
    private val transportLog = ArrayDeque<String>()
    private val sampleBuffer = mutableListOf<SampleEntity>()
    private val rawBuffer = mutableListOf<RawFrameEntity>()

    private var engineJob: Job? = null
    private var loggingEnabled = true
    private var rawCapture = false

    private var sessionMonotonicBase = 0L
    private var sessionWallBase = 0L
    private var lastSampleMonotonic = 0L
    private var lastDataMonotonic = 0L
    private var totals = SessionTotals()

    @Volatile
    var sessionId: Long = 0L
        private set

    fun start() {
        if (engineJob?.isActive == true) return
        engineJob = scope.launch {
            launch { observeSettings() }
            launch { observeTransport() }
            launch { observeLink() }
            launch { flushLoop() }
            transport.start()
        }
    }

    fun stop() {
        transport.stop()
        engineJob?.cancel()
        engineJob = null
        scope.launch { drainBuffers(); closeSession("手动停止") }
    }

    private suspend fun observeSettings() {
        settings.settings.collect { s ->
            spec = s.protocol
            decoder = FrameDecoder(spec)
            battery = s.battery
            rawCapture = s.rawFrameCapture
            loggingEnabled = s.loggingEnabled
            socEstimator = SocEstimator(
                OcvTable.forChemistry(s.battery.chemistry),
                emfModel,
                (s.manualSocPercent ?: DEFAULT_INITIAL_SOC) / 100.0,
            )
            transport.configure(s.deviceAddress, s.pollIntervalMs, s.responseTimeoutMs, s.autoReconnect)
            _snapshot.value = _snapshot.value.copy(protocol = spec, battery = s.battery)
        }
    }

    private suspend fun observeTransport() {
        transport.events.collect { event ->
            when (event) {
                is TransportEvent.RxFrame -> handleRx(event)
                is TransportEvent.TxFrame -> {
                    pushFrame(FrameLine(System.currentTimeMillis(), true, TransportEvent.hex(event.bytes), "TX"))
                    if (rawCapture && sessionId != 0L) {
                        rawBuffer += RawFrameEntity(sessionId = sessionId, wallClockMs = System.currentTimeMillis(), outbound = true, hex = TransportEvent.hex(event.bytes))
                    }
                    publish()
                }
                is TransportEvent.Log -> {
                    transportLog.addLast(event.line)
                    while (transportLog.size > MAX_LOG_LINES) transportLog.removeFirst()
                    publish()
                }
            }
        }
    }

    private suspend fun observeLink() {
        transport.status.collect { status ->
            _snapshot.value = _snapshot.value.copy(link = status)
            if (status.phase != LinkPhase.POLLING && sessionId != 0L &&
                lastDataMonotonic > 0 && SystemClock.elapsedRealtime() - lastDataMonotonic > SESSION_IDLE_MS
            ) {
                closeSession("链路中断")
            }
        }
    }

    private suspend fun handleRx(event: TransportEvent.RxFrame) {
        pushFrame(FrameLine(event.wallClockMs, false, TransportEvent.hex(event.bytes), "RX"))
        val result = decoder.decode(event.bytes)
        when (result.status) {
            FrameStatus.DATA -> onDataFrame(event, result)
            FrameStatus.READY -> {
                transportLog.addLast("就绪帧 ${TransportEvent.hex(event.bytes)}")
                while (transportLog.size > MAX_LOG_LINES) transportLog.removeFirst()
                publish()
            }
            FrameStatus.UNKNOWN_FRAME -> {
                transportLog.addLast("未识别 len=${event.bytes.size} ${TransportEvent.hex(event.bytes)}")
                while (transportLog.size > MAX_LOG_LINES) transportLog.removeFirst()
                publish()
            }
            FrameStatus.TOO_SHORT -> Unit
        }
    }

    private suspend fun onDataFrame(event: TransportEvent.RxFrame, result: FrameDecodeResult) {
        val current = result.byRole(FieldRole.CURRENT).firstOrNull() ?: return
        val voltage = result.byRole(FieldRole.VOLTAGE).firstOrNull() ?: return
        if (voltage.value <= 0.0) return
        ensureSession(event.wallClockMs, event.monotonicMs)

        val magnitude = abs(current.value)
        val resistance = battery.internalResistanceOhm ?: emfModel.resistanceFor(null)
        val inferred = if (spec.currentSignMode == CurrentSignMode.SIGNED) {
            val dir = when {
                magnitude < battery.idleDeadbandA -> PowerDirection.IDLE
                current.value > 0 -> PowerDirection.CHARGE
                else -> PowerDirection.DISCHARGE
            }
            DirectionInferer.Inferred(current.value, dir, 1.0, voltage.value - current.value * resistance)
        } else {
            inferer.infer(magnitude, voltage.value, resistance, battery.idleDeadbandA, event.monotonicMs)
        }

        val sample = BatterySample(
            wallClockMs = event.wallClockMs,
            monotonicMs = event.monotonicMs,
            rawCurrentA = current.value,
            currentA = inferred.currentA,
            voltageV = voltage.value,
            direction = inferred.direction,
            temperatures = result.byRole(FieldRole.TEMPERATURE)
                .map { TemperatureReading(it.key, it.label, it.value) },
            deviceSocPercent = result.byRole(FieldRole.SOC).firstOrNull()?.value,
            unknowns = result.byRole(FieldRole.UNKNOWN)
                .map { NamedReading(it.key, it.label, it.value, it.unit) },
            frameHex = result.frameHex,
        )

        emfModel.add(sample.currentA, sample.voltageV, sample.monotonicMs)
        val fit = emfModel.fit()
        val soc = socEstimator.update(sample, battery, resistance)
        rollingStats.add(sample)
        val stats = rollingStats.summary(sample.monotonicMs)
        val alarms = alarmEngine.evaluate(
            sample = sample,
            config = battery,
            soc = soc,
            fit = fit,
            linkDeadMs = if (lastDataMonotonic == 0L) 0L else event.monotonicMs - lastDataMonotonic,
        )

        totals = accumulate(totals, sample, event.monotonicMs)
        lastDataMonotonic = event.monotonicMs

        chart.addLast(
            ChartPoint(
                elapsedMs = event.monotonicMs - sessionMonotonicBase,
                voltageV = sample.voltageV,
                currentA = sample.currentA,
                powerW = sample.powerW,
                socPercent = soc.socPercent,
            ),
        )
        while (chart.size > MAX_CHART_POINTS) chart.removeFirst()

        if (rawCapture && sessionId != 0L) {
            rawBuffer += RawFrameEntity(sessionId = sessionId, wallClockMs = event.wallClockMs, outbound = false, hex = result.frameHex)
        }
        if (loggingEnabled && sessionId != 0L) {
            sampleBuffer += sample.toEntity(sessionId, event.monotonicMs - sessionMonotonicBase, soc.socPercent)
        }

        _snapshot.value = _snapshot.value.copy(
            link = transport.status.value,
            latest = sample,
            soc = soc,
            recent5Min = stats,
            sessionTotals = totals,
            alarms = alarms,
            chart = chart.toList(),
            fit = fit,
            directionConfidence = inferred.confidence,
            estimatedEmfV = inferred.emfV,
            unknownFields = sample.unknowns,
            latestReadings = result.fields,
            checksumOk = result.checksumOk,
            decodeProblems = result.problems,
            frameLog = frameLog.toList(),
            transportLog = transportLog.toList(),
        )
    }

    private fun accumulate(prev: SessionTotals, sample: BatterySample, monotonicMs: Long): SessionTotals {
        val dtSec = if (lastSampleMonotonic == 0L) 0.0
        else ((monotonicMs - lastSampleMonotonic) / 1000.0).coerceIn(0.0, 20.0)
        lastSampleMonotonic = monotonicMs
        val dAh = sample.currentA * dtSec / 3600.0
        val dWh = sample.powerW * dtSec / 3600.0
        return prev.copy(
            sessionId = sessionId,
            startedAtMs = sessionWallBase,
            elapsedMs = monotonicMs - sessionMonotonicBase,
            sampleCount = prev.sampleCount + 1,
            dischargeAh = if (dAh < 0) prev.dischargeAh - dAh else prev.dischargeAh,
            recoveredAh = if (dAh > 0) prev.recoveredAh + dAh else prev.recoveredAh,
            dischargeWh = if (dWh < 0) prev.dischargeWh - dWh else prev.dischargeWh,
            recoveredWh = if (dWh > 0) prev.recoveredWh + dWh else prev.recoveredWh,
            minVoltageV = minOf(prev.minVoltageV, sample.voltageV),
            maxCurrentA = maxOf(prev.maxCurrentA, abs(sample.currentA)),
        )
    }

    private suspend fun ensureSession(wallClockMs: Long, monotonicMs: Long) {
        if (sessionId != 0L) return
        sessionMonotonicBase = monotonicMs
        sessionWallBase = wallClockMs
        lastSampleMonotonic = 0L
        totals = SessionTotals(startedAtMs = wallClockMs)
        val id = db.sessionDao().insert(
            RideSessionEntity(
                startedAtMs = wallClockMs,
                protocolId = spec.id,
                deviceAddress = transport.status.value.deviceAddress,
                startSoc = socEstimator.state.socPercent,
            ),
        )
        sessionId = id
        totals = totals.copy(sessionId = id)
    }

    private suspend fun closeSession(reason: String) {
        val id = sessionId
        if (id == 0L) return
        sessionId = 0L
        drainBuffers()
        db.sessionDao().finish(
            id = id,
            endedAtMs = System.currentTimeMillis(),
            endSoc = socEstimator.state.socPercent,
            dischargeAh = totals.dischargeAh,
            recoveredAh = totals.recoveredAh,
            dischargeWh = totals.dischargeWh,
            recoveredWh = totals.recoveredWh,
            minVoltage = if (totals.minVoltageV == Double.MAX_VALUE) 0.0 else totals.minVoltageV,
            maxCurrent = totals.maxCurrentA,
            sampleCount = totals.sampleCount,
        )
        db.sessionDao().setNote(id, reason)
        totals = SessionTotals()
        chart.clear()
        rollingStats.reset()
        inferer.reset()
    }

    private suspend fun flushLoop() {
        while (isActive) {
            delay(FLUSH_INTERVAL_MS)
            drainBuffers()
        }
    }

    private suspend fun drainBuffers() {
        if (sampleBuffer.isNotEmpty()) {
            val batch = sampleBuffer.toList()
            sampleBuffer.clear()
            runCatching { db.sampleDao().insertAll(batch) }
                .onFailure { transportLog.addLast("写库失败: ${it.message}") }
        }
        if (rawBuffer.isNotEmpty()) {
            val batch = rawBuffer.toList()
            rawBuffer.clear()
            runCatching { db.rawFrameDao().insertAll(batch) }
        }
    }

    private fun pushFrame(line: FrameLine) {
        frameLog.addLast(line)
        while (frameLog.size > MAX_FRAME_LINES) frameLog.removeFirst()
    }

    private fun publish() {
        _snapshot.value = _snapshot.value.copy(
            link = transport.status.value,
            chart = chart.toList(),
            frameLog = frameLog.toList(),
            transportLog = transportLog.toList(),
        )
    }

    /** Applies the operator's known state of charge, e.g. right after a confirmed full charge. */
    fun recalibrateSoc(percent: Double) {
        socEstimator.forceSet(percent / 100.0)
        _snapshot.value = _snapshot.value.copy(soc = socEstimator.state)
    }

    fun resetSession() {
        scope.launch { closeSession("用户结束会话") }
    }

    fun clearAlarms() {
        alarmEngine.reset()
        _snapshot.value = _snapshot.value.copy(alarms = emptyList())
    }

    fun estimateRange(soc: SocState, stats: StatsSummary): RangeEstimate = rangeEstimator.estimate(soc, stats, battery)

    companion object {
        const val MAX_CHART_POINTS = 240
        const val MAX_FRAME_LINES = 300
        const val MAX_LOG_LINES = 200
        const val FLUSH_INTERVAL_MS = 5_000L
        const val SESSION_IDLE_MS = 60_000L
        const val DEFAULT_INITIAL_SOC = 50.0
    }
}

private fun BatterySample.toEntity(sessionId: Long, elapsedMs: Long, socPercent: Double) = SampleEntity(
    sessionId = sessionId,
    wallClockMs = wallClockMs,
    elapsedMs = elapsedMs,
    rawCurrentA = rawCurrentA,
    currentA = currentA,
    voltageV = voltageV,
    powerW = powerW,
    direction = direction.name,
    tempC = maxTemperatureC,
    deviceSoc = deviceSocPercent,
    socPercent = socPercent,
    frameHex = frameHex,
)
