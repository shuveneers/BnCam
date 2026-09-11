package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase3CalibrationProvenanceSourceContractTest {
    private fun source(path: String): String = File(path).readText()

    @Test
    fun persistedProfilesAreNotInstalledBeforeExactBinding() {
        val repo = source("app/src/main/java/com/bncam/core/isp/raw10/RawCameraColorProfileRepository.kt")
        assertTrue("private const val VERSION = 2" in repo)
        assertTrue("raw_camera_color_profiles_v2" in repo)
        assertTrue("registerExpectedCalibrationBinding" in repo)
        assertTrue("persistedCandidates" in repo)
        assertTrue("native installation deferred until exact sensor calibration binding is registered" in repo)
        assertTrue("staticCalibrationFingerprint" in repo)
        assertTrue("snapshotMatchesBinding" in repo)
        assertFalse("stableFileKey(entry.snapshot.calibrationProfileId)" in repo)
    }

    @Test
    fun calibrationProfileIdComesFromSensorAuthorityNotLensAlias() {
        val calibration = source("app/src/main/java/com/bncam/core/quality/SensorCalibration.kt")
        assertTrue("CalibrationProfileBinding.from(sensorMetadata)" in calibration)
        assertTrue("calibrationProfileId = calibrationProfileBinding.calibrationProfileId" in calibration)
        assertFalse("\"\$lensId/\$physicalCameraId\"" in calibration)
    }

    @Test
    fun rawColorAuditConsumesUniformSensorMetadata() {
        val audit = source("app/src/main/java/com/bncam/core/capture/RawColorPipelineAuditor.kt")
        assertTrue("sensorMetadata: SensorMetadata?" in audit)
        assertTrue("sensorMetadata.referenceIlluminant1Field" in audit)
        assertTrue("sensorMetadata.forwardMatrix1" in audit)
        assertFalse("characteristics.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)" in audit)
    }

    @Test
    fun dngProfileRequiresExactStaticCalibrationMatch() {
        val semantic = source("app/src/main/java/com/bncam/core/isp/raw10/DngSemanticAudit.kt")
        assertTrue("matchesDngCharacterization" in semantic)
        assertTrue("DNG_STATIC_CALIBRATION_MISMATCH" in semantic)
        assertTrue("EXACT_SENSOR_METADATA_AND_DNG_STATIC_MATCH" in semantic)
    }

    @Test
    fun genericLegacyResolverIsNotUsedByProductionSources() {
        val mainRoot = File("app/src/main/java")
        val offenders = mainRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "UniversalCalibrationResolver.kt" }
            .filter { "UniversalCalibrationResolver" in it.readText() }
            .toList()
        assertTrue(offenders.isEmpty())
    }
    @Test
    fun persistedProfilesAreRevalidatedAgainstCurrentStaticCharacterization() {
        val source = source("app/src/main/java/com/bncam/core/isp/raw10/RawCameraColorProfileRepository.kt")
        assertTrue("binding.matchesDngCharacterization(" in source)
        assertTrue("REJECTED_STAGE_BINDING_MISMATCH" in source)
        assertTrue("installDiscoveredProfile(" in source)
        assertTrue("calibrationBinding: CalibrationProfileBinding" in source)
    }

}
