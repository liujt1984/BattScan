package com.battscan.leadacid.core.protocol

import com.battscan.leadacid.core.model.PowerDirection
import kotlin.math.roundToLong

enum class FrameStatus {
    /** A full data frame matching length and magic. */
    DATA,

    /** Short frame that only says the module is alive (WS-BLE sends `01 05 00`). */
    READY,

    /** Right length, wrong magic - kept so the debug screen can show it. */
    UNKNOWN_FRAME,

    TOO_SHORT,
}

data class FieldReading(
    val key: String,
    val label: String,
    val role: FieldRole,
    val unit: String,
    val value: Double,
    val rawLong: Long,
)

data class FrameDecodeResult(
    val status: FrameStatus,
    val fields: List<FieldReading>,
    val frameHex: String,
    val checksumOk: Boolean?,
    val problems: List<String>,
) {
    val ok: Boolean get() = status == FrameStatus.DATA
    fun byRole(role: FieldRole): List<FieldReading> = fields.filter { it.role == role }
    fun byKey(key: String): FieldReading? = fields.firstOrNull { it.key == key }
}

/**
 * Pure frame reader. No Android and no BLE types, so the same code that runs on the phone can be
 * replayed against the 150 captured frames in a JVM unit test.
 */
class FrameDecoder(private val spec: ProtocolSpec) {

    private fun magicOk(bytes: ByteArray): Boolean =
        spec.magic.all { (index, value) -> index < bytes.size && (bytes[index].toInt() and 0xFF) == value }

    fun classify(bytes: ByteArray): FrameStatus = when {
        bytes.size < 3 -> FrameStatus.TOO_SHORT
        bytes.size < spec.frameLength -> FrameStatus.READY
        bytes.size == spec.frameLength && magicOk(bytes) -> FrameStatus.DATA
        else -> FrameStatus.UNKNOWN_FRAME
    }

    fun decode(bytes: ByteArray): FrameDecodeResult {
        val problems = mutableListOf<String>()
        val hex = bytes.joinToString(" ") { "%02X".format(it) }
        if (bytes.size < spec.frameLength) {
            val status = if (bytes.size < 3) FrameStatus.TOO_SHORT else FrameStatus.READY
            return FrameDecodeResult(status, emptyList(), hex, null, emptyList())
        }
        if (bytes.size != spec.frameLength || !magicOk(bytes)) {
            return FrameDecodeResult(FrameStatus.UNKNOWN_FRAME, emptyList(), hex, null, listOf("magic 或长度不匹配"))
        }
        if (spec.highestByteRead > bytes.size) {
            problems += "字段越界: 需要 ${spec.highestByteRead} 字节, 实际 ${bytes.size}"
        }

        val readings = spec.fields.mapNotNull { field ->
            readField(bytes, field, problems)?.let { raw ->
                FieldReading(
                    key = field.key,
                    label = field.label,
                    role = field.role,
                    unit = field.unit,
                    value = raw * field.scale,
                    rawLong = raw,
                )
            }
        }
        val checksumOk = if (spec.checksumMode == ChecksumMode.OFF) {
            null
        } else {
            validateChecksum(bytes)
        }
        val status = if (readings.isEmpty()) FrameStatus.UNKNOWN_FRAME else FrameStatus.DATA
        return FrameDecodeResult(status, readings, hex, checksumOk, problems)
    }

    private fun readField(bytes: ByteArray, field: FieldSpec, problems: MutableList<String>): Long? {
        val end = field.offset + field.length
        if (field.length !in 1..8 || end > bytes.size) {
            problems += "${field.key}: 偏移 ${field.offset}+${field.length} 越界"
            return null
        }
        var acc = 0L
        when (field.endianness) {
            Endianness.LITTLE -> for (i in 0 until field.length) {
                acc = acc or ((bytes[field.offset + i].toLong() and 0xFF) shl (8 * i))
            }
            Endianness.BIG -> for (i in 0 until field.length) {
                acc = (acc shl 8) or (bytes[field.offset + i].toLong() and 0xFF)
            }
        }
        if (field.signed) {
            val bits = 8 * field.length
            val signBit = 1L shl (bits - 1)
            if (acc and signBit != 0L) acc -= (1L shl bits)
        }
        return acc
    }

    fun validateChecksum(bytes: ByteArray): Boolean {
        val at = spec.checksumOffset ?: return true
        if (at >= bytes.size) return false
        val expected = (bytes[at].toInt() and 0xFF)
        val payload = bytes.copyOfRange(0, at)
        val actual = checksumOf(spec.checksumMode, payload)
        return expected == actual
    }

    companion object {
        fun checksumOf(mode: ChecksumMode, payload: ByteArray): Int = when (mode) {
            ChecksumMode.OFF, ChecksumMode.SUM8 -> {
                var s = 0
                payload.forEach { s = (s + (it.toInt() and 0xFF)) and 0xFF }
                s
            }
            ChecksumMode.XOR8 -> {
                var x = 0
                payload.forEach { x = x xor (it.toInt() and 0xFF) }
                x
            }
            ChecksumMode.CRC8_POLY07 -> crc8(payload, 0x07, 0x00, false)
            ChecksumMode.CRC8_POLY31 -> crc8(payload, 0x31, 0x00, false)
        }

        private fun crc8(payload: ByteArray, poly: Int, init: Int, reflect: Boolean): Int {
            var crc = init
            payload.forEach { b ->
                crc = crc xor (b.toInt() and 0xFF)
                repeat(8) {
                    crc = if (crc and 0x80 != 0) ((crc shl 1) xor poly) and 0xFF else (crc shl 1) and 0xFF
                }
            }
            return if (reflect) Integer.reverse(crc shl 24).ushr(24) else crc
        }

        /**
         * Builds a frame from physical values. Used by round-trip tests and by the synthetic-data
         * mode in the calibration screen.
         */
        fun buildFrame(spec: ProtocolSpec, currentA: Double, voltageV: Double, tempC: Double): ByteArray {
            val bytes = ByteArray(spec.frameLength)
            spec.magic.forEach { (index, value) -> bytes[index] = value.toByte() }
            fun put(field: FieldSpec, value: Double) {
                val raw = (value / field.scale).roundToLong()
                for (i in 0 until field.length) {
                    val shift = if (field.endianness == Endianness.LITTLE) 8 * i else 8 * (field.length - 1 - i)
                    bytes[field.offset + i] = ((raw shr shift) and 0xFF).toByte()
                }
            }
            spec.fields.forEach { f ->
                when (f.role) {
                    FieldRole.CURRENT -> put(f, currentA)
                    FieldRole.VOLTAGE -> put(f, voltageV)
                    FieldRole.TEMPERATURE -> put(f, tempC)
                    else -> Unit
                }
            }
            spec.checksumOffset?.let { off ->
                if (spec.checksumMode != ChecksumMode.OFF) {
                    bytes[off] = checksumOf(spec.checksumMode, bytes.copyOfRange(0, off)).toByte()
                }
            }
            return bytes
        }

        fun signedCurrent(magnitudeA: Double, direction: PowerDirection): Double = when (direction) {
            PowerDirection.CHARGE -> magnitudeA
            PowerDirection.DISCHARGE -> -magnitudeA
            else -> 0.0
        }
    }
}
