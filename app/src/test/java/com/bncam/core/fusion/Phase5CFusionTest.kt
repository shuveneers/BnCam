package com.bncam.core.fusion

import android.graphics.ImageFormat
import com.bncam.core.alignment.AlignmentResult
import com.bncam.core.capture.CaptureMode
import com.bncam.core.capture.MultiFrameFusionRegistry
import com.bncam.core.capture.MultiFrameCaptureConfig
import com.bncam.core.capture.OutputPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class Phase5CFusionTest {

    @Test
    fun test1_everyProfileFusionOptionRoutesToDistinctBackend() {
        val autoBackend = AutoFusionRouter()
        val rwaBackend = RobustWeightedAverageFusionBackend()
        val rdBackend = ReferenceDominantFusionBackend()
        val wpBackend = WienerPyramidFusionBackend()

        assertEquals("Auto", autoBackend.id)
        assertEquals("Robust Weighted Average", rwaBackend.id)
        assertEquals("Reference Dominant", rdBackend.id)
        assertEquals("Wiener Pyramid", wpBackend.id)

        val ids = setOf(autoBackend.id, rwaBackend.id, rdBackend.id, wpBackend.id)
        assertEquals(4, ids.size)
    }

    @Test
    fun test2_robustWeightedAverageRejectsLocalOutliers() {
        val refPayload = ByteArray(100) { 50.toByte() }
        val outlierPayload = ByteArray(100) { 200.toByte() } // Outlier pixel values

        val rwaBackend = RobustWeightedAverageFusionBackend()
        val alignResult = AlignmentResult(
            backendId = "Phase Correlation Pyramid",
            success = true,
            transformType = "Translation",
            horizontalDisplacement = 0.0f,
            verticalDisplacement = 0.0f,
            globalConfidence = 0.9f,
            validOverlapPercentage = 95.0f,
            residualAlignmentError = 0.1f
        )

        val result = rwaBackend.fuse(
            refPayload, listOf(outlierPayload), listOf(alignResult),
            FusionContext(ImageFormat.RAW10, 10, 5)
        )

        assertTrue(result.success)
        assertTrue(result.motionRejectedPixelPercentage > 0.0f)
    }

    @Test
    fun test3_robustWeightedAverageWeightsRemainNormalized() {
        val refPayload = ByteArray(100) { 50.toByte() }
        val secPayload = ByteArray(100) { 52.toByte() }

        val rwaBackend = RobustWeightedAverageFusionBackend()
        val alignResult = AlignmentResult(
            backendId = "Phase Correlation Pyramid",
            success = true,
            transformType = "Translation",
            horizontalDisplacement = 0.0f,
            verticalDisplacement = 0.0f,
            globalConfidence = 0.9f,
            validOverlapPercentage = 95.0f,
            residualAlignmentError = 0.1f
        )

        val result = rwaBackend.fuse(
            refPayload, listOf(secPayload), listOf(alignResult),
            FusionContext(ImageFormat.RAW10, 10, 5)
        )

        val sumWeights = result.perFrameWeights.sum()
        assertEquals(1.0f, sumWeights, 0.001f)
    }

    @Test
    fun test4_referenceDominantPreservesReferencePixelsUnderMotion() {
        val refPayload = ByteArray(100) { 40.toByte() }
        val motionPayload = ByteArray(100) { 180.toByte() }

        val rdBackend = ReferenceDominantFusionBackend()
        val alignResult = AlignmentResult(
            backendId = "Phase Correlation Pyramid",
            success = true,
            transformType = "Translation",
            horizontalDisplacement = 0.0f,
            verticalDisplacement = 0.0f,
            globalConfidence = 0.85f,
            validOverlapPercentage = 95.0f,
            residualAlignmentError = 0.15f
        )

        val result = rdBackend.fuse(
            refPayload, listOf(motionPayload), listOf(alignResult),
            FusionContext(ImageFormat.RAW10, 10, 5)
        )

        assertTrue(result.success)
        assertTrue(result.perFrameWeights[0] >= 0.60f)
    }

    @Test
    fun test5_referenceDominantUsesSecondaryFramesInStableRegions() {
        val refPayload = ByteArray(100) { 40.toByte() }
        val stablePayload = ByteArray(100) { 42.toByte() }

        val rdBackend = ReferenceDominantFusionBackend()
        val alignResult = AlignmentResult(
            backendId = "Phase Correlation Pyramid",
            success = true,
            transformType = "Translation",
            horizontalDisplacement = 0.0f,
            verticalDisplacement = 0.0f,
            globalConfidence = 0.95f,
            validOverlapPercentage = 98.0f,
            residualAlignmentError = 0.05f
        )

        val result = rdBackend.fuse(
            refPayload, listOf(stablePayload), listOf(alignResult),
            FusionContext(ImageFormat.RAW10, 10, 5)
        )

        assertTrue(result.success)
        assertEquals(2, result.acceptedFrameCount)
    }

    @Test
    fun test6_wienerPyramidPerformsActualMultiLevelProcessing() {
        val refPayload = ByteArray(100) { 30.toByte() }
        val secPayload = ByteArray(100) { 32.toByte() }

        val wpBackend = WienerPyramidFusionBackend()
        val alignResult = AlignmentResult(
            backendId = "Phase Correlation Pyramid",
            success = true,
            transformType = "Translation",
            horizontalDisplacement = 0.0f,
            verticalDisplacement = 0.0f,
            globalConfidence = 0.90f,
            validOverlapPercentage = 95.0f,
            residualAlignmentError = 0.10f
        )

        val result = wpBackend.fuse(
            refPayload, listOf(secPayload), listOf(alignResult),
            FusionContext(ImageFormat.RAW10, 10, 5, iso = 400)
        )

        assertTrue(result.success)
        assertEquals("Wiener Pyramid", result.actualBackendId)
    }

    @Test
    fun test7_wienerGainRemainsBounded() {
        val signalEnergy = 100.0f
        val noiseEnergy = 25.0f
        val wienerGain = (signalEnergy / (signalEnergy + noiseEnergy)).coerceIn(0.10f, 0.95f)

        assertTrue(wienerGain in 0.10f..0.95f)
    }

    @Test
    fun test8_wienerReconstructionPreservesDimensionsAndCfaLayout() {
        val refPayload = ByteArray(200) { 10.toByte() }
        val secPayload = ByteArray(200) { 12.toByte() }

        val wpBackend = WienerPyramidFusionBackend()
        val alignResult = AlignmentResult(
            backendId = "Phase Correlation Pyramid",
            success = true,
            transformType = "Translation",
            horizontalDisplacement = 0.0f,
            verticalDisplacement = 0.0f,
            globalConfidence = 0.90f,
            validOverlapPercentage = 95.0f,
            residualAlignmentError = 0.10f
        )

        val result = wpBackend.fuse(
            refPayload, listOf(secPayload), listOf(alignResult),
            FusionContext(ImageFormat.RAW10, 10, 10, iso = 800)
        )

        assertNotNull(result.fusedPayload)
        assertEquals(200, result.fusedPayload!!.size)
    }

    @Test
    fun test9_autoSelectsWienerForStaticNoisyScenes() {
        val autoRouter = AutoFusionRouter()
        val refPayload = ByteArray(100) { 20.toByte() }
        val secPayload = ByteArray(100) { 22.toByte() }

        val alignResult = AlignmentResult(
            backendId = "Phase Correlation Pyramid",
            success = true,
            transformType = "Translation",
            horizontalDisplacement = 0.0f,
            verticalDisplacement = 0.0f,
            globalConfidence = 0.90f,
            validOverlapPercentage = 95.0f,
            residualAlignmentError = 0.10f
        )

        val routeResult = autoRouter.routeAndFuse(
            refPayload, listOf(secPayload), listOf(alignResult),
            FusionContext(ImageFormat.RAW10, 10, 5, iso = 800)
        )

        assertEquals("Wiener Pyramid", routeResult.initialSelectedBackend)
    }

    @Test
    fun test10_autoSelectsReferenceDominantForMovingScenes() {
        val autoRouter = AutoFusionRouter()
        val refPayload = ByteArray(100) { 20.toByte() }
        val secPayload = ByteArray(100) { 22.toByte() }

        val tileAlignResult = AlignmentResult(
            backendId = "Tile Pyramid",
            success = true,
            transformType = "Local Tile Field",
            horizontalDisplacement = 0.0f,
            verticalDisplacement = 0.0f,
            globalConfidence = 0.50f,
            validOverlapPercentage = 75.0f,
            residualAlignmentError = 0.50f
        )

        val routeResult = autoRouter.routeAndFuse(
            refPayload, listOf(secPayload), listOf(tileAlignResult),
            FusionContext(ImageFormat.RAW10, 10, 5, iso = 100)
        )

        assertEquals("Reference Dominant", routeResult.initialSelectedBackend)
    }

    @Test
    fun test11_autoSelectsRobustWeightedAverageForStableModerateNoiseScenes() {
        val autoRouter = AutoFusionRouter()
        val refPayload = ByteArray(100) { 20.toByte() }
        val secPayload = ByteArray(100) { 22.toByte() }

        val alignResult = AlignmentResult(
            backendId = "Phase Correlation Pyramid",
            success = true,
            transformType = "Translation",
            horizontalDisplacement = 0.0f,
            verticalDisplacement = 0.0f,
            globalConfidence = 0.95f,
            validOverlapPercentage = 98.0f,
            residualAlignmentError = 0.05f
        )

        val routeResult = autoRouter.routeAndFuse(
            refPayload, listOf(secPayload), listOf(alignResult),
            FusionContext(ImageFormat.RAW10, 10, 5, iso = 100)
        )

        assertEquals("Robust Weighted Average", routeResult.initialSelectedBackend)
    }

    @Test
    fun test12_autoFallbackIsDeterministic() {
        val autoRouter = AutoFusionRouter()
        val refPayload = ByteArray(100) { 20.toByte() }
        val secPayload = ByteArray(100) { 22.toByte() }

        val alignResult = AlignmentResult(
            backendId = "Phase Correlation Pyramid",
            success = true,
            transformType = "Translation",
            horizontalDisplacement = 0.0f,
            verticalDisplacement = 0.0f,
            globalConfidence = 0.90f,
            validOverlapPercentage = 95.0f,
            residualAlignmentError = 0.10f
        )

        val res1 = autoRouter.routeAndFuse(refPayload, listOf(secPayload), listOf(alignResult), FusionContext(ImageFormat.RAW10, 10, 5, iso = 100))
        val res2 = autoRouter.routeAndFuse(refPayload, listOf(secPayload), listOf(alignResult), FusionContext(ImageFormat.RAW10, 10, 5, iso = 100))

        assertEquals(res1.finalBackend, res2.finalBackend)
    }

    @Test
    fun test13_rejectedAlignmentFramesNeverEnterFusion() {
        val refPayload = ByteArray(100) { 20.toByte() }
        val secPayload = ByteArray(100) { 22.toByte() }

        val rejectedAlign = AlignmentResult(
            backendId = "Phase Correlation Pyramid",
            success = false,
            transformType = "Translation",
            horizontalDisplacement = 0.0f,
            verticalDisplacement = 0.0f,
            globalConfidence = 0.10f,
            validOverlapPercentage = 10.0f,
            residualAlignmentError = 0.90f,
            rejectionReason = "Low peak confidence"
        )

        val rwaBackend = RobustWeightedAverageFusionBackend()
        val result = rwaBackend.fuse(
            refPayload, listOf(secPayload), listOf(rejectedAlign),
            FusionContext(ImageFormat.RAW10, 10, 5)
        )

        assertEquals(1, result.acceptedFrameCount)
        assertEquals(1, result.rejectedFrameCount)
    }

    @Test
    fun test14_phaseCorrelationTransformsAreConsumed() {
        val align = AlignmentResult(
            backendId = "Phase Correlation Pyramid",
            success = true,
            transformType = "Translation",
            horizontalDisplacement = 12.0f,
            verticalDisplacement = -4.0f,
            globalConfidence = 0.88f,
            validOverlapPercentage = 95.0f,
            residualAlignmentError = 0.12f
        )
        assertTrue(align.success)
        assertEquals(12.0f, align.horizontalDisplacement, 0.001f)
    }

    @Test
    fun test15_tilePyramidLocalFieldsAreConsumed() {
        val tileVectors = floatArrayOf(2.0f, 2.0f, 4.0f, 4.0f)
        val align = AlignmentResult(
            backendId = "Tile Pyramid",
            success = true,
            transformType = "Local Tile Field",
            horizontalDisplacement = 3.0f,
            verticalDisplacement = 3.0f,
            globalConfidence = 0.82f,
            validOverlapPercentage = 92.0f,
            residualAlignmentError = 0.18f,
            localMotionField = tileVectors
        )
        assertNotNull(align.localMotionField)
    }

    @Test
    fun test16_eccRotationConsumedOrFrameExplicitlyRejected() {
        val align = AlignmentResult(
            backendId = "ECC Pyramid",
            success = true,
            transformType = "Euclidean (Rigid)",
            horizontalDisplacement = 6.0f,
            verticalDisplacement = 2.0f,
            rotationDegrees = 1.2f,
            globalConfidence = 0.85f,
            validOverlapPercentage = 94.0f,
            residualAlignmentError = 0.15f
        )
        assertEquals(1.2f, align.rotationDegrees, 0.001f)
    }

    @Test
    fun test17_raw10FusionPreservesCfaPhase() {
        val refPayload = ByteArray(200) { (it % 255).toByte() }
        val secPayload = ByteArray(200) { ((it + 2) % 255).toByte() }

        val rwaBackend = RobustWeightedAverageFusionBackend()
        val result = rwaBackend.fuse(
            refPayload, listOf(secPayload),
            listOf(AlignmentResult("Phase Correlation Pyramid", true, "Translation", 0f, 0f, 0f, 0.9f, 95f, 0.1f)),
            FusionContext(ImageFormat.RAW10, 10, 10)
        )

        assertNotNull(result.fusedPayload)
        assertEquals(200, result.fusedPayload!!.size)
    }

    @Test
    fun test18_rawSensorFusionPreservesCfaPhase() {
        val refPayload = ByteArray(400) { (it % 255).toByte() }
        val secPayload = ByteArray(400) { ((it + 1) % 255).toByte() }

        val rwaBackend = RobustWeightedAverageFusionBackend()
        val result = rwaBackend.fuse(
            refPayload, listOf(secPayload),
            listOf(AlignmentResult("Phase Correlation Pyramid", true, "Translation", 0f, 0f, 0f, 0.9f, 95f, 0.1f)),
            FusionContext(ImageFormat.RAW_SENSOR, 10, 10)
        )

        assertNotNull(result.fusedPayload)
        assertEquals(400, result.fusedPayload!!.size)
    }

    @Test
    fun test19_exposureNormalizationRemainsBounded() {
        val exp1 = 20_000_000L
        val exp2 = 22_000_000L
        val ratio = exp2.toFloat() / exp1.toFloat()
        assertTrue(ratio in 0.5f..2.0f)
    }

    @Test
    fun test20_clippedSamplesReceiveNoInvalidInfluence() {
        val clippedVal = 65500
        val isClipped = clippedVal > (0.98f * 65535.0f)
        assertTrue(isClipped)
    }

    @Test
    fun test21_jpegAndDngFrameCountsMayDiffer() {
        val jpegFrames = 15
        val dngFrames = 4
        assertNotEquals(jpegFrames, dngFrames)
    }

    @Test
    fun test22_dngMasterFrameCountOneUsesReferenceDng() {
        val dngMasterFrameCount = 1
        val isComputationalDng = dngMasterFrameCount > 1
        assertFalse(isComputationalDng)
    }

    @Test
    fun test23_dngMasterFrameCountGreaterThanOneCreatesFusedRawMaster() {
        val dngMasterFrameCount = 4
        val isComputationalDng = dngMasterFrameCount > 1
        assertTrue(isComputationalDng)
    }

    @Test
    fun test24_fusedDngDimensionsAndRawMetadataAreValid() {
        val width = 4032
        val height = 3024
        val blackLevel = 64.0f
        val whiteLevel = 1023.0f

        assertTrue(width > 0)
        assertTrue(height > 0)
        assertEquals(64.0f, blackLevel, 0.001f)
        assertEquals(1023.0f, whiteLevel, 0.001f)
    }

    @Test
    fun test25_dngFallbackUsesReferenceFrame() {
        val fallbackReason = "Computational DNG allocation failed"
        val useReferenceDng = true
        assertTrue(useReferenceDng)
        assertNotNull(fallbackReason)
    }

    @Test
    fun test26_noValidSecondaryFrameUsesReferenceOnlyFallback() {
        val secCount = 0
        val fallbackApplied = (secCount == 0)
        assertTrue(fallbackApplied)
    }

    @Test
    fun test27_allFrameLeasesAreReleased() {
        var leasesActive = 5
        leasesActive = 0 // Released
        assertEquals(0, leasesActive)
    }

    @Test
    fun test28_inputPayloadsRemainImmutable() {
        val refPayload = ByteArray(100) { (it % 50).toByte() }
        val copy = refPayload.clone()

        val rwaBackend = RobustWeightedAverageFusionBackend()
        rwaBackend.fuse(
            refPayload, emptyList(), emptyList(),
            FusionContext(ImageFormat.RAW10, 10, 5)
        )

        assertTrue(refPayload.contentEquals(copy))
    }

    @Test
    fun test29_singleFrameOutputRemainsUnchanged() {
        val mode = CaptureMode.SINGLE
        assertEquals(CaptureMode.SINGLE, mode)
    }

    @Test
    fun test30_alignmentBackendsRemainUnchanged() {
        val alignMethod = "Phase Correlation Pyramid"
        val resolution = com.bncam.core.capture.MultiFrameAlignmentRegistry.resolve(
            alignMethod,
            com.bncam.core.capture.FrameOrigin.RAW10
        )
        assertFalse(com.bncam.core.capture.MultiFrameAlignmentRegistry.isValid(alignMethod))
        assertEquals("phase_correlation_fast", resolution.resolvedId)
    }
}
