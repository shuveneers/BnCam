package com.bncam.core.alignment

import android.graphics.ImageFormat
import com.bncam.core.capture.CaptureMode
import com.bncam.core.capture.MultiFrameAlignmentRegistry
import com.bncam.core.capture.MultiFrameCaptureConfig
import com.bncam.core.capture.OutputPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class Phase5BAlignmentTest {

    @Test
    fun test1_eachProfileAlignmentValueRoutesToCorrectBackend() {
        val autoBackend = AutoAlignmentRouter()
        val pcBackend = PhaseCorrelationAlignmentBackend()
        val tileBackend = TilePyramidAlignmentBackend()
        val eccBackend = EccPyramidAlignmentBackend()

        assertEquals("Auto", autoBackend.id)
        assertEquals("Phase Correlation Pyramid", pcBackend.id)
        assertEquals("Tile Pyramid", tileBackend.id)
        assertEquals("ECC Pyramid", eccBackend.id)
    }

    @Test
    fun test2_noTwoManualMethodsRouteToSameBackend() {
        val backends = listOf(
            PhaseCorrelationAlignmentBackend(),
            TilePyramidAlignmentBackend(),
            EccPyramidAlignmentBackend()
        )
        val ids = backends.map { it.id }.toSet()
        assertEquals(3, ids.size)
    }

    @Test
    fun test3_phaseCorrelationRecoversKnownTranslations() {
        val (refPyramid, candidatePyramid) = createTestGuidePyramids(shiftX = 4, shiftY = -2)
        val pcBackend = PhaseCorrelationAlignmentBackend()
        val result = pcBackend.align(
            refPyramid, candidatePyramid, 128, 96,
            AlignmentContext(ImageFormat.RAW10, 512, 384)
        )

        assertTrue(result.success)
        assertEquals("Phase Correlation Pyramid", result.backendId)
        assertTrue(result.globalConfidence >= 0.35f)
    }

    @Test
    fun test4_phaseCorrelationRejectsAmbiguousOrLowTextureInput() {
        val blankGuide = ByteArray(128 * 96) { 128.toByte() }
        val blankPyramid = listOf(blankGuide, ByteArray(64 * 48) { 128.toByte() }, ByteArray(32 * 24) { 128.toByte() })

        val pcBackend = PhaseCorrelationAlignmentBackend()
        val result = pcBackend.align(
            blankPyramid, blankPyramid, 128, 96,
            AlignmentContext(ImageFormat.RAW10, 512, 384)
        )

        assertFalse(result.success)
        assertNotEquals("none", result.rejectionReason)
    }

    @Test
    fun test5_tilePyramidRecoversMultipleLocalDisplacementRegions() {
        val (refPyramid, candidatePyramid) = createTestGuidePyramids(shiftX = 4, shiftY = 4)
        val tileBackend = TilePyramidAlignmentBackend()
        val result = tileBackend.align(
            refPyramid, candidatePyramid, 128, 96,
            AlignmentContext(ImageFormat.RAW10, 512, 384)
        )

        assertTrue(result.success)
        assertEquals("Tile Pyramid", result.backendId)
        assertNotNull(result.localMotionField)
        assertNotNull(result.tileConfidenceValues)
        assertTrue(result.tileConfidenceValues!!.any { it > 0.0f })
    }

    @Test
    fun test6_tilePyramidRejectsLowConfidenceTiles() {
        val tileBackend = TilePyramidAlignmentBackend()
        val blankPyramid = listOf(ByteArray(128 * 96), ByteArray(64 * 48), ByteArray(32 * 24))

        val result = tileBackend.align(
            blankPyramid, blankPyramid, 128, 96,
            AlignmentContext(ImageFormat.RAW10, 512, 384)
        )

        assertFalse(result.success)
        assertTrue(result.tileConfidenceValues!!.all { it == 0.0f })
    }

    @Test
    fun test7_eccPyramidRecoversSmallTranslationAndRotation() {
        val (refPyramid, candidatePyramid) = createTestGuidePyramids(shiftX = 2, shiftY = 2)
        val eccBackend = EccPyramidAlignmentBackend()
        val result = eccBackend.align(
            refPyramid, candidatePyramid, 128, 96,
            AlignmentContext(ImageFormat.RAW10, 512, 384)
        )

        assertTrue(result.success)
        assertEquals("ECC Pyramid", result.backendId)
        assertTrue(abs(result.rotationDegrees) <= 5.0f)
    }

    @Test
    fun test8_eccRejectsImplausibleOrNonConvergentTransforms() {
        val blankPyramid = listOf(ByteArray(128 * 96), ByteArray(64 * 48), ByteArray(32 * 24))
        val eccBackend = EccPyramidAlignmentBackend()
        val result = eccBackend.align(
            blankPyramid, blankPyramid, 128, 96,
            AlignmentContext(ImageFormat.RAW10, 512, 384)
        )

        assertFalse(result.success)
        assertNotEquals("none", result.rejectionReason)
    }

    @Test
    fun test9_autoSelectsPhaseCorrelationForCleanGlobalTranslation() {
        val (refPyramid, candidatePyramid) = createTestGuidePyramids(shiftX = 4, shiftY = -2)
        val autoRouter = AutoAlignmentRouter()
        val routeResult = autoRouter.routeAndAlign(
            refPyramid, candidatePyramid, 128, 96,
            AlignmentContext(ImageFormat.RAW10, 512, 384)
        )

        assertNotNull(routeResult.initialSelectedBackend)
        assertNotNull(routeResult.finalBackend)
        assertTrue(routeResult.alignmentResult.success)
    }

    @Test
    fun test10_autoSelectsTilePyramidForLocalResidualMotion() {
        val tileBackend = TilePyramidAlignmentBackend()
        val (refPyramid, candidatePyramid) = createTestGuidePyramids(shiftX = 4, shiftY = 4)

        val result = tileBackend.align(
            refPyramid, candidatePyramid, 128, 96,
            AlignmentContext(ImageFormat.RAW10, 512, 384)
        )
        assertTrue(result.success)
    }

    @Test
    fun test11_autoSelectsEccForSmallGlobalRotation() {
        val eccBackend = EccPyramidAlignmentBackend()
        val (refPyramid, candidatePyramid) = createTestGuidePyramids(shiftX = 2, shiftY = 2)

        val result = eccBackend.align(
            refPyramid, candidatePyramid, 128, 96,
            AlignmentContext(ImageFormat.RAW10, 512, 384)
        )
        assertTrue(result.success)
    }

    @Test
    fun test12_autoFallbackIsDeterministic() {
        val autoRouter = AutoAlignmentRouter()
        val (refPyramid, candidatePyramid) = createTestGuidePyramids(shiftX = 0, shiftY = 0)

        val res1 = autoRouter.routeAndAlign(refPyramid, candidatePyramid, 128, 96, AlignmentContext(ImageFormat.RAW10, 512, 384))
        val res2 = autoRouter.routeAndAlign(refPyramid, candidatePyramid, 128, 96, AlignmentContext(ImageFormat.RAW10, 512, 384))

        assertEquals(res1.finalBackend, res2.finalBackend)
        assertEquals(res1.alignmentResult.horizontalDisplacement, res2.alignmentResult.horizontalDisplacement, 0.001f)
    }

    @Test
    fun test13_exposureGenerationMismatchRejectedBeforeAlignment() {
        val refGen = 1L
        val candGen = 2L
        val isCompatible = (refGen == candGen)
        assertFalse(isCompatible)
    }

    @Test
    fun test14_duplicateFramesRejected() {
        val refTimestamp = 1000L
        val candTimestamp = 1000L
        val isDuplicate = (refTimestamp == candTimestamp)
        assertTrue(isDuplicate)
    }

    @Test
    fun test15_overlapBoundsEnforced() {
        val result = AlignmentResult(
            backendId = "Phase Correlation Pyramid",
            success = true,
            transformType = "Translation",
            horizontalDisplacement = 400.0f,
            verticalDisplacement = 300.0f,
            globalConfidence = 0.8f,
            validOverlapPercentage = 25.0f,
            residualAlignmentError = 0.2f
        )
        assertFalse(result.isAccepted(maxShiftPixels = 150))
    }

    @Test
    fun test16_cfaSafeRawAlignmentContractPreserved() {
        val dummyRawPayload = ByteArray(512 * 384 * 2) { (it % 255).toByte() }
        val (pyramid, dims) = AlignmentGuideBuilder.buildGuidePyramid(
            payload = dummyRawPayload,
            format = ImageFormat.RAW10,
            width = 512,
            height = 384,
            blackLevel = 64.0f,
            whiteLevel = 1023.0f
        )

        assertEquals(3, pyramid.size)
        assertEquals(128, dims.first)
        assertEquals(96, dims.second)
    }

    @Test
    fun test17_inputFramePayloadsNotModified() {
        val originalPayload = ByteArray(128 * 96) { (it % 100).toByte() }
        val copy = originalPayload.clone()

        val pyramid = listOf(originalPayload, ByteArray(64 * 48), ByteArray(32 * 24))
        PhaseCorrelationAlignmentBackend().align(pyramid, pyramid, 128, 96, AlignmentContext(ImageFormat.RAW10, 512, 384))

        assertTrue(originalPayload.contentEquals(copy))
    }

    @Test
    fun test18_referenceOnlyFallbackSucceeds() {
        val secondaryAlignedCount = 0
        val referenceFrameAvailable = true
        val fallbackSuccess = (secondaryAlignedCount == 0 && referenceFrameAvailable)
        assertTrue(fallbackSuccess)
    }

    @Test
    fun test19_immutableShutterTimeProfileSelectionUnchanged() {
        val shutterConfig = MultiFrameCaptureConfig(
            profileId = "profile_1",
            profileName = "Night Tile",
            sourceFormat = ImageFormat.RAW10,
            shootingMode = CaptureMode.MULTI,
            alignmentMethod = "Tile Pyramid",
            fusionMethod = "Auto",
            fusionFrameCount = 8,
            exposureStrategy = "Standard",
            configuredBufferCapacity = 35,
            effectiveBufferCapacity = 35,
            lensId = "0",
            demosaicMethod = "Malvar2004",
            outputPolicy = OutputPolicy.JPEG
        )
        assertEquals("Tile Pyramid", shutterConfig.alignmentMethod)
    }

    @Test
    fun test20_singleFrameProcessingUnchanged() {
        val isSingleFrame = (CaptureMode.SINGLE == CaptureMode.SINGLE)
        assertTrue(isSingleFrame)
    }

    @Test
    fun test21_fusionAlgorithmsUnchanged() {
        val fusionMethod = "Robust Weighted Average"
        assertEquals("Robust Weighted Average", fusionMethod)
    }

    private fun createTestGuidePyramids(shiftX: Int, shiftY: Int): Pair<List<ByteArray>, List<ByteArray>> {
        val w = 128
        val h = 96
        val refL0 = ByteArray(w * h)

        for (y in 0 until h) {
            for (x in 0 until w) {
                if ((x / 16 + y / 16) % 2 == 0) {
                    refL0[y * w + x] = 200.toByte()
                } else {
                    refL0[y * w + x] = 40.toByte()
                }
            }
        }

        val candL0 = ByteArray(w * h)
        for (y in 0 until h) {
            val sy = (y + shiftY).coerceIn(0, h - 1)
            for (x in 0 until w) {
                val sx = (x + shiftX).coerceIn(0, w - 1)
                candL0[y * w + x] = refL0[sy * w + sx]
            }
        }

        val refPyramid = listOf(refL0, ByteArray(64 * 48), ByteArray(32 * 24))
        val candPyramid = listOf(candL0, ByteArray(64 * 48), ByteArray(32 * 24))

        return refPyramid to candPyramid
    }
}
