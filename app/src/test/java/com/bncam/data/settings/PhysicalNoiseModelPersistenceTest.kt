package com.bncam.data.settings

import com.bncam.core.quality.NoiseModelSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicalNoiseModelPersistenceTest {

    @Test
    fun legacyAutoMigratesToOemBecauseItWasDirectCamera2() {
        val migrated = PhysicalNoiseModelMigration.migrate(
            LegacyPhysicalNoiseModelState(mode = "Auto")
        )

        assertEquals(NoiseModelSource.OEM, migrated.source)
        assertTrue(migrated.migration.migratedFromLegacy)
        assertFalse(migrated.dynamicIsoEnabled)
        assertEquals(1.0, migrated.dynamicIsoCoefficient, 0.0)
    }

    @Test
    fun legacyOffAndFreshInstallSelectSystemWithoutInventingAModel() {
        val oldOff = PhysicalNoiseModelMigration.migrate(
            LegacyPhysicalNoiseModelState(mode = "Off")
        )
        val fresh = PhysicalNoiseModelMigration.migrate(LegacyPhysicalNoiseModelState())

        assertEquals(NoiseModelSource.SYSTEM, oldOff.source)
        assertEquals(NoiseModelSource.SYSTEM, fresh.source)
        assertNull(oldOff.systemModel)
        assertNull(fresh.systemModel)
        assertFalse(fresh.migration.migratedFromLegacy)
    }

    @Test
    fun legacyManualRetainsBackupButNeverFabricatesAbcd() {
        val migrated = PhysicalNoiseModelMigration.migrate(
            LegacyPhysicalNoiseModelState(
                mode = "Manual",
                noiseA = "1,2,3,4",
                noiseB = "5,6,7,8",
                isoStep = "100",
                manualNoiseSo = "0.01,0.001,0.02,0.002,0.03,0.003,0.04,0.004"
            )
        )

        assertEquals(NoiseModelSource.SYSTEM, migrated.source)
        assertNull(migrated.manualModel)
        assertNull(migrated.systemModel)
        assertTrue(migrated.migration.legacyManualSoRetained)
        assertTrue(migrated.migration.legacyParametricFieldsRetained)
    }

    @Test
    fun legacyDynamicIsoIsResetBecauseItsMeaningChanged() {
        val migrated = PhysicalNoiseModelMigration.migrate(
            LegacyPhysicalNoiseModelState(
                mode = "Auto",
                dynamicIsoCoefficient = 0.63
            )
        )

        assertFalse(migrated.dynamicIsoEnabled)
        assertEquals(1.0, migrated.dynamicIsoCoefficient, 0.0)
        assertTrue(migrated.migration.legacyDynamicIsoReset)
    }

    @Test
    fun persistedModelAllowsNegativeCoefficientsAndRoundTrips() {
        val model = PersistedParametricNoiseModel(
            a = listOf(-1.0e-6, 2.0e-6, -3.0e-6, 4.0e-6),
            b = listOf(0.1, -0.2, 0.3, -0.4),
            c = listOf(-1.0e-9, 2.0e-9, -3.0e-9, 4.0e-9),
            d = listOf(0.001, -0.002, 0.003, -0.004),
            isoStep = 711.0
        )
        val encoded = PhysicalNoiseModelPersistenceCodec.encodeModel(model)
        val decoded = PhysicalNoiseModelPersistenceCodec.decodeModel(
            encoded.a,
            encoded.b,
            encoded.c,
            encoded.d,
            encoded.isoStep
        )

        assertEquals(model, decoded)
        assertEquals(model.a, decoded?.toCoreModel()?.a?.toList())
    }

    @Test
    fun dynamicIsoCoefficientUsesZeroToTwoRangeAndOneHundredthSteps() {
        assertEquals(0.0, sanitizePhysicalDynamicIsoCoefficient(-1.0), 0.0)
        assertEquals(0.0, sanitizePhysicalDynamicIsoCoefficient(0.004), 0.0)
        assertEquals(0.01, sanitizePhysicalDynamicIsoCoefficient(0.006), 1.0e-12)
        assertEquals(1.23, sanitizePhysicalDynamicIsoCoefficient(1.234), 1.0e-12)
        assertEquals(1.24, sanitizePhysicalDynamicIsoCoefficient(1.236), 1.0e-12)
        assertEquals(2.0, sanitizePhysicalDynamicIsoCoefficient(9.0), 0.0)
        assertEquals(1.0, sanitizePhysicalDynamicIsoCoefficient(Double.NaN), 0.0)
    }

    @Test
    fun systemManualAndPresetKeepIndependentPersistentState() {
        val system = PersistedParametricNoiseModel(
            a = listOf(1.0, 2.0, 3.0, 4.0),
            b = listOf(5.0, 6.0, 7.0, 8.0),
            c = listOf(9.0, 10.0, 11.0, 12.0),
            d = listOf(13.0, 14.0, 15.0, 16.0),
            isoStep = 100.0
        )
        val manual = PersistedParametricNoiseModel(
            a = listOf(-1.0, -2.0, -3.0, -4.0),
            b = listOf(-5.0, -6.0, -7.0, -8.0),
            c = listOf(-9.0, -10.0, -11.0, -12.0),
            d = listOf(-13.0, -14.0, -15.0, -16.0),
            isoStep = 711.0
        )
        val settings = LensPhysicalNoiseModelSettings(
            source = NoiseModelSource.PRESET,
            dynamicIsoEnabled = true,
            dynamicIsoCoefficient = 1.337,
            systemModel = system,
            systemModelOrigin = "OEM_CALIBRATED",
            manualModel = manual,
            selectedPresetId = "my-preset"
        ).sanitized()

        assertEquals(system, settings.systemModel)
        assertEquals(manual, settings.manualModel)
        assertEquals("my-preset", settings.selectedPresetId)
        assertEquals(1.34, settings.dynamicIsoCoefficient, 1.0e-12)
        assertTrue(settings.dynamicIsoEnabled)
        assertTrue(settings.selectedSourceConfigured)
    }
}
