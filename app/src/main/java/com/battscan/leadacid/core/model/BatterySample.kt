package com.battscan.leadacid.core.model

enum class PowerDirection {
    /** Current flows into the pack: throttle-off regen braking, or a plugged-in charger. */
    CHARGE,

    /** Current flows out of the pack: motoring. */
    DISCHARGE,

    /** |current| below the deadband, so the sign carries no usable information. */
    IDLE,

    /** Not enough EMF/resistance history yet to choose between charge and discharge. */
    UNKNOWN,
}

/**
 * One decoded uplink frame.
 *
 * [rawCurrentA] is what the frame literally says; [currentA] is the signed value after direction
 * inference, positive meaning into the pack. Keeping both is deliberate: the WS-BLE current field
 * is a magnitude, so every downstream number depends on an inference that must stay auditable.
 */
data class BatterySample(
    val wallClockMs: Long,
    val monotonicMs: Long,
    val rawCurrentA: Double,
    val currentA: Double,
    val voltageV: Double,
    val direction: PowerDirection,
    val temperatures: List<TemperatureReading>,
    val deviceSocPercent: Double?,
    val unknowns: List<NamedReading>,
    val frameHex: String,
) {
    val powerW: Double get() = currentA * voltageV

    val maxTemperatureC: Double? get() = temperatures.maxOfOrNull { it.valueC }

    fun named(key: String): Double? = unknowns.firstOrNull { it.key == key }?.value
}

data class TemperatureReading(val key: String, val label: String, val valueC: Double)

data class NamedReading(val key: String, val label: String, val value: Double, val unit: String)
