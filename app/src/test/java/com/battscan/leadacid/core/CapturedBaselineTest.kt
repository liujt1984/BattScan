package com.battscan.leadacid.core

import com.battscan.leadacid.core.algo.DirectionInferer
import com.battscan.leadacid.core.algo.EmfModel
import com.battscan.leadacid.core.algo.OcvTable
import com.battscan.leadacid.core.algo.SocEstimator
import com.battscan.leadacid.core.model.BatteryConfig
import com.battscan.leadacid.core.model.PowerDirection
import com.battscan.leadacid.core.protocol.ChecksumMode
import com.battscan.leadacid.core.protocol.FieldRole
import com.battscan.leadacid.core.protocol.FrameDecoder
import com.battscan.leadacid.core.protocol.WsBlePresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Replay of the 2026-10-28 WS-BLE capture (150 frames, device 2B:E0:3D:AE:5C:BA, service 0xFFF0).
 *
 * This is the regression that protects the analysis conclusions, not just the code: the decode
 * scaling, the dV/dI contradiction, and the reason b12 is *not* wired up as the state of charge.
 * If someone later hardcodes a field mapping that disagrees with the air traffic, this file fails.
 */
class CapturedBaselineTest {

    private data class Frame(
        val timeS: Double,
        val currentA: Double,
        val voltageV: Double,
        val b08: Double,
        val b10: Double,
        val b12: Double,
        val checksum: Int,
    )

    private val frames: List<Frame> by lazy {
        val stream = checkNotNull(
            javaClass.classLoader.getResourceAsStream("capture_20261028.csv"),
        ) { "the captured frames must stay on the test classpath" }
        BufferedReader(InputStreamReader(stream)).use { reader ->
            reader.readLines()
                .drop(1)
                .filter { it.isNotBlank() }
                .map { line ->
                    val p = line.split(",")
                    Frame(
                        timeS = p[0].toDouble(),
                        currentA = p[1].toDouble(),
                        voltageV = p[2].toDouble(),
                        b08 = p[3].toDouble(),
                        b10 = p[4].toDouble(),
                        b12 = p[5].toDouble(),
                        checksum = p[6].toInt(),
                    )
                }
        }
    }

    private fun monotonicMs(frame: Frame) = (frame.timeS * 1000).toLong()

    /**
     * Rebuilds the 18 bytes that were on the air from the decoded columns.
     *
     * The report fixed b0/b1 as the frame magic, b2-b3 at 0D D1, b9 at 04, b11 at 01 and b13-b16 at
     * 02 06 C4 09 as constants over all 150 frames, so the only per-frame bytes are the current,
     * voltage, b8, b10, b12 and the private checksum - all of which the fixture carries.
     */
    private fun capturedBytes(frame: Frame): ByteArray {
        val currentRaw = (frame.currentA * 100).roundToLong()
        val voltageRaw = (frame.voltageV * 100).roundToLong()
        return byteArrayOf(
            0x01, 0x08, 0x0D, 0xD1,
            (currentRaw and 0xFF).toByte(), ((currentRaw shr 8) and 0xFF).toByte(),
            (voltageRaw and 0xFF).toByte(), ((voltageRaw shr 8) and 0xFF).toByte(),
            (frame.b08 * 10).roundToLong().toByte(), 0x04,
            frame.b10.roundToInt().toByte(), 0x01,
            frame.b12.roundToInt().toByte(),
            0x02, 0x06, 0xC4, 0x09,
            frame.checksum.toByte(),
        )
    }

    @Test
    fun theFixtureIsTheCompleteCapture() {
        assertEquals(150, frames.size)
        // The two sessions in the HCI log, with the 26.6 s hole after the first 0x08 disconnect.
        assertTrue(frames.first().timeS < 4.0)
        assertTrue(frames.last().timeS > 211.0)
        val longestGap = frames.zipWithNext { a, b -> b.timeS - a.timeS }.max()
        assertTrue("longest gap ${longestGap}s", longestGap in 25.0..28.0)
    }

    @Test
    fun theReconstructedBytesAreTheFramesFromTheAir() {
        // If this disagrees with the golden vector, the constant-byte list above is wrong and every
        // checksum assertion below becomes meaningless.
        val expected = "01 08 0D D1 8A 03 FD 1C F4 04 1F 01 2E 02 06 C4 09 B0"
        assertEquals(expected, FrameDecoder(WsBlePresets.WS_BLE_18B).decode(capturedBytes(frames.first())).frameHex)
    }

    @Test
    fun capturedMeansMatchTheAnalysisReport() {
        val currents = frames.map { it.currentA }
        val voltages = frames.map { it.voltageV }

        assertEquals(9.307, currents.average(), 0.001)
        assertEquals(74.8405, voltages.average(), 0.001)
        assertEquals(7.29, currents.min(), 1e-9)
        assertEquals(11.17, currents.max(), 1e-9)
        assertEquals(71.13, voltages.min(), 1e-9)
        assertEquals(79.10, voltages.max(), 1e-9)
    }

    @Test
    fun theDecodeIsPhysicallyPlausibleForA72VPack() {
        // The scaling that justified trusting 0.01 A / 0.01 V: 521-884 W is a real e-bike load,
        // while a wrong byte pairing would land far outside that band.
        val powers = frames.map { it.currentA * it.voltageV }

        assertEquals(520.7, powers.min(), 0.1)
        assertEquals(883.5, powers.max(), 0.1)
        assertEquals(699.7, weightedPowerW(), 0.5)
    }

    /** Time-weighted average power over the covered gaps, as RollingStats computes it. */
    private fun weightedPowerW(): Double {
        var wattSeconds = 0.0
        var seconds = 0.0
        frames.zipWithNext().forEach { (a, b) ->
            val dt = b.timeS - a.timeS
            if (dt <= 0.0 || dt > 20.0) return@forEach
            seconds += dt
            wattSeconds += (a.currentA + b.currentA) / 2.0 * (a.voltageV + b.voltageV) / 2.0 * dt
        }
        return wattSeconds / seconds
    }

    @Test
    fun voltageRisesWithCurrentWhichIsTheOpenQuestion() {
        // The single most important number in the whole analysis: a discharging 72 V pack cannot
        // show +1.79 V per amp. Pinning it keeps the mapping question open until a known-工况
        // riding capture answers it.
        val model = EmfModel()
        frames.forEach { model.add(it.currentA, it.voltageV, monotonicMs(it)) }

        val fit = model.fit()
        assertTrue(fit.trustworthy)
        assertEquals(1.7933, fit.slopeOhm, 0.005)
        assertEquals(58.15, fit.interceptV, 0.2)
        assertEquals(0.9377, fit.correlation, 0.005)
        assertTrue(fit.verdict().contains("无符号幅值"))
        // ...and the guard must refuse to adopt it as the pack resistance.
        assertEquals(EmfModel.DEFAULT_RESISTANCE_OHM, model.resistanceFor(null), 1e-9)
    }

    @Test
    fun thePresetDecodesEveryCapturedFrameFromTheAir() {
        val decoder = FrameDecoder(WsBlePresets.WS_BLE_18B)

        frames.forEach { frame ->
            val result = decoder.decode(capturedBytes(frame))

            assertTrue("frame at ${frame.timeS}s failed to decode", result.ok)
            assertEquals(frame.currentA, result.byRole(FieldRole.CURRENT).first().value, 0.005)
            assertEquals(frame.voltageV, result.byRole(FieldRole.VOLTAGE).first().value, 0.005)
            assertEquals(frame.b08, result.byKey("b08")?.value ?: -1.0, 0.005)
            assertEquals(frame.b10, result.byRole(FieldRole.TEMPERATURE).first().value, 0.005)
            assertEquals(frame.b12, result.byKey("b12")?.value ?: -1.0, 1e-9)
            assertEquals(3537.0, result.byKey("b02_03")?.value ?: -1.0, 1e-9)
            assertEquals(0x0206C409.toLong(), result.byKey("b13_16")?.rawLong ?: -1L)
        }
    }

    @Test
    fun theCapturedChecksumByteMatchesNoStandardAlgorithm() {
        // The report's negative result, kept as an executable statement: b17 is a private checksum,
        // so the preset must leave ChecksumMode OFF or every frame would be rejected on the wire.
        // A byte that agrees with a real algorithm agrees on all 150 frames; anything else is
        // coincidence, and a 1-in-256 guess hits roughly once per 150 frames.
        val payload = frames.map { capturedBytes(it).copyOfRange(0, 17) }
        val actual = frames.map { it.checksum }

        ChecksumMode.entries
            .filter { it != ChecksumMode.OFF }
            .forEach { mode ->
                val hits = payload.zip(actual).count { (bytes, expected) ->
                    FrameDecoder.checksumOf(mode, bytes) == expected
                }
                assertTrue("$mode matched only $hits of 150", hits < 10)
            }
        assertEquals(ChecksumMode.OFF, WsBlePresets.WS_BLE_18B.checksumMode)
        assertNull(FrameDecoder(WsBlePresets.WS_BLE_18B).decode(capturedBytes(frames.first())).checksumOk)
    }

    @Test
    fun b12CannotBeTheStateOfCharge() {
        // b12 falls 46 -> 32 (14 units) while only 0.469 Ah actually moved. Taken as SOC percent
        // that implies a 3.35 Ah pack, against the 20 Ah label - so it stays an UNKNOWN field.
        val ampHours = trapezoidAmpHours()
        val drop = frames.first().b12 - frames.last().b12

        assertEquals(0.469, ampHours, 0.005)
        assertEquals(14.0, drop, 1e-9)
        assertEquals(3.35, ampHours / (drop / 100.0), 0.05)
        assertTrue(ampHours / (drop / 100.0) < 10.0)
        assertNull(WsBlePresets.WS_BLE_18B.fieldByRole(FieldRole.SOC))
    }

    /** Amp-hours on the wire, integrating each gap as the mean of the frames around it. */
    private fun trapezoidAmpHours(): Double {
        var ampHours = 0.0
        frames.zipWithNext().forEach { (a, b) ->
            val dt = b.timeS - a.timeS
            if (dt <= 0.0 || dt > 20.0) return@forEach
            ampHours += (a.currentA + b.currentA) / 2.0 * dt / 3600.0
        }
        return ampHours
    }

    @Test
    fun b08AndB12MoveTogetherWhileB10BehavesLikeATemperature() {
        // b8 (24.4 -> 21.1) and b12 (46 -> 32) are one slow quantity seen twice; b10 rises with the
        // electronics and ignores the load, which is why only b10 is registered as a temperature.
        val slowCorrelation = correlation(frames.map { it.b08 }, frames.map { it.b12 })
        val tempAgainstCurrent = correlation(frames.map { it.b10 }, frames.map { it.currentA })

        assertTrue("slow pair r=$slowCorrelation", slowCorrelation > 0.90)
        assertTrue("b10 vs current r=$tempAgainstCurrent", abs(tempAgainstCurrent) < 0.25)
        assertEquals("b10", WsBlePresets.WS_BLE_18B.fields.first { it.role == FieldRole.TEMPERATURE }.key)
    }

    @Test
    fun replayingTheCaptureThroughTheInfererSplitsChargeFromDischarge() {
        val inferer = DirectionInferer()
        var discharge = 0
        var charge = 0
        var idle = 0
        var lastEmf = 0.0

        frames.forEach { frame ->
            val inferred = inferer.infer(
                magnitudeA = frame.currentA,
                voltageV = frame.voltageV,
                resistanceOhm = EmfModel.DEFAULT_RESISTANCE_OHM,
                deadbandA = BatteryConfig().idleDeadbandA,
                monotonicMs = monotonicMs(frame),
            )
            when (inferred.direction) {
                PowerDirection.DISCHARGE -> discharge++
                PowerDirection.CHARGE -> charge++
                else -> idle++
            }
            lastEmf = inferred.emfV
        }

        // A regen-equipped ride should read as mostly discharge with real braking segments, not as
        // one flat sign. The exact split is pinned so a sign-convention flip shows up immediately.
        assertEquals(84, discharge)
        assertEquals(66, charge)
        assertEquals(0, idle)
        assertTrue("emf $lastEmf", lastEmf in 74.0..75.5)
    }

    @Test
    fun replayingTheCaptureThroughTheSocEstimatorStaysInsideTheEnergyBudget() {
        val config = BatteryConfig(ratedCapacityAh = 20.0, internalResistanceOhm = EmfModel.DEFAULT_RESISTANCE_OHM)
        val emfModel = EmfModel()
        val estimator = SocEstimator(OcvTable.LEAD_ACID_2V, emfModel, 0.80)
        val inferer = DirectionInferer()
        var state = estimator.state

        frames.forEach { frame ->
            val inferred = inferer.infer(
                frame.currentA, frame.voltageV,
                EmfModel.DEFAULT_RESISTANCE_OHM, config.idleDeadbandA, monotonicMs(frame),
            )
            val sample = Samples.of(monotonicMs(frame), inferred.currentA, frame.voltageV, direction = inferred.direction)
            emfModel.add(sample.currentA, sample.voltageV, sample.monotonicMs)
            state = estimator.update(sample, config, EmfModel.DEFAULT_RESISTANCE_OHM)
        }

        assertEquals(0.2739, state.dischargedAh, 0.0005)
        assertEquals(0.1937, state.recoveredAh, 0.0005)

        // The conservation law behind those two numbers: however the inferer splits the sign, the
        // counter may only move the amp-hours that were actually on the wire. Recomputed with the
        // integrator's own rule (later frame's current, gaps clamped to SocEstimator.MAX_STEP_SEC),
        // which is why it exceeds the 0.469 Ah trapezoid across the 26.6 s disconnect hole.
        val rawAhOnWire = frames.zipWithNext().sumOf { (a, b) ->
            b.currentA * (b.timeS - a.timeS).coerceIn(0.0, SocEstimator.MAX_STEP_SEC) / 3600.0
        }
        assertEquals(rawAhOnWire, state.dischargedAh + state.recoveredAh / config.chargeEfficiency, 1e-9)

        // Two minutes at 9 A out of a 20 Ah pack is a few percent of SOC, and the pack started here
        // at 80 %, so a replay that claimed a double-digit drop would mean the sign split leaked.
        assertEquals(79.61, state.socPercent, 0.5)
        assertTrue("soc ${state.socPercent}", state.socPercent > 70.0)
    }

    private fun correlation(xs: List<Double>, ys: List<Double>): Double {
        val mx = xs.average()
        val my = ys.average()
        var sxy = 0.0
        var sxx = 0.0
        var syy = 0.0
        xs.zip(ys).forEach { (x, y) ->
            sxy += (x - mx) * (y - my)
            sxx += (x - mx) * (x - mx)
            syy += (y - my) * (y - my)
        }
        val denom = sqrt(sxx * syy)
        return if (denom < 1e-9) 0.0 else sxy / denom
    }
}
