package com.battscan.leadacid.core.protocol

/**
 * Byte order used when reading a multi-byte field out of a frame.
 */
enum class Endianness {
    LITTLE, BIG;

    companion object {
        fun parse(s: String): Endianness? = when (s.uppercase()) {
            "LE", "LITTLE" -> LITTLE
            "BE", "BIG" -> BIG
            else -> null
        }
    }
}

/**
 * What a decoded field physically means. Fields with a typed role are mapped onto
 * [com.battscan.leadacid.core.model.BatterySample]; everything else is kept as a labelled raw
 * number so the calibration screen can propose a new role for it.
 */
enum class FieldRole {
    CURRENT,
    VOLTAGE,
    TEMPERATURE,
    SOC,
    UNKNOWN;

    companion object {
        fun parse(s: String): FieldRole? = entries.firstOrNull { it.name.equals(s, ignoreCase = true) }
    }
}

/**
 * Whether the current field already carries a sign, or is a magnitude whose direction has to be
 * inferred. The WS-BLE capture only ever produced positive raw current, so MAGNITUDE is the safe
 * default and direction inference stays mandatory for regen riding.
 */
enum class CurrentSignMode {
    SIGNED,
    MAGNITUDE;

    companion object {
        fun parse(s: String): CurrentSignMode? = when (s.uppercase()) {
            "SIGNED" -> SIGNED
            "MAGNITUDE", "UNSIGNED" -> MAGNITUDE
            else -> null
        }
    }
}

/**
 * Checksum handling. The WS-BLE trailing byte matched no standard algorithm during the HCI
 * analysis, so OFF keeps the byte visible without pretending it can be validated.
 */
enum class ChecksumMode {
    OFF, SUM8, XOR8, CRC8_POLY07, CRC8_POLY31;

    companion object {
        fun parse(s: String): ChecksumMode? = entries.firstOrNull { it.name.equals(s, ignoreCase = true) }
    }
}

data class FieldSpec(
    val key: String,
    val label: String,
    val offset: Int,
    val length: Int,
    val endianness: Endianness,
    val scale: Double,
    val unit: String,
    val role: FieldRole,
    val signed: Boolean = false,
) {
    fun encode(): String = listOf(
        key, length.toString(), offset.toString(), endianness.name,
        scale.toString(), role.name, signed.toString(), unit, label,
    ).joinToString(",")

    companion object {
        fun decode(line: String): FieldSpec? {
            val p = line.split(",")
            if (p.size < 8) return null
            val endian = Endianness.parse(p[3]) ?: return null
            val role = FieldRole.parse(p[5]) ?: return null
            val scale = p[4].toDoubleOrNull() ?: return null
            return FieldSpec(
                key = p[0],
                length = p[1].toIntOrNull() ?: return null,
                offset = p[2].toIntOrNull() ?: return null,
                endianness = endian,
                scale = scale,
                role = role,
                signed = p[6].toBooleanStrictOrNull() ?: false,
                unit = p[7],
                label = if (p.size > 8) p[8] else p[0],
            )
        }
    }
}

/**
 * A complete, user-editable description of one uplink frame.
 *
 * Magic bytes are index/hex pairs that must match before a frame is decoded, so a short readiness
 * indication and a full data frame are separated by length plus magic rather than by guessing.
 */
data class ProtocolSpec(
    val id: String,
    val displayName: String,
    val frameLength: Int,
    val magic: List<Pair<Int, Int>>,
    val fields: List<FieldSpec>,
    val checksumMode: ChecksumMode,
    val checksumOffset: Int?,
    val currentSignMode: CurrentSignMode,
    val pollCommand: List<Int>,
    val sessionStartCommand: List<Int>,
) {
    val highestByteRead: Int
        get() = (fields.map { it.offset + it.length } + listOfNotNull(checksumOffset?.plus(1))).maxOrNull() ?: 0

    fun fieldByRole(role: FieldRole): FieldSpec? = fields.firstOrNull { it.role == role }

    fun encode(): String = buildString {
        append("SPEC|").append(id).append('|').append(displayName).append('|')
        append(frameLength).append('|').append(checksumMode.name).append('|')
        append(checksumOffset?.toString() ?: "-1").append('|')
        append(currentSignMode.name).append('|')
        append(pollCommand.joinToString(" ") { "%02X".format(it) }).append('|')
        append(sessionStartCommand.joinToString(" ") { "%02X".format(it) }).append('|')
        append(magic.joinToString(" ") { "${it.first}=%02X".format(it.second) })
        fields.forEach { f -> append("\nFIELD|").append(f.encode()) }
    }

    companion object {
        /** `SPEC|id|name|frameLength|checksumMode|checksumOffset|signMode|poll|sessionStart|magic` */
        fun decode(text: String): ProtocolSpec? {
            val head = text.lines().firstOrNull { it.startsWith("SPEC|") } ?: return null
            val p = head.split("|")
            if (p.size < 10) return null
            val fields = text.lines()
                .filter { it.startsWith("FIELD|") }
                .mapNotNull { FieldSpec.decode(it.substringAfter("FIELD|")) }
            if (fields.isEmpty()) return null
            return ProtocolSpec(
                id = p[1],
                displayName = p[2],
                frameLength = p[3].toIntOrNull() ?: return null,
                checksumMode = ChecksumMode.parse(p[4]) ?: ChecksumMode.OFF,
                checksumOffset = p[5].toIntOrNull()?.takeIf { it >= 0 },
                currentSignMode = CurrentSignMode.parse(p[6]) ?: CurrentSignMode.MAGNITUDE,
                pollCommand = parseHex(p[7]),
                sessionStartCommand = parseHex(p[8]),
                magic = parseMagic(p[9]),
                fields = fields,
            )
        }

        private fun parseMagic(s: String): List<Pair<Int, Int>> =
            s.trim().split(" ").filter { it.contains("=") }.mapNotNull { part ->
                val kv = part.split("=")
                val index = kv[0].toIntOrNull() ?: return@mapNotNull null
                val value = kv[1].toIntOrNull(16) ?: return@mapNotNull null
                index to value
            }

        fun parseHex(s: String): List<Int> =
            s.trim().split(" ", ",").filter { it.isNotBlank() }.mapNotNull { it.toIntOrNull(16) }
    }
}
