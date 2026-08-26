package com.bncam.core.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemosaicNativeContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/Demosaic.cpp").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    @Test
    fun raw10AndRawSensorUseOneDemosaicResolverAndRenderer() {
        val kotlinSource = File(appDir, "src/main/java/com/bncam/core/engine/ImageUtils.kt").readText()
        val nativeSource = File(appDir, "src/main/cpp/native-lib.cpp").readText()
        val ispSource = File(appDir, "src/main/cpp/IspCore.cpp").readText()

        assertEquals(1, Regex("private external fun renderJpegFromMasterNative").findAll(kotlinSource).count())
        assertTrue(nativeSource.contains("RawSourceFormat::RAW10 : RawSourceFormat::RAW_SENSOR"))
        assertEquals(1, Regex("resolveDemosaicForFrame\\(").findAll(ispSource).count())
        assertEquals(1, Regex("demosaicMalvar2004ToRgb32f\\(").findAll(ispSource).count())
        assertEquals(1, Regex("demosaicMenon2007ToRgb32f\\(").findAll(ispSource).count())
    }

    @Test
    fun requiredDemosaicDebugFieldsAreExported() {
        val ispSource = File(appDir, "src/main/cpp/IspCore.cpp").readText()
        listOf(
            "requestedDemosaicMode",
            "resolvedDemosaicAlgorithm",
            "demosaicResolveReason",
            "effectiveCfaPattern",
            "cfaOriginX",
            "cfaOriginY",
            "demosaicOutputChannelOrder=RGB",
            "malvarAvailable=true",
            "menonAvailable=true",
            "menonRefinementUsed=",
            "demosaicTimeMs",
            "fallbackOccurred",
            "fallbackReason",
            "autoSceneAnalysisUsed",
            "autoSampleCount",
            "autoMedianSignal",
            "autoMeanGradient",
            "autoP90Gradient",
            "autoEdgeFraction",
            "autoCoherentEdgeFraction",
            "autoLowSignalFraction",
            "autoSignals",
            "malvarTimeMs",
            "menonTimeMs",
            "demosaicSetupMs",
            "demosaicKernelMs",
            "demosaicFinalizeMs",
            "demosaicInputWidth",
            "demosaicInputHeight",
            "workingBufferBytesEstimate",
            "allocationReuse"
        ).forEach { field -> assertTrue("Missing $field", ispSource.contains(field)) }
    }

    @Test
    fun raw10AndRawSensorShareBrightnessVibranceAndPerformancePolicy() {
        val ispSource = File(appDir, "src/main/cpp/IspCore.cpp").readText()
        assertTrue(ispSource.contains("const bool isRawBayer = isRaw10 || isRawSensor"))
        assertTrue(ispSource.contains("isRawBayer && lowLightScene && lowRawClipping"))
        assertTrue(ispSource.contains("sharedRawBayerJpegPolicyUsed=true"))
        assertTrue(ispSource.contains("indoorLowLightMidtoneLiftApplied"))
        assertTrue(ispSource.contains("rawJpegBaseVibrance"))
        assertTrue(ispSource.contains("expensiveDefectPassSkipped"))
        assertTrue(ispSource.contains("demosaicScratchReused"))
        assertTrue(ispSource.contains("totalRawBayerJpegIspMs"))
    }

    @Test
    fun rawBayerJpegPolicyKeepsDngAndYuvOutsideRenderer() {
        val ispSource = File(appDir, "src/main/cpp/IspCore.cpp").readText()
        val dngSource = File(appDir, "src/main/cpp/DngMerger.cpp").readText()
        assertTrue(ispSource.contains("yuvUntouched=true"))
        assertTrue(ispSource.contains("dngUntouched=true"))
        assertTrue(dngSource.contains("dngAppliedExposureGain=false"))
        assertTrue(dngSource.contains("dngAppliedToneCurve=false"))
        assertTrue(dngSource.contains("dngAppliedDenoise=false"))
        assertTrue(dngSource.contains("dngAppliedSharpen=false"))
    }

    @Test
    fun brightLowLightRawFramesKeepSceneRequestedSubUnityGain() {
        val ispSource = File(appDir, "src/main/cpp/IspCore.cpp").readText()
        assertTrue(ispSource.contains("const bool brightHighlightRangePresent"))
        assertTrue(ispSource.contains("p95 >= 0.25f"))
        assertTrue(ispSource.contains("const bool highlightAwareSubUnityGainAllowed"))
        assertTrue(ispSource.contains("const bool subUnitySceneGainAllowed"))
        assertTrue(ispSource.contains("!highlightAwareSubUnityGainAllowed"))
        assertTrue(ispSource.contains("highlight_aware_low_light_scene_gain"))
    }

    @Test
    fun nativeValidationCoversMalvarMenonResolverAndOddCfaShifts() {
        val demosaicSource = File(appDir, "src/main/cpp/Demosaic.cpp").readText()
        val nativeSource = File(appDir, "src/main/cpp/native-lib.cpp").readText()

        assertTrue(demosaicSource.contains("constantFieldPassed"))
        assertTrue(demosaicSource.contains("syntheticChannelsPassed"))
        assertTrue(demosaicSource.contains("channelOrderPassed"))
        assertTrue(demosaicSource.contains("kernelDcGainPassed"))
        assertTrue(demosaicSource.contains("referenceVectorPassed"))
        assertTrue(demosaicSource.contains("samplePreservationPassed"))
        assertTrue(nativeSource.contains("cfaShiftOddXY"))
        assertTrue(nativeSource.contains("normalForcesMalvar"))
        assertTrue(nativeSource.contains("qualityForcesMenon"))
        assertTrue(nativeSource.contains("autoResolverImplemented=true"))
        assertTrue(nativeSource.contains("autoResolverPassed"))
        assertTrue(nativeSource.contains("autoSmoothMalvar"))
        assertTrue(nativeSource.contains("autoDetailMenon"))
        assertTrue(nativeSource.contains("autoLowSignalMalvar"))
        assertTrue(nativeSource.contains("autoHighIsoMalvar"))
        assertTrue(nativeSource.contains("autoHighMotionMalvar"))
    }
}
