package com.battscan.leadacid.core.protocol

/**
 * Layout derived from the 2026-10-28 WS-BLE capture (150 decoded frames, service 0xFFF0).
 *
 * Only the current and voltage byte pairs are confirmed by magnitude sanity against a real 72 V
 * bike (521-884 W). b8/b12 are a slow unproven quantity and b2-b3 plus b13-b16 are constant, so
 * they are registered as UNKNOWN for the calibration screen to resolve instead of being guessed.
 */
object WsBlePresets {

    val WS_BLE_18B = ProtocolSpec(
        id = "wsble18",
        displayName = "WS-BLE 0xFFF0 (抓包默认)",
        frameLength = 18,
        magic = listOf(0 to 0x01, 1 to 0x08),
        checksumMode = ChecksumMode.OFF,
        checksumOffset = 17,
        currentSignMode = CurrentSignMode.MAGNITUDE,
        pollCommand = listOf(0x02, 0x09, 0x00),
        sessionStartCommand = listOf(0x02, 0x07, 0x04, 0x00, 0x00, 0x00, 0x00),
        fields = listOf(
            FieldSpec("b02_03", "b2-b3 恒定 3537", 2, 2, Endianness.BIG, 1.0, "", FieldRole.UNKNOWN),
            FieldSpec("current", "电流", 4, 2, Endianness.LITTLE, 0.01, "A", FieldRole.CURRENT),
            FieldSpec("voltage", "总电压", 6, 2, Endianness.LITTLE, 0.01, "V", FieldRole.VOLTAGE),
            FieldSpec("b08", "b8 缓降 24.4→21.1", 8, 1, Endianness.LITTLE, 0.1, "?", FieldRole.UNKNOWN),
            FieldSpec("b10", "b10 温度?", 10, 1, Endianness.LITTLE, 1.0, "°C", FieldRole.TEMPERATURE),
            FieldSpec("b12", "b12 缓降 46→32", 12, 1, Endianness.LITTLE, 1.0, "?", FieldRole.UNKNOWN),
            FieldSpec("b13_16", "b13-b16 保留", 13, 4, Endianness.BIG, 1.0, "", FieldRole.UNKNOWN),
        ),
    )

    /**
     * Same frame, current reinterpreted as a signed int16. Useful when a riding capture proves the
     * module does encode direction in the two's-complement high bit.
     */
    val WS_BLE_18B_SIGNED = WS_BLE_18B.copy(
        id = "wsble18s",
        displayName = "WS-BLE 18B（电流按有符号）",
        currentSignMode = CurrentSignMode.SIGNED,
        fields = WS_BLE_18B.fields.map {
            if (it.role == FieldRole.CURRENT) it.copy(signed = true) else it
        },
    )

    val all: List<ProtocolSpec> = listOf(WS_BLE_18B, WS_BLE_18B_SIGNED)
}
