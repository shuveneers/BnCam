package com.bncam.core.capture

import android.graphics.ImageFormat
import com.bncam.core.engine.CaptureStrategy
import com.bncam.core.output.CapturePublicationResult
import com.bncam.core.output.CaptureWarning
import com.bncam.core.output.CaptureWarningCode
import com.bncam.core.output.StringCaptureArtifacts
import com.bncam.core.quality.CurveRuntimeConfig
import com.bncam.core.quality.DemosaicMode
import com.bncam.core.quality.RenderQualityPreferencesSnapshot
import com.bncam.core.quality.ResolvedIspSettings
import com.bncam.core.tracing.CaptureTraceRecorder
import com.bncam.core.tracing.CaptureTraceSection
import com.bncam.data.settings.ResolvedLensHardwareSettings
import java.io.File
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureRecipeAndTraceTest {
    @Test
    fun recipeIsImmutableAndDefensivelyCopiesNestedLists() {
        val mutableWarnings = mutableListOf("initial")
        val recipe = recipeFixture(mutableWarnings)
        mutableWarnings += "late_mutation"

        assertEquals(listOf("initial"), recipe.executionSettings.lensHardwareSettings.warnings)
        assertTrue(
            CaptureRecipe::class.java.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) }
                .all { Modifier.isFinal(it.modifiers) }
        )
        assertFalse(CaptureRecipe::class.java.declaredMethods.any { it.name == "copy" })
    }

    @Test
    fun recipeCapturesSourceModeCountsAndStableSchemaSerialization() {
        val recipe = recipeFixture()
        val first = recipe.toJson()
        val second = recipe.toJson()

        assertEquals(FrameOrigin.RAW10, recipe.frameSource)
        assertEquals(CaptureMode.MULTI, recipe.captureMode)
        assertEquals(40, recipe.requestedFrameCount)
        assertEquals(25, recipe.effectiveFrameCount)
        assertEquals(3, recipe.schemaVersion)
        assertEquals(first, second)
        assertTrue(first.contains("\"schemaVersion\":3"))
        assertTrue(first.contains("\"dngSource\":\"FUSED_RAW\""))
        assertTrue(first.contains("\"runtimeSafeMaximum\":30"))
        assertFalse(first.contains("\"deviceSupportedMaximum\""))
        assertTrue(first.contains("\"frameSource\":\"RAW10\""))
        assertTrue(first.contains("\"captureMode\":\"MULTI\""))
        assertTrue(first.contains("\"requestedFrameCount\":40"))
        assertTrue(first.contains("\"effectiveFrameCount\":25"))
        assertTrue(first.contains("\"spectraProfile\""))
        assertTrue(first.contains("\"spectraStrength\""))
        assertTrue(first.contains("\"spectraLuma\""))
        assertTrue(first.contains("\"spectraChroma\""))
        assertTrue(first.contains("\"spectraDetailProtection\""))
        assertTrue(first.contains("\"spectraLowFrequency\""))
        assertTrue(first.contains("\"noiseReductionProfile\""))
        assertTrue(first.contains("\"luminance\""))
        assertTrue(first.contains("\"luminanceDetail\""))
        assertTrue(first.contains("\"colorSmoothness\""))
        assertTrue(first.contains("\"resolvedSettings\""))
        assertTrue(first.contains("\"CUSTOM_CONFIGURED_REDACTED\""))
        assertFalse(first.contains("/private/camera/path"))
        assertFalse(first.contains("raw16Bytes"))
    }

    @Test
    fun lensFingerprintChangesForPreviouslyOmittedSpectraControls() {
        val lens = recipeFixture().executionSettings.lensHardwareSettings

        assertNotEquals(lens.fingerprint(), lens.copy(dynamicIsoCoeff = 0.5f).fingerprint())
        assertNotEquals(lens.fingerprint(), lens.copy(noiseB = listOf(0.1f, 0.2f, 0.3f, 0.4f)).fingerprint())
        assertNotEquals(
            lens.fingerprint(),
            lens.copy(dynamicChromaAuthorityAdjustment = 0.5f).fingerprint()
        )
    }

    @Test
    fun runnersUseRecipeSnapshotInsteadOfDirectPreferenceFlows() {
        val appDir = sequenceOf(File("."), File("app"))
            .first { File(it, "src/main/java/com/bncam/core/runners/SingleFrameRunner.kt").isFile }
        listOf("SingleFrameRunner.kt", "MultiFrameRunner.kt").forEach { name ->
            val source =
                File(appDir, "src/main/java/com/bncam/core/runners/$name").readText()
            assertTrue(source.contains("val capturedSettings = recipe.executionSettings"))
            assertTrue(source.contains("preferenceSnapshot = capturedSettings.renderPreferences"))
            assertFalse(source.contains(".first()"))
        }
    }

    @Test
    fun traceSerializesRequestedResolvedExecutedFallbackWarningAndException() {
        val recorder = CaptureTraceRecorder(recipeFixture(), "capture-42")
        recorder.decision(
            section = CaptureTraceSection.ALIGNMENT,
            key = "runtimeAlignment",
            requested = "optical_flow_quality",
            supported = "false",
            resolved = "phase_correlation_fast",
            executed = "phase_correlation_fast",
            result = "support_frames_aligned",
            fallback = true,
            reason = "planned_method_not_connected"
        )
        recorder.warning(
            CaptureWarning(
                code = CaptureWarningCode.CAPTURE_FALLBACK,
                message = "Fallback used",
                reason = "planned_method_not_connected"
            )
        )
        recorder.exception("alignment", IllegalStateException("synthetic failure"))
        val trace = recorder.complete(
            StringCaptureArtifacts(
                jpegUri = "content://jpeg/1",
                thumbnailUri = "content://jpeg/1",
                publicationResult = CapturePublicationResult.PARTIAL_SUCCESS,
                warnings = listOf(
                    CaptureWarning(
                        code = CaptureWarningCode.DNG_PUBLICATION_FAILED,
                        message = "DNG failed",
                        reason = "dng_write_failed"
                    )
                )
            )
        )
        val json = trace.toJson()

        assertEquals(json, trace.toJson())
        assertTrue(json.contains("\"requested\":\"optical_flow_quality\""))
        assertTrue(json.contains("\"resolved\":\"phase_correlation_fast\""))
        assertTrue(json.contains("\"executed\":\"phase_correlation_fast\""))
        assertTrue(json.contains("\"fallback\":true"))
        assertTrue(json.contains("\"reason\":\"planned_method_not_connected\""))
        assertTrue(json.contains("\"CAPTURE_FALLBACK\""))
        assertTrue(json.contains("\"java.lang.IllegalStateException\""))
        assertFalse(json.contains("rawImagePayload"))
        assertFalse(json.contains("raw16Bytes"))
    }

    private fun recipeFixture(
        warningSource: MutableList<String> = mutableListOf("initial")
    ): CaptureRecipe {
        val warm = FrameCapacityPolicy.resolveWarmBuffer(FrameOrigin.RAW10, 30)
        val candidates = FrameCapacityPolicy.resolveSelectableCandidates(
            FrameOrigin.RAW10,
            CaptureMode.MULTI,
            requestedValue = 40,
            runtimeSafeMaximum = 30
        )
        val processing = FrameCapacityPolicy.resolveProcessingFrames(
            FrameOrigin.RAW10,
            CaptureMode.MULTI,
            requestedValue = 40,
            runtimeSafeMaximum = 30
        )
        val dng = FrameCapacityPolicy.resolveDngMasterFrames(
            FrameOrigin.RAW10,
            CaptureMode.MULTI,
            requestedValue = 4,
            runtimeSafeMaximum = 30
        )
        val renderPreferences = RenderQualityPreferencesSnapshot(
            profileId = "profile-1",
            frameSourceFormat = ImageFormat.RAW10,
            captureMode = CaptureStrategy.MULTI_FRAME_ZSL,
            resolvedIspSettings = ResolvedIspSettings(
                profileId = "profile-1",
                captureMode = CaptureStrategy.MULTI_FRAME_ZSL,
                frameSource = "RAW10",
                routeName = "multi_raw10",
                activeSettings = emptyList(),
                hiddenSettings = emptyList()
            ),
            jpegQuality = 98,
            demosaic = DemosaicMode.resolveForPhase4("Malvar 2004"),
            curves = CurveRuntimeConfig.linear()
        )
        val lens = ResolvedLensHardwareSettings(
            lensId = "0",
            snapshotSource = "test",
            warnings = warningSource,
            noiseModelType = "Default",
            noiseModelNativeMode = 0,
            noiseA = listOf(0f, 0f, 0f, 0f),
            noiseB = listOf(0f, 0f, 0f, 0f),
            noiseC = listOf(0f, 0f, 0f, 0f),
            noiseD = listOf(0f, 0f, 0f, 0f),
            isoStep = 1f,
            isoNrStyle = "Default",
            isoNrNativeMode = 0,
            dynamicIsoCoeff = 0f,
            manualIsoValue = 0f,
            blackLevelMode = "Auto",
            blackLevelNativeMode = 0,
            dynamicBlackLevelPercent = 100f,
            manualBlackLevels = listOf(0f, 0f, 0f, 0f),
            colorMatrixMode = "System",
            colorMatrixNativeMode = 0,
            manualColorMatrix =
                listOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            colorMatrixValidationPassed = true,
            colorMatrixRejectReason = "none",
            awbProfile = "System",
            awbRatioRaw = "1.0",
            awbRatio = 1f,
            awbTemp = 0f,
            awbIntensity = 0f,
            awbNativeMode = 0
        )
        fun method(requested: String, resolved: String) = MethodResolution(
            requestedId = requested,
            requestedAvailability = MethodAvailability.IMPLEMENTED,
            supported = true,
            resolvedId = resolved,
            fallback = false,
            reason = "test_resolution"
        )
        return CaptureRecipe.create(
            CaptureRecipeInput(
                applicationVersion = "test",
                activeProfileIdentifier = "profile-1",
                profileVersionHash = "abcdef123456",
                logicalCameraId = "0",
                physicalCameraId = "0a",
                lensIdentifier = "main",
                frameSource = FrameOrigin.RAW10,
                captureMode = CaptureMode.MULTI,
                outputPolicy = OutputPolicy.JPEG_PLUS_RAW,
                pipelineGenerationId = 7,
                requestedFrameCount = 40,
                warmBufferResolution = warm,
                candidateCountResolution = candidates,
                processingFrameResolution = processing,
                dngMasterFrameResolution = dng,
                dngSource = DngSource.FUSED_RAW,
                frameSelectionMethod = method("auto", "latest_complete"),
                anchorSelectionMethod = method("auto", "latest_selected"),
                alignmentMethod = method("auto", "phase_correlation_fast"),
                fusionMethod = method("auto", "robust_mean"),
                demosaicMethod = method("Malvar 2004", "MALVAR_2004"),
                exposureStrategy = "ETTR",
                phoneAssistanceSensorsEnabled = true,
                computeBackendId = "cpu_native_opencv",
                hardwareOverrideFingerprint = "hardware-hash",
                thermalState = "NONE",
                captureTimestampEpochMs = 123456789L,
                capabilityResolutions = emptyList(),
                executionSettings = CaptureExecutionSettings(
                    profileName = "Test",
                    cameraSoundEnabled = true,
                    flashMode = "Off",
                    opticalStabilization = true,
                    hotPixelMode = "High Quality",
                    noiseReductionHint = "High Quality",
                    edgeModeHint = "High Quality",
                    tonemapHint = "High Quality",
                    antiBanding = "Auto",
                    debug = CaptureDebugSettings(
                        shotLoggingEnabled = true,
                        saveLocationData = false,
                        logSummary = true,
                        logActiveMode = true,
                        logProfileSettings = true,
                        logFrameAnalysis = true,
                        logWarnings = true,
                        logPipelineDebug = true,
                        logVendorInjection = false
                    ),
                    output = CaptureOutputSettings(
                        photoPrefix = "private-prefix",
                        saveLocation = "/private/camera/path",
                        mirrorFrontPreview = false,
                        watermarkEnabled = false,
                        watermarkStyle = "Default",
                        watermarkSignature = "private-signature",
                        watermarkAuthor = false,
                        exifSaveSignature = false,
                        exifExtraData = false
                    ),
                    selection = CaptureSelectionSettings(
                        basePosition = "Auto",
                        baseCandidateCount = 5,
                        baseIncludeInMerge = true,
                        baseBias = "Overall Best Score",
                        baseTemporalBias = 0f,
                        selectionRequestedFrames = 5,
                        frameBias = "Auto",
                        acceptAll = false,
                        rejectDuplicates = true,
                        useAlignableOnly = false,
                        discardFirst = false,
                        preferRecent = false,
                        ignoreStale = true
                    ),
                    merge = CaptureMergeSettings(
                        subPixel = false,
                        linearInterpolation = false,
                        strictness = 0.8f,
                        maximumShiftPixels = 150
                    ),
                    lensHardwareSettings = lens,
                    renderPreferences = renderPreferences
                )
            )
        )
    }
}
