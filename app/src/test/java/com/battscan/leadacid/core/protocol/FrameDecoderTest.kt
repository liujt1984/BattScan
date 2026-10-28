package com.battscan.leadacid.core.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The golden vector is frame #1 of the 2026-10-28 WS-BLE capture, whose decoded values were checked
 * against the physical pack (9.06 A / 74.21 V). If this test fails, the byte layout no longer
 * matches what was actually on the air.
 */
class FrameDecoderTest {

    private val goldenFrame = intArrayOf(
        0x01, 0x08, 0x0D, 0xD1, 0x8A, 0x03, 0xFD, 0x1C, 0xF4, 0x04,
        0x1F, 0x01, 0x2E, 0x02, 0x06, 0xC4, 0x09, 0xB0,
    ).map { it.toByte() }.toByteArray()

    private fun bytesOf(vararg values: Int) = values.map { it.toByte() }.toByteArray()

    @Test
    fun decodesTheCapturedGoldenFrame() {
        val result = FrameDecoder(WsBlePresets.WS_BLE_18B).decode(goldenFrame)

        assertTrue(result.ok)
        assertEquals(9.06, result.byKey("current")!!.value, 1e-9)
        assertEquals(74.21, result.byKey("voltage")!!.value, 1e-9)
        assertEquals(3537L, result.byKey("b02_03")!!.rawLong)
        assertEquals(24.4, result.byKey("b08")!!.value, 1e-9)
        assertEquals(31.0, result.byKey("b10")!!.value, 1e-9)
        assertEquals(46.0, result.byKey("b12")!!.value, 1e-9)
        assertEquals(0x0206C409L, result.byKey("b13_16")!!.rawLong)
        assertTrue(result.problems.isEmpty())
    }

    @Test
    fun roleLookupSeparatesTypedFieldsFromUnknowns() {
        val result = FrameDecoder(WsBlePresets.WS_BLE_18B).decode(goldenFrame)

        assertEquals(1, result.byRole(FieldRole.CURRENT).size)
        assertEquals(1, result.byRole(FieldRole.VOLTAGE).size)
        assertEquals(listOf("b10"), result.byRole(FieldRole.TEMPERATURE).map { it.key })
        assertEquals(listOf("b02_03", "b08", "b12", "b13_16"), result.byRole(FieldRole.UNKNOWN).map { it.key })
        assertNull(result.byRole(FieldRole.SOC).firstOrNull())
    }

    @Test
    fun classifiesReadyShortAndOversizedFrames() {
        val decoder = FrameDecoder(WsBlePresets.WS_BLE_18B)

        // Anything shorter than a data frame is a status indication; the capture only ever sent
        // `01 05 00`, so a 3-byte frame with other content is still reported as READY rather than
        // dropped, which keeps it visible on the raw-frame screen.
        assertEquals(FrameStatus.READY, decoder.classify(bytesOf(0x01, 0x05, 0x00)))
        assertEquals(FrameStatus.READY, decoder.classify(bytesOf(0x01, 0x09, 0x00)))
        assertEquals(FrameStatus.TOO_SHORT, decoder.classify(bytesOf(0x01)))
        assertEquals(FrameStatus.DATA, decoder.classify(goldenFrame))
        assertEquals(
            FrameStatus.UNKNOWN_FRAME,
            decoder.classify(goldenFrame.copyOf(20)),
            "a long frame with the right magic must not be silently accepted",
        )
    }

    @Test
    fun rejectsMagicMismatchAndWrongLengthInsteadOfGuessing() {
        val decoder = FrameDecoder(WsBlePresets.WS_BLE_18B)

        val mutated = goldenFrame.copyOf().also { it[1] = 0x09 }
        assertEquals(FrameStatus.UNKNOWN_FRAME, decoder.decode(mutated).status)
        assertEquals(listOf("magic 或长度不匹配"), decoder.decode(mutated).problems)
        assertFalse(decoder.decode(mutated).ok)
        // Oversized frames are rejected too: silently trusting a longer payload would hide a
        // firmware change in the field layout.
        assertEquals(FrameStatus.UNKNOWN_FRAME, decoder.decode(goldenFrame.copyOf(20)).status)
    }

    @Test
    fun signedPresetReadsTheHighBitAsRegen() {
        val negative = goldenFrame.copyOf().also {
            // -10.00 A is raw -1000 = 0xFB18, stored little-endian as 18 FB
            it[4] = 0x18
            it[5] = 0xFB.toByte()
        }

        assertEquals(-10.0, FrameDecoder(WsBlePresets.WS_BLE_18B_SIGNED).decode(negative).byKey("current")!!.value, 1e-9)
        // The magnitude preset reads the same bytes as 642.8 A - an impossible draw, which is
        // exactly how a wrong sign mode shows up on a real capture.
        assertEquals(642.80, FrameDecoder(WsBlePresets.WS_BLE_18B).decode(negative).byKey("current")!!.value, 1e-9)
    }

    @Test
    fun buildAndDecodeRoundTripsPhysicalValues() {
        val spec = WsBlePresets.WS_BLE_18B
        val decoder = FrameDecoder(spec)

        listOf(7.29 to 71.13, 10.00 to 76.18, 11.17 to 79.10).forEach { (current, voltage) ->
            val frame = FrameDecoder.buildFrame(spec, current, voltage, 31.0)
            val result = decoder.decode(frame)
            assertTrue(result.ok)
            assertEquals(current, result.byKey("current")!!.value, 0.005)
            assertEquals(voltage, result.byKey("voltage")!!.value, 0.005)
            assertEquals(31.0, result.byKey("b10")!!.value, 1e-9)
        }
    }

    @Test
    fun checksumModeValidatesAgainstTheSameAlgorithmItWrites() {
        val spec = WsBlePresets.WS_BLE_18B.copy(checksumMode = ChecksumMode.SUM8)
        val frame = FrameDecoder.buildFrame(spec, 9.06, 74.21, 31.0)

        assertTrue(FrameDecoder(spec).validateChecksum(frame))
        frame[frame.size - 1] = (frame[frame.size - 1] + 1).toByte()
        assertFalse(FrameDecoder(spec).validateChecksum(frame))
    }

    @Test
    fun unknownChecksumOffsetMeansNoValidation() {
        val spec = WsBlePresets.WS_BLE_18B.copy(checksumMode = ChecksumMode.OFF)
        assertNull(FrameDecoder(spec).decode(goldenFrame).checksumOk)

        val sumSpec = spec.copy(checksumMode = ChecksumMode.XOR8, checksumOffset = null)
        assertTrue(FrameDecoder(sumSpec).validateChecksum(goldenFrame))
    }

    @Test
    fun reportsFieldOutOfBoundsAsAProblemWithoutDroppingTheFrame() {
        val spec = WsBlePresets.WS_BLE_18B.copy(
            fields = WsBlePresets.WS_BLE_18B.fields +
                FieldSpec("ghost", "越界字段", 17, 4, Endianness.LITTLE, 1.0, "", FieldRole.UNKNOWN),
        )
        val result = FrameDecoder(spec).decode(goldenFrame)

        assertTrue(result.ok)
        assertTrue(result.problems.isNotEmpty())
        assertTrue(result.problems.all { it.contains("越界") })
        // The readable fields still decode, so one bad row in the config cannot blank the dashboard.
        assertEquals(74.21, result.byKey("voltage")!!.value, 1e-9)
    }

    @Test
    fun hexRenderingIsStableForTheFrameLog() {
        val result = FrameDecoder(WsBlePresets.WS_BLE_18B).decode(goldenFrame)

        assertEquals("01 08 0D D1 8A 03 FD 1C F4 04 1F 01 2E 02 06 C4 09 B0", result.frameHex)
    }

    @Test
    fun protocolTextSurvivesAnEncodeDecodeRoundTrip() {
        val encoded = WsBlePresets.WS_BLE_18B.encode()
        val parsed = ProtocolSpec.decode(encoded)!!

        assertEquals(WsBlePresets.WS_BLE_18B.id, parsed.id)
        assertEquals(WsBlePresets.WS_BLE_18B.frameLength, parsed.frameLength)
        assertEquals(WsBlePresets.WS_BLE_18B.currentSignMode, parsed.currentSignMode)
        assertEquals(WsBlePresets.WS_BLE_18B.magic, parsed.magic)
        assertArrayEquals(
            WsBlePresets.WS_BLE_18B.pollCommand.map { it.toByte() }.toByteArray(),
            parsed.pollCommand.map { it.toByte() }.toByteArray(),
        )
        assertEquals(WsBlePresets.WS_BLE_18B.fields, parsed.fields)

        // The re-parsed spec must decode the captured frame identically, or the text format has
        // silently lost the mapping the calibration screen is editing.
        assertEquals(
            FrameDecoder(WsBlePresets.WS_BLE_18B).decode(goldenFrame).byKey("voltage")!!.value,
            FrameDecoder(parsed).decode(goldenFrame).byKey("voltage")!!.value,
            1e-9,
        )
    }

    @Test
    fun malformedProtocolTextIsRejectedNotHalfParsed() {
        assertNull(ProtocolSpec.decode("SPEC|a|b|18|OFF|17|MAGNITUDE|02 09 00"))
        assertNull(ProtocolSpec.decode("SPEC|a|b|x|OFF|17|MAGNITUDE|02 09 00|02 07|0=01"))
        assertNull(ProtocolSpec.decode("no spec line here"))
    }

    @Test
    fun highestByteReadDrivesTheOutOfRangeCheck() {
        assertEquals(18, WsBlePresets.WS_BLE_18B.highestByteRead)
    }
}
