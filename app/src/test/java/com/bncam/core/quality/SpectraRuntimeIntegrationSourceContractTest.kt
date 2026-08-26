package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectraRuntimeIntegrationSourceContractTest {
    private fun appDir(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/DngMerger.cpp").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    @Test
    fun snapshotSOReachesCaptureMergeAndPostFusionIsp() {
        val app = appDir()
        val imageUtils = File(app, "src/main/java/com/bncam/core/engine/ImageUtils.kt").readText()
        val bridge = File(app, "src/main/cpp/native-lib.cpp").readText()
        val merger = File(app, "src/main/cpp/DngMerger.cpp").readText()
        val calibration = File(app, "src/main/java/com/bncam/core/quality/SensorCalibration.kt").readText()

        assertTrue(imageUtils.contains("spectraEffectiveS = spectra.effectiveS"))
        assertTrue(imageUtils.contains("spectraEffectiveO = spectra.effectiveO"))
        assertTrue(imageUtils.contains("fuseSupportFrames = fuseSupportFrames"))
        assertTrue(bridge.contains("extractFiniteNonNegativeDouble4"))
        assertTrue(bridge.contains("fuseSupportFrames == JNI_TRUE"))
        assertTrue(merger.contains("analyzeAlignedSupportNoise"))
        assertTrue(merger.contains("mergeAlignedSupport"))
        assertTrue(merger.contains("spectraFusionVarianceScale"))
        assertTrue(merger.contains("weightSq32"))
        assertTrue(calibration.contains("fun FinalSensorCalibration.withSpectraMergeStats"))
        assertTrue(calibration.contains("fusionVarianceScale"))
        assertTrue(imageUtils.contains("masterFrame.finalCalibration ?: qualityConfig?.finalCalibration"))
    }

    @Test
    fun singleFrameObserverUsesRealWarmBufferFrameWithoutFusion() {
        val app = appDir()
        val runner = File(app, "src/main/java/com/bncam/core/runners/SingleFrameRunner.kt").readText()
        val rawInput = File(app, "src/main/java/com/bncam/core/isp/raw/Raw16RenderInput.kt").readText()

        assertTrue(runner.contains("spectraObserverFrame"))
        assertTrue(runner.contains("observerBuffer = spectraObserverFrame?.hardwareBuffer"))
        assertTrue(runner.contains("spectraObserverFrame"))
        assertTrue(rawInput.contains("arrayOf(observerBuffer, buffer)"))
        assertTrue(rawInput.contains("fuseSupportFrames = false"))
    }

    @Test
    fun multiFrameUsesActualNativeWeightsAndDoesNotDoubleScaleBeforeMerge() {
        val app = appDir()
        val runner = File(app, "src/main/java/com/bncam/core/runners/MultiFrameRunner.kt").readText()
        val merger = File(app, "src/main/cpp/DngMerger.cpp").readText()
        val master = File(app, "src/main/java/com/bncam/core/isp/raw/MasterRawFrame.kt").readText()

        assertTrue(runner.contains("qualityConfig = baseRenderQualityConfig"))
        assertTrue(merger.contains("inverseVarianceWeight"))
        assertTrue(merger.contains("alignmentConfidence"))
        assertTrue(merger.contains("motionConfidence"))
        assertTrue(merger.contains("model.confidence"))
        assertTrue(master.contains("withSpectraMergeStats(dngMergeStats)"))
    }

    @Test
    fun blackLevelsRemainMosaicOrderedWhileSOUsesCanonicalChannels() {
        val app = appDir()
        val merger = File(app, "src/main/cpp/DngMerger.cpp").readText()

        assertTrue(merger.contains("int spectraCfaChannel"))
        assertTrue(merger.contains("int spectraMosaicPhase"))
        assertTrue(merger.contains("((y & 1) << 1) | (x & 1)"))
        assertTrue(merger.contains("spectraPixelBlack"))
        assertFalse(merger.contains("blackLevels[static_cast<size_t>(ch)]"))
    }
    @Test
    fun spectraFitsShotAndReadNoiseSeparatelyAndUsesHeteroscedasticFusionVariance() {
        val app = appDir()
        val merger = File(app, "src/main/cpp/DngMerger.cpp").readText()
        val calibration = File(app, "src/main/java/com/bncam/core/quality/SensorCalibration.kt").readText()

        assertTrue(merger.contains("sAdaptationScale"))
        assertTrue(merger.contains("oAdaptationScale"))
        assertTrue(merger.contains("regressionConfidence"))
        assertTrue(merger.contains("populatedSignalBins"))
        assertTrue(merger.contains("relativeVarianceRatio"))
        assertTrue(merger.contains("supportVariance / anchorVariance"))
        assertTrue(merger.contains("observation.sAdaptationScale"))
        assertTrue(merger.contains("observation.oAdaptationScale"))
        assertTrue(calibration.contains("spectraSScale"))
        assertTrue(calibration.contains("spectraOScale"))
        assertTrue(calibration.contains("spectraFitConfidence"))
    }

    @Test
    fun spectraPassesAreOutOfPlaceChannelAwareAndExposeRequestedUiIdentity() {
        val app = appDir()
        val isp = File(app, "src/main/cpp/IspCore.cpp").readText()
        val header = File(app, "src/main/cpp/IspCore.h").readText()
        val screen = File(app, "src/main/java/com/bncam/ui/screens/settings/lens_profiles/NoiseModelSettingsScreen.kt").readText()
        val lens = File(app, "src/main/java/com/bncam/ui/screens/settings/lens_profiles/LensDetailScreen.kt").readText()

        val profileSub = File(app, "src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileTuningSubScreens.kt").readText()

        assertTrue(isp.contains("VST Wiener"))
        assertTrue(isp.contains("const cv::Mat source = raw.mosaic.clone()"))
        assertTrue(header.contains("rowProfileByChannel"))
        assertTrue(header.contains("columnProfileByChannel"))
        assertTrue(isp.contains("measurePatternEnergy(destination"))
        assertTrue(isp.contains("downstreamLumaAuthority"))
        assertTrue(isp.contains("downstreamChromaAuthority"))
        assertTrue(isp.contains("remainingDownstreamAuthority"))
        assertTrue(isp.contains("spectraCaptureIntegration={source=capture_snapshot"))
        assertFalse(isp.contains("updateNoiseObserver("))
        assertFalse(isp.contains("computeMultiFrameNoiseWeight("))
        assertTrue(profileSub.contains("ProfileSpectraSettingsScreen"))
        assertTrue(profileSub.contains("Dynamic ISO"))
        assertTrue(profileSub.contains("Luma noise"))
        assertTrue(profileSub.contains("Chroma noise"))
        assertFalse(lens.contains("SPECTRA Noise model"))
    }

    @Test
    fun spectraEvolutionIsIsoAdaptiveSpatiallyAwareAndNoRegretGated() {
        val app = appDir()
        val isp = File(app, "src/main/cpp/IspCore.cpp").readText()
        val header = File(app, "src/main/cpp/IspCore.h").readText()
        val merger = File(app, "src/main/cpp/DngMerger.cpp").readText()
        val mergerHeader = File(app, "src/main/cpp/DngMerger.h").readText()
        val profileSub = File(app, "src/main/java/com/bncam/ui/screens/settings/lens_profiles/ProfileTuningSubScreens.kt").readText()
        val lens = File(app, "src/main/java/com/bncam/ui/screens/settings/lens_profiles/LensDetailScreen.kt").readText()

        assertTrue(header.contains("struct SpectraIsoAdaptiveState"))
        assertTrue(header.contains("struct SpectraProvenanceField"))
        assertTrue(header.contains("predictedRawVarianceByChannel"))
        assertTrue(header.contains("predictedChromaResidualVariance"))
        assertTrue(header.contains("greenSplitResidualEnergy"))
        assertTrue(header.contains("struct SpectraNoRegretResult"))
        assertTrue(header.contains("evaluatedTiles"))
        assertTrue(isp.contains("resolveSpectraIsoAdaptiveState"))
        assertTrue(isp.contains("effectiveIso"))
        assertTrue(isp.contains("combinedNoisePressure"))
        assertTrue(isp.contains("buildSpectraProvenanceField"))
        assertTrue(isp.contains("predictedVisibleVariance"))
        assertTrue(isp.contains("lensShadingGainAt"))
        assertTrue(isp.contains("applySpectraNoRegretGate"))
        assertTrue(isp.contains("rejectedOversmooth"))
        assertTrue(isp.contains("rejectedDetailLoss"))
        assertTrue(isp.contains("broadAuthority"))
        assertTrue(merger.contains("noisePressure"))
        assertTrue(merger.contains("temporalAuthority"))
        assertTrue(merger.contains("adaptationLowerBound"))
        assertTrue(merger.contains("Huber IRLS"))
        assertTrue(merger.contains("temporalCorrelation"))
        assertTrue(merger.contains("independentVarianceScale"))
        assertTrue(mergerHeader.contains("spectraNoisePressure"))
        assertTrue(mergerHeader.contains("spectraTemporalAuthority"))
        assertTrue(mergerHeader.contains("spectraTemporalCorrelation"))
        assertTrue(mergerHeader.contains("spectraIndependentNoiseFraction"))
        assertTrue(profileSub.contains("ProfileSpectraSettingsScreen"))
        assertFalse(lens.contains("SPECTRA Noise model"))
    }

}
