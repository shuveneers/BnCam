package com.bncam.core.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorCalibrationFusionNoiseTest {
    @Test
    fun `fusion combines each selected frame S O instead of reusing anchor`() {
        val frame1 = calibration(doubleArrayOf(1.0, 2.0, 1.0, 2.0, 1.0, 2.0, 1.0, 2.0), "frame1")
        val frame2 = calibration(doubleArrayOf(3.0, 4.0, 3.0, 4.0, 3.0, 4.0, 3.0, 4.0), "frame2")

        val fused = SensorCalibrationResolver.combineNoiseForFusion(frame1, listOf(frame1, frame2))

        assertEquals(1.0, fused.effectiveNoiseProfile!![0], 0.000001)
        assertEquals(1.5, fused.effectiveNoiseProfile!![1], 0.000001)
        assertEquals(1.0, fused.noiseSnapshot!!.effectiveS[0], 0.000001)
        assertEquals(2.0, fused.noiseSnapshot!!.effectiveO[0], 0.000001)
        assertTrue(fused.effectiveNoiseProfileSource.contains("2 frames"))
        assertTrue(fused.pipelineWarnings.any {
            it.contains("frozen PhysicalNoiseState remains unchanged") &&
                it.contains("anchor metadata was not reused")
        })
    }

    @Test
    fun `missing per-frame profile uses explicit zero fallback in fusion denominator`() {
        val frame1 = calibration(doubleArrayOf(1.0, 2.0, 1.0, 2.0, 1.0, 2.0, 1.0, 2.0), "frame1")
        val frame2 = calibration(doubleArrayOf(3.0, 4.0, 3.0, 4.0, 3.0, 4.0, 3.0, 4.0), "frame2")
        val frame3 = calibration(null, "missing")

        val fused = SensorCalibrationResolver.combineNoiseForFusion(frame1, listOf(frame1, frame2, frame3))

        assertEquals(4.0 / 9.0, fused.effectiveNoiseProfile!![0], 0.000001)
        assertEquals(6.0 / 9.0, fused.effectiveNoiseProfile!![1], 0.000001)
        assertTrue(fused.effectiveNoiseProfileSource.contains("1 explicit zero fallbacks"))
    }

    @Test
    fun `fusion transforms each frame S O from its own black white domain`() {
        val anchor = calibration(doubleArrayOf(1.0, 2.0, 1.0, 2.0, 1.0, 2.0, 1.0, 2.0), "anchor")
        val support = calibration(
            noise = doubleArrayOf(3.0, 4.0, 3.0, 4.0, 3.0, 4.0, 3.0, 4.0),
            source = "support",
            black = 128f,
            white = 2047
        )

        val fused = SensorCalibrationResolver.combineNoiseForFusion(anchor, listOf(anchor, support))
        val alpha = (1023.0 - 64.0) / (2047.0 - 128.0)
        val beta = (64.0 - 128.0) / (2047.0 - 128.0)

        assertEquals((1.0 + 3.0 * alpha) / 4.0, fused.effectiveNoiseProfile!![0], 1.0e-12)
        assertEquals((2.0 + 3.0 * beta + 4.0) / 4.0, fused.effectiveNoiseProfile!![1], 1.0e-12)
    }


    @Test
    fun `physical merge stats keep shutter S O immutable and record residual propagation separately`() {
        val base = calibration(
            doubleArrayOf(4.0, 8.0, 4.0, 8.0, 4.0, 8.0, 4.0, 8.0),
            "frame"
        ).withPhysicalNoiseAuthority()
        val beforeS = base.noiseSnapshot!!.effectiveS
        val beforeO = base.noiseSnapshot!!.effectiveO
        val beforeProfile = base.effectiveNoiseProfile!!.copyOf()

        val propagated = base.withPhysicalMergeStats(
            "physicalFusionVarianceScale=0.25;physicalEffectiveFrameCount=4.0;" +
                "spectraSScale0=1.25;spectraOScale0=0.75"
        )

        assertTrue(propagated.noiseSnapshot!!.effectiveS.contentEquals(beforeS))
        assertTrue(propagated.noiseSnapshot!!.effectiveO.contentEquals(beforeO))
        assertTrue(propagated.effectiveNoiseProfile!!.contentEquals(beforeProfile))
        assertEquals(0.25, propagated.physicalFusionVarianceScale, 1.0e-12)
        assertEquals(4.0, propagated.physicalEffectiveFrameCount, 1.0e-12)
        assertTrue(propagated.pipelineWarnings.any {
            it.contains("frozenPhysicalSoUnchanged=true") &&
                it.contains("SPECTRA fit coefficients ignored")
        })
    }

    @Test
    fun `spectra observer-only stats adapt shutter snapshot within bounded authority`() {
        val base = calibration(
            doubleArrayOf(1.0, 2.0, 1.0, 2.0, 1.0, 2.0, 1.0, 2.0),
            "frame"
        )

        val adapted = base.withSpectraMergeStats(
            "spectraEnabled=true;spectraObserverSamples=4096;" +
                "spectraObserverConfidence=1.0;spectraFusionVarianceScale=1.0;" +
                "spectraEffectiveFrameCount=1.0;" +
                "spectraScale0=1.25;spectraScale1=1.00;spectraScale2=0.75;spectraScale3=1.10"
        )

        assertEquals(1.25, adapted.noiseSnapshot!!.effectiveS[0], 1.0e-12)
        assertEquals(2.50, adapted.noiseSnapshot!!.effectiveO[0], 1.0e-12)
        assertEquals(0.75, adapted.noiseSnapshot!!.effectiveS[2], 1.0e-12)
        assertEquals(1.50, adapted.noiseSnapshot!!.effectiveO[2], 1.0e-12)
        assertTrue(adapted.effectiveNoiseProfileSource.contains("SPECTRA_CAPTURE_FUSION"))
    }

    @Test
    fun `spectra fusion variance scales both S and O once after native weighting`() {
        val base = calibration(
            doubleArrayOf(4.0, 8.0, 4.0, 8.0, 4.0, 8.0, 4.0, 8.0),
            "frame"
        )

        val adapted = base.withSpectraMergeStats(
            "spectraEnabled=true;spectraObserverSamples=0;" +
                "spectraObserverConfidence=0.0;spectraFusionVarianceScale=0.25;" +
                "spectraEffectiveFrameCount=4.0"
        )

        assertEquals(1.0, adapted.noiseSnapshot!!.effectiveS[0], 1.0e-12)
        assertEquals(2.0, adapted.noiseSnapshot!!.effectiveO[0], 1.0e-12)
        assertEquals(1.0, adapted.effectiveNoiseProfile!![0], 1.0e-12)
        assertEquals(2.0, adapted.effectiveNoiseProfile!![1], 1.0e-12)
    }

    @Test
    fun `spectra regression adapts S and O independently`() {
        val base = calibration(
            doubleArrayOf(1.0, 2.0, 1.0, 2.0, 1.0, 2.0, 1.0, 2.0),
            "frame"
        )

        val adapted = base.withSpectraMergeStats(
            "spectraEnabled=true;spectraObserverSamples=4096;" +
                "spectraObserverConfidence=1.0;spectraFusionVarianceScale=1.0;" +
                "spectraSScale0=1.20;spectraOScale0=0.80;spectraFitConfidence0=1.0;" +
                "spectraSScale1=1.00;spectraOScale1=1.00;spectraFitConfidence1=1.0;" +
                "spectraSScale2=1.00;spectraOScale2=1.00;spectraFitConfidence2=1.0;" +
                "spectraSScale3=1.00;spectraOScale3=1.00;spectraFitConfidence3=1.0"
        )

        assertEquals(1.20, adapted.noiseSnapshot!!.effectiveS[0], 1.0e-12)
        assertEquals(1.60, adapted.noiseSnapshot!!.effectiveO[0], 1.0e-12)
        assertEquals(1.00, adapted.noiseSnapshot!!.effectiveS[1], 1.0e-12)
        assertEquals(2.00, adapted.noiseSnapshot!!.effectiveO[1], 1.0e-12)
    }

    @Test
    fun `low confidence independent fit keeps adaptation conservative`() {
        val base = calibration(
            doubleArrayOf(1.0, 2.0, 1.0, 2.0, 1.0, 2.0, 1.0, 2.0),
            "frame"
        )

        val adapted = base.withSpectraMergeStats(
            "spectraEnabled=true;spectraObserverSamples=4096;" +
                "spectraObserverConfidence=1.0;spectraFusionVarianceScale=1.0;" +
                "spectraSScale0=1.20;spectraOScale0=0.80;spectraFitConfidence0=0.0"
        )

        assertEquals(1.04, adapted.noiseSnapshot!!.effectiveS[0], 1.0e-12)
        assertEquals(1.92, adapted.noiseSnapshot!!.effectiveO[0], 1.0e-12)
    }

    @Test
    fun `legacy calibration ignores spectra merge telemetry`() {
        val active = calibration(
            doubleArrayOf(1.0, 2.0, 1.0, 2.0, 1.0, 2.0, 1.0, 2.0),
            "frame"
        )
        val legacy = active.copy(
            noiseModelMode = "Off",
            noiseSnapshot = active.noiseSnapshot!!.copy(spectraMode = "Off")
        )

        val result = legacy.withSpectraMergeStats(
            "spectraEnabled=true;spectraObserverSamples=4096;" +
                "spectraObserverConfidence=1.0;spectraFusionVarianceScale=0.25;" +
                "spectraScale0=1.25;spectraScale1=1.25;spectraScale2=1.25;spectraScale3=1.25"
        )

        assertSame(legacy, result)
    }

    private fun calibration(
        noise: DoubleArray?,
        source: String,
        black: Float = 64f,
        white: Int = 1023
    ): FinalSensorCalibration {
        val base = BaseSensorCalibration(
            lensId = "0",
            physicalCameraId = null,
            calibrationProfileId = "0",
            frameSource = "RAW10",
            cfaPattern = 0,
            cfaName = "RGGB",
            sensorOrientation = 0,
            sensorTimestampNs = 1L,
            inputRawFormat = 37,
            inputBitDepth = 10,
            inputDomain = RawDomain.RAW10_PACKED_10BIT,
            baseWhiteLevel = white,
            baseWhiteLevelSource = "test",
            baseWhiteLevelRawMetadata = white,
            baseWhiteLevelAppliedDomain = "RAW10",
            baseWhiteLevelScaleFactor = 1f,
            dynamicWhiteLevelAvailable = true,
            baseBlackLevels = floatArrayOf(black, black, black, black),
            baseBlackLevelSource = "test",
            baseBlackLevelRawMetadataValues = floatArrayOf(black, black, black, black),
            baseBlackLevelAppliedDomain = "RAW10",
            baseBlackLevelScaleFactor = 1f,
            dynamicBlackLevelAvailable = true,
            blackSubtractionApplied = true,
            baseNoiseProfile = noise,
            baseNoiseProfileSource = source,
            baseNoiseProfileFallbackReason = if (noise == null) "test missing" else "None",
            baseNoiseProfilePresent = noise != null,
            baseNoiseProfileAppliedByDefault = noise != null,
            baseNoiseProfileFormula = "variance = S*x + O",
            baseNoiseProfilePairCount = noise?.size?.div(2) ?: 0,
            baseNoiseProfileChannelCount = noise?.size?.div(2) ?: 0,
            baseNoiseProfileChannelMap = "test",
            hasNoiseProfile = noise != null,
            noiseProfileValid = noise != null,
            normalizationCalibrationValid = true,
            cfaSupportedForBayerNoiseModel = true,
            baseWbGains = floatArrayOf(1f, 1f, 1f, 1f),
            baseWbSource = "test",
            baseWbAppliedByDefault = true,
            baseColorMatrix = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            baseColorMatrixSource = "test",
            baseColorMatrixApplied = true,
            baseColorMatrixIdentityFallbackUsed = false,
            baseColorMatrixRejectReason = "none",
            baseColorMatrixNote = "test",
            warnings = emptyList()
        )
        val override = LensOverrideLayer(
            blackLevelMode = "Auto",
            dynamicBlackLevelPercent = 100f,
            manualBlackLevels = null,
            noiseMode = "Auto",
            noisePresetName = "Auto",
            manualNoiseValues = null,
            colorMode = "System",
            colorPresetName = "System",
            manualColorMatrix = null,
            awbMode = "System",
            awbProfile = "System",
            awbRatio = 1f,
            awbTemp = 0f,
            awbIntensity = 0f,
            warnings = emptyList()
        )
        return FinalSensorCalibration(
            base = base,
            override = override,
            effectiveWhiteLevel = white,
            effectiveWhiteLevelSource = "test",
            effectiveWhiteLevelAppliedDomain = "RAW10",
            effectiveWhiteLevelScaleFactor = 1f,
            effectiveBlackLevels = floatArrayOf(black, black, black, black),
            effectiveBlackLevelSource = "test",
            blackLevelScaleFactor = 1f,
            effectiveBlackLevelAppliedDomain = "RAW10",
            blackSubtractionApplied = true,
            noiseModelMode = "Auto",
            effectiveNoiseProfile = noise,
            effectiveNoiseProfileSource = source,
            effectiveNoiseProfileFallbackReason = if (noise == null) "test missing" else "None",
            hasNoiseProfile = noise != null,
            noiseProfileValid = noise != null,
            normalizationCalibrationValid = true,
            cfaSupportedForBayerNoiseModel = true,
            effectiveNoiseProfileApplied = noise != null,
            noiseProfileNotAppliedReason = if (noise != null) "none" else "missing_sensor_noise_profile",
            effectiveNoiseProfilePairCount = noise?.size?.div(2) ?: 0,
            effectiveNoiseProfileChannelCount = noise?.size?.div(2) ?: 0,
            effectiveNoiseProfileFormula = "variance = S*x + O",
            effectiveNoiseProfileChannelMap = "test",
            effectiveWbGains = floatArrayOf(1f, 1f, 1f, 1f),
            effectiveWbSource = "test",
            effectiveWbApplied = true,
            effectiveColorMatrix = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            effectiveColorMatrixSource = "test",
            effectiveColorMatrixApplied = true,
            effectiveColorMatrixIdentityFallbackUsed = false,
            effectiveColorMatrixRejectReason = "none",
            effectiveColorMatrixNote = "test",
            rawInputDomain = RawDomain.RAW10_PACKED_10BIT,
            applicability = emptyList(),
            calibrationApplied = true,
            pipelineWarnings = emptyList(),
            noiseSnapshot = NoiseModelSnapshotV3(
                lensKey = "0",
                sourceFormat = "RAW10",
                iso = 100,
                exposureTimeNs = 1L,
                postRawSensitivityBoost = 100,
                cfaPattern = 0,
                cfaName = "RGGB",
                whiteLevel = white,
                blackLevel = floatArrayOf(black, black, black, black),
                cameraS = DoubleArray(4) { channel -> noise?.getOrNull(channel * 2) ?: 0.0 },
                cameraO = DoubleArray(4) { channel -> noise?.getOrNull(channel * 2 + 1) ?: 0.0 },
                effectiveS = DoubleArray(4) { channel -> noise?.getOrNull(channel * 2) ?: 0.0 },
                effectiveO = DoubleArray(4) { channel -> noise?.getOrNull(channel * 2 + 1) ?: 0.0 },
                chromaUserScale = 1.0f,
                lumaUserScale = 1.0f,
                spectraMode = if (noise == null) "Off" else "Auto",
                signalModelConfidence = if (noise == null) 0.0f else 1.0f
            )
        )
    }
}
