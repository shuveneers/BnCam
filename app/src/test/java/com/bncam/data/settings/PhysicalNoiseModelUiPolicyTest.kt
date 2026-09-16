package com.bncam.data.settings

import com.bncam.core.quality.NoiseModelSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicalNoiseModelUiPolicyTest {

    private val systemModel = PersistedParametricNoiseModel(
        a = listOf(1.0, 2.0, 3.0, 4.0),
        b = listOf(5.0, 6.0, 7.0, 8.0),
        c = listOf(-1.0, -2.0, -3.0, -4.0),
        d = listOf(9.0, 10.0, 11.0, 12.0),
        isoStep = 100.0
    )

    @Test
    fun exposesExactlyFourPhysicalSourcesInPublishedOrder() {
        assertEquals(
            listOf(
                NoiseModelSource.OEM,
                NoiseModelSource.SYSTEM,
                NoiseModelSource.MANUAL,
                NoiseModelSource.PRESET
            ),
            PhysicalNoiseModelUiPolicy.selectableSources
        )
    }

    @Test
    fun oemBypassesDynamicIsoUi() {
        assertFalse(PhysicalNoiseModelUiPolicy.dynamicIsoAvailable(NoiseModelSource.OEM))
        assertTrue(PhysicalNoiseModelUiPolicy.dynamicIsoAvailable(NoiseModelSource.SYSTEM))
        assertTrue(PhysicalNoiseModelUiPolicy.dynamicIsoAvailable(NoiseModelSource.MANUAL))
        assertTrue(PhysicalNoiseModelUiPolicy.dynamicIsoAvailable(NoiseModelSource.PRESET))
    }

    @Test
    fun resetPreservesSystemCalibrationButClearsUserOverrides() {
        val migration = PhysicalNoiseModelMigrationMetadata(
            migratedFromLegacy = true,
            legacyMode = "Manual",
            legacyManualSoRetained = true
        )
        val original = LensPhysicalNoiseModelSettings(
            source = NoiseModelSource.MANUAL,
            dynamicIsoEnabled = true,
            dynamicIsoCoefficient = 1.37,
            systemModel = systemModel,
            systemModelOrigin = "learned",
            manualModel = systemModel.copy(isoStep = 200.0),
            selectedPresetId = "preset-1",
            migration = migration
        )

        val reset = PhysicalNoiseModelUiPolicy.resetUserControls(original)

        assertEquals(NoiseModelSource.SYSTEM, reset.source)
        assertFalse(reset.dynamicIsoEnabled)
        assertEquals(1.0, reset.dynamicIsoCoefficient, 0.0)
        assertSame(systemModel, reset.systemModel)
        assertEquals("learned", reset.systemModelOrigin)
        assertNull(reset.manualModel)
        assertNull(reset.selectedPresetId)
        assertEquals(migration, reset.migration)
    }

    @Test
    fun summaryDoesNotPretendIncompleteSourcesAreConfigured() {
        assertEquals(
            "System · model not calibrated",
            PhysicalNoiseModelUiPolicy.summary(LensPhysicalNoiseModelSettings(source = NoiseModelSource.SYSTEM))
        )
        assertEquals(
            "Manual · incomplete",
            PhysicalNoiseModelUiPolicy.summary(LensPhysicalNoiseModelSettings(source = NoiseModelSource.MANUAL))
        )
        assertEquals(
            "Preset · none selected",
            PhysicalNoiseModelUiPolicy.summary(LensPhysicalNoiseModelSettings(source = NoiseModelSource.PRESET))
        )
    }
}
