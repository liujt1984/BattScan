package com.battscan.leadacid.core.algo

import com.battscan.leadacid.core.model.BatteryConfig
import com.battscan.leadacid.core.model.Chemistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OCV interpolation plus the Peukert and temperature derating the SOC integrator divides by.
 */
class OcvAndCapacityTest {

    private val leadAcid = OcvTable.LEAD_ACID_2V

    @Test
    fun leadAcidTableIsMonotonicAndClampedAtBothEnds() {
        assertEquals(0.0, leadAcid.socFor(1.910), 1e-9)
        assertEquals(1.0, leadAcid.socFor(2.125), 1e-9)
        // A nonsense reading must not produce a negative or above-full SOC.
        assertEquals(0.0, leadAcid.socFor(1.20), 1e-9)
        assertEquals(1.0, leadAcid.socFor(3.00), 1e-9)

        val sweep = (0..50).map { leadAcid.socFor(1.90 + it * 0.005) }
        assertTrue(sweep.zipWithNext { a, b -> a <= b }.all { it })
    }

    @Test
    fun interpolationIsLinearBetweenThePublishedBreakPoints() {
        assertEquals(0.50, leadAcid.socFor(2.010), 1e-9)
        assertEquals(0.70, leadAcid.socFor(2.050), 1e-9)
        assertEquals(0.60, leadAcid.socFor(2.030), 1e-9)
        assertEquals(0.55, leadAcid.socFor(2.020), 1e-9)
    }

    @Test
    fun voltageForIsTheInverseOfSocFor() {
        listOf(0.05, 0.25, 0.5, 0.75, 0.95).forEach { soc ->
            assertEquals(soc, leadAcid.socFor(leadAcid.voltageFor(soc)), 1e-6)
        }
        assertEquals(1.910, leadAcid.voltageFor(-0.5), 1e-9)
        assertEquals(2.125, leadAcid.voltageFor(1.5), 1e-9)
    }

    @Test
    fun chemistrySelectsTheMatchingCurve() {
        assertEquals(leadAcid, OcvTable.forChemistry(Chemistry.LEAD_ACID))
        assertEquals(OcvTable.LI_ION_3P7, OcvTable.forChemistry(Chemistry.LI_ION))

        // The same per-cell voltage means very different things to the two chemistries: 3.70 V is a
        // 30 % li-ion cell but is clamped to full on a lead-acid table that tops out at 2.125 V.
        assertEquals(0.30, OcvTable.LI_ION_3P7.socFor(3.70), 1e-9)
        assertEquals(1.0, leadAcid.socFor(3.70), 1e-9)
        assertEquals(1.0, OcvTable.LI_ION_3P7.socFor(4.20), 1e-9)
    }

    @Test
    fun capacityIsUnchangedAtExactlyTheRateCurrentTheLabelWasMeasuredAt() {
        val config = BatteryConfig(ratedCapacityAh = 20.0, ratedAtHours = 2.0, peukertExponent = 1.3)

        // 20 Ah at the 2 h rate means the label was measured at 10 A, where the factor is 1.0 by
        // construction; below it lead-acid genuinely yields a little more.
        assertEquals(1.0, CapacityModel.peukertFactor(20.0, 2.0, 1.3, 10.0), 1e-9)
        assertEquals(20.0, CapacityModel.effectiveCapacityAh(config, 10.0, null), 1e-9)
        assertTrue(CapacityModel.effectiveCapacityAh(config, 5.0, null) > 20.0)
    }

    @Test
    fun higherDischargeCurrentShrinksTheUsableAmpHours() {
        val config = BatteryConfig(ratedCapacityAh = 20.0, ratedAtHours = 2.0, peukertExponent = 1.3)

        val atTwenty = CapacityModel.effectiveCapacityAh(config, 20.0, null)
        val atForty = CapacityModel.effectiveCapacityAh(config, 40.0, null)

        // 20 A is twice the rate current -> 0.5^0.3; 40 A -> 0.25^0.3.
        assertEquals(16.245, atTwenty, 0.001)
        assertEquals(13.195, atForty, 0.001)
        assertTrue(atForty < atTwenty)
    }

    @Test
    fun peukertDeratingIsCappedSoAStallCannotInventCapacity() {
        val config = BatteryConfig(ratedCapacityAh = 20.0, ratedAtHours = 2.0, peukertExponent = 1.6)

        // 0.8 A is 12.5x below the rate current, so the raw law would claim 4.55x the label; the
        // guard caps the multiplier at 4.0.
        assertEquals(4.0, CapacityModel.peukertFactor(20.0, 2.0, 1.6, 0.8), 1e-9)
        assertEquals(80.0, CapacityModel.effectiveCapacityAh(config, 0.8, null), 1e-9)
    }

    @Test
    fun coldWeatherReducesCapacityAndWarmWeatherRestoresItWithinTheClamp() {
        val config = BatteryConfig(
            ratedCapacityAh = 20.0,
            ratedAtHours = 2.0,
            referenceTempC = 25.0,
            tempCoefficientPerC = 0.004,
        )

        assertEquals(18.0, CapacityModel.effectiveCapacityAh(config, 10.0, 0.0), 1e-9)
        assertEquals(20.0, CapacityModel.effectiveCapacityAh(config, 10.0, 25.0), 1e-9)
        assertEquals(22.8, CapacityModel.effectiveCapacityAh(config, 10.0, 60.0), 1e-9)
        // The factor is clamped at +25 %, so a hot pack cannot claim more than it physically holds.
        assertEquals(25.0, CapacityModel.effectiveCapacityAh(config, 10.0, 100.0), 1e-9)
        // -100 C is already past the linear law (0.5 of label) but still above the 0.35 floor.
        assertEquals(10.0, CapacityModel.effectiveCapacityAh(config, 10.0, -100.0), 1e-9)
        // The floor is what stops a stuck sensor from driving the usable capacity to zero.
        assertEquals(7.0, CapacityModel.effectiveCapacityAh(config, 10.0, -200.0), 1e-9)
    }

    @Test
    fun missingTemperatureReadingLeavesTheRatingAlone() {
        val config = BatteryConfig(ratedCapacityAh = 20.0)

        assertEquals(20.0, CapacityModel.effectiveCapacityAh(config, 10.0, null), 1e-9)
        assertEquals(1.0, CapacityModel.temperatureFactor(null, 25.0, 0.004), 1e-9)
    }

    @Test
    fun degenerateRatingsCannotCrashTheDerating() {
        val config = BatteryConfig(ratedCapacityAh = 20.0, ratedAtHours = 0.0, peukertExponent = 1.3)

        assertEquals(20.0, CapacityModel.effectiveCapacityAh(config, 30.0, null), 1e-9)
        assertEquals(1.0, CapacityModel.peukertFactor(20.0, 2.0, 1.3, 0.0), 1e-9)
        assertEquals(1.0, CapacityModel.peukertFactor(0.0, 2.0, 1.3, 10.0), 1e-9)
    }

    @Test
    fun aRidiculouslySmallLoadStillLeavesSomeCapacityOnTheTable() {
        val config = BatteryConfig(ratedCapacityAh = 0.5, ratedAtHours = 2.0, peukertExponent = 1.3)

        // effectiveCapacityAh has a hard floor so the integrator never divides by zero.
        assertEquals(0.5, CapacityModel.effectiveCapacityAh(config, 0.0, null), 1e-9)
    }
}
