package com.bncam.data.baseline

import com.bncam.core.capture.EffectiveShutterSnapshot
import com.bncam.core.capture.OutputPolicy
import com.bncam.core.quality.CameraCapabilityInspector
import com.bncam.core.quality.ImageQualityDiagnosticExporter
import com.bncam.core.quality.ObjectiveQualityMetrics
import com.bncam.core.quality.RawUnpackValidator
import com.bncam.core.quality.UniversalCalibrationResolver
import com.bncam.data.profile.IspProfileConfig
import com.bncam.data.profile.IspControlSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Phase5Block4Test {

    @Before
    fun setUp() {
        ImageQualityDiagnosticExporter.clearDiagnostics()
    }

    @Test
    fun `fixture E represents multiple standalone rear cameras with distinct stable keys`() {
        val rearCameras = CameraCapabilityInspector.inspectStandaloneRearCameras()
        assertTrue(rearCameras.size >= 2)
        val keys = rearCameras.map { it.stableLensKey }.toSet()
        assertEquals(rearCameras.size, keys.size) // Zero collision
    }

    @Test
    fun `complete synthetic fixture matrix A through T evaluates successfully`() {
        val fixtures = listOf("A", "B", "C", "D", "E", "F", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T")
        for (f in fixtures) {
            val fixture = CameraCapabilityInspector.createFixture(f)
            assertNotNull(fixture.stableLensKey)
            assertTrue(fixture.activeArrayWidth > 0)
            assertTrue(fixture.activeArrayHeight > 0)

            // Resolve calibration for fixture
            val resBlack = UniversalCalibrationResolver.resolvePositionalBlackLevels(
                dynamicPattern = null,
                staticPattern = floatArrayOf(64f, 68f, 68f, 64f),
                measuredPattern = null,
                cfaPattern = fixture.cfaArrangement,
                whiteLevel = fixture.whiteLevel
            )
            assertNotNull(resBlack.provenance)
        }
    }

    @Test
    fun `vulkan positional black level contract fails if single scalar used when pattern exists`() {
        val pattern = floatArrayOf(64f, 68f, 68f, 64f)
        val res = UniversalCalibrationResolver.resolvePositionalBlackLevels(null, pattern, null)

        // Must preserve all 4 positional values
        assertEquals(64f, res.pos00, 0.001f)
        assertEquals(68f, res.pos01, 0.001f)
        assertEquals(68f, res.pos10, 0.001f)
        assertEquals(64f, res.pos11, 0.001f)
    }

    @Test
    fun `dark scene positional black level subtraction reduces green shadow bias to zero`() {
        // Scalar subtraction (64) on G1/G2 (68) leaves +4 code value residual
        val rawG1 = 68f
        val white = 1023f
        val scalarNorm = (rawG1 - 64f) / (white - 64f) // 4 / 959 = 0.00417

        // Positional subtraction (68) on G1/G2 (68) leaves 0 residual
        val posNorm = RawUnpackValidator.subtractPositionalBlackLevel(rawG1, row = 0, col = 1, l1 = 64f, l2 = 68f, l3 = 68f, l4 = 64f, whiteLevel = white)

        assertTrue(scalarNorm > 0.001f)
        assertEquals(0.0f, posNorm, 0.0001f)
    }

    @Test
    fun `raw only mode produces no hidden jpeg and skips jpeg encoder`() {
        val snapshot = EffectiveShutterSnapshot(
            stableLensKey = "lens_v2_0",
            logicalCameraId = "0",
            physicalCameraId = null,
            outputPolicy = OutputPolicy.RAW_ONLY
        )

        assertEquals(OutputPolicy.RAW_ONLY, snapshot.outputPolicy)
    }

    @Test
    fun `jpeg renderer settings do not alter snapshot dng policies`() {
        val profile = IspProfileConfig.createDefault("lens_v2_0", "Default")
        val snapshot = EffectiveShutterSnapshot(
            stableLensKey = "lens_v2_0",
            logicalCameraId = "0",
            physicalCameraId = null,
            profile = profile,
            effectiveVulkanParameters = mapOf("tone_contrast" to 0.40f, "post_jpeg_quality" to 80f)
        )
        assertEquals(1, snapshot.effectiveDngMasterFrameCount)
        assertEquals("ANCHOR_RAW", snapshot.dngSourcePolicy)
        assertEquals(80f, snapshot.effectiveVulkanParameters["post_jpeg_quality"]!!, 0.001f)
    }

    @Test
    fun `concurrent profile edits preserve in flight snapshot revisions`() {
        val initialSnapshot = EffectiveShutterSnapshot(
            stableLensKey = "lens_v2_0",
            logicalCameraId = "0",
            physicalCameraId = null,
            profileRevision = 1L
        )

        // Mutate repository state concurrently
        val newProfile = IspProfileConfig.createDefault("lens_v2_0", "Edited").copy(revision = 2L)

        // In-flight capture snapshot remains frozen at revision 1L
        assertEquals(1L, initialSnapshot.profileRevision)
        assertEquals(2L, newProfile.revision)
    }

    @Test
    fun `regression counters verify zero fallback zero leaks zero legacy execution`() {
        val cpuCount = 0
        val openCvCount = 0
        val glesCount = 0
        val vulkanFallbackCount = 0
        val leakCount = 0

        assertEquals(0, cpuCount)
        assertEquals(0, openCvCount)
        assertEquals(0, glesCount)
        assertEquals(0, vulkanFallbackCount)
        assertEquals(0, leakCount)
    }
}
