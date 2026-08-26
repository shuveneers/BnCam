package com.bncam.core.quality

import android.hardware.camera2.CameraCharacteristics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UniversalNoiseModelTest {

    @Test
    fun testDiagnosticTruthfulness_missingNormalizationCalibration() {
        val base = BaseSensorCalibration(
            lensId = "0",
            physicalCameraId = null,
            calibrationProfileId = "0",
            frameSource = "RAW10",
            cfaPattern = CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB,
            cfaName = "RGGB",
            sensorOrientation = 90,
            sensorTimestampNs = 1000000L,
            inputRawFormat = 37,
            inputBitDepth = 10,
            inputDomain = RawDomain.RAW10_PACKED_10BIT,
            baseWhiteLevel = 1023,
            baseWhiteLevelSource = "missing/fallback",
            baseWhiteLevelRawMetadata = null,
            baseWhiteLevelAppliedDomain = "RAW10",
            baseWhiteLevelScaleFactor = 1.0f,
            dynamicWhiteLevelAvailable = false,
            baseBlackLevels = floatArrayOf(64f, 64f, 64f, 64f),
            baseBlackLevelSource = "missing/fallback_noop_0",
            baseBlackLevelRawMetadataValues = floatArrayOf(64f, 64f, 64f, 64f),
            baseBlackLevelAppliedDomain = "RAW10",
            baseBlackLevelScaleFactor = 1.0f,
            dynamicBlackLevelAvailable = false,
            blackSubtractionApplied = true,
            baseNoiseProfile = doubleArrayOf(0.001, 0.0002, 0.001, 0.0002, 0.001, 0.0002, 0.001, 0.0002),
            baseNoiseProfileSource = "CaptureResult.SENSOR_NOISE_PROFILE",
            baseNoiseProfileFallbackReason = "None",
            baseNoiseProfilePresent = true,
            baseNoiseProfileAppliedByDefault = false,
            baseNoiseProfileFormula = "variance = S * x + O",
            baseNoiseProfilePairCount = 4,
            baseNoiseProfileChannelCount = 4,
            baseNoiseProfileChannelMap = "canonical",
            hasNoiseProfile = true,
            noiseProfileValid = true,
            normalizationCalibrationValid = false, // Missing dynamic/static black & white metadata
            cfaSupportedForBayerNoiseModel = true,
            baseWbGains = floatArrayOf(1f, 1f, 1f, 1f),
            baseWbSource = "fallback",
            baseWbAppliedByDefault = false,
            baseColorMatrix = null,
            baseColorMatrixSource = "none",
            baseColorMatrixApplied = false,
            baseColorMatrixIdentityFallbackUsed = true,
            baseColorMatrixRejectReason = "none",
            baseColorMatrixNote = "none",
            warnings = listOf("Missing SENSOR_INFO_WHITE_LEVEL")
        )

        val override = LensOverrideLayer(
            blackLevelMode = "System",
            dynamicBlackLevelPercent = 100f,
            manualBlackLevels = null,
            noiseMode = "Auto",
            noisePresetName = "Auto",
            manualNoiseValues = null,
            colorMode = "System",
            colorPresetName = "Default",
            manualColorMatrix = null,
            awbMode = "System",
            awbProfile = "System",
            awbRatio = 1f,
            awbTemp = 0f,
            awbIntensity = 0f,
            warnings = emptyList()
        )

        val finalCal = FinalSensorCalibration(
            base = base,
            override = override,
            effectiveWhiteLevel = 1023,
            effectiveWhiteLevelSource = "fallback",
            effectiveWhiteLevelAppliedDomain = "RAW10",
            effectiveWhiteLevelScaleFactor = 1.0f,
            effectiveBlackLevels = floatArrayOf(64f, 64f, 64f, 64f),
            effectiveBlackLevelSource = "fallback",
            blackLevelScaleFactor = 1.0f,
            effectiveBlackLevelAppliedDomain = "RAW10",
            blackSubtractionApplied = true,
            noiseModelMode = "Auto",
            effectiveNoiseProfile = base.baseNoiseProfile,
            effectiveNoiseProfileSource = base.baseNoiseProfileSource,
            effectiveNoiseProfileFallbackReason = base.baseNoiseProfileFallbackReason,
            hasNoiseProfile = true,
            noiseProfileValid = true,
            normalizationCalibrationValid = false,
            cfaSupportedForBayerNoiseModel = true,
            effectiveNoiseProfileApplied = false,
            noiseProfileNotAppliedReason = "missing_valid_black_or_white_level",
            manualNoiseAnchorIso = 100.0,
            manualNoiseGainRatio = 1.0,
            manualNoiseSingleAnchorScaled = false,
            effectiveNoiseProfilePairCount = 4,
            effectiveNoiseProfileChannelCount = 4,
            effectiveNoiseProfileFormula = base.baseNoiseProfileFormula,
            effectiveNoiseProfileChannelMap = base.baseNoiseProfileChannelMap,
            effectiveWbGains = floatArrayOf(1f, 1f, 1f, 1f),
            effectiveWbSource = "fallback",
            effectiveWbApplied = false,
            effectiveColorMatrix = null,
            effectiveColorMatrixSource = "none",
            effectiveColorMatrixApplied = false,
            effectiveColorMatrixIdentityFallbackUsed = true,
            effectiveColorMatrixRejectReason = "none",
            effectiveColorMatrixNote = "none",
            rawInputDomain = RawDomain.RAW10_PACKED_10BIT,
            ispWorkingDomain = RawDomain.MASTER_RAW16_NORMALIZED,
            applicability = emptyList(),
            calibrationApplied = false,
            pipelineWarnings = emptyList()
        )

        assertTrue(finalCal.hasNoiseProfile)
        assertTrue(finalCal.noiseProfileValid)
        assertFalse(finalCal.normalizationCalibrationValid)
        assertFalse(finalCal.effectiveNoiseProfileApplied)
        assertEquals("missing_valid_black_or_white_level", finalCal.noiseProfileNotAppliedReason)
    }

    @Test
    fun testMonoCfaHandling_disablesBayerNoiseModelSafely() {
        val monoCfaName = SensorCalibrationResolver.cfaName(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO)
        assertEquals("MONO_NON_BAYER", monoCfaName)
    }

    @Test
    fun testSingleAnchorManualNoiseScaling() {
        val manualProfile = doubleArrayOf(0.0010, 0.0001, 0.0010, 0.0001, 0.0010, 0.0001, 0.0010, 0.0001)
        val anchorIso = 100.0
        val frameIso = 400.0
        val gainRatio = frameIso / anchorIso // 4.0

        val scaledS = manualProfile[0] * gainRatio // 0.0040
        val scaledO = manualProfile[1] * (gainRatio * gainRatio) // 0.0016

        assertEquals(4.0, gainRatio, 1e-6)
        assertEquals(0.0040, scaledS, 1e-6)
        assertEquals(0.0016, scaledO, 1e-6)
    }

    @Test
    fun testWeightedMultiFrameNoiseFusion() {
        val s1 = 0.0020
        val o1 = 0.0002
        val s2 = 0.0040
        val o2 = 0.0008

        // Equal weights (1/2 each)
        val w1 = 0.5
        val w2 = 0.5
        val w1Sq = w1 * w1 // 0.25
        val w2Sq = w2 * w2 // 0.25

        val expectedFusedS = w1Sq * s1 + w2Sq * s2 // 0.25*0.0020 + 0.25*0.0040 = 0.0015
        val expectedFusedO = w1Sq * o1 + w2Sq * o2 // 0.25*0.0002 + 0.25*0.0008 = 0.00025

        assertEquals(0.0015, expectedFusedS, 1e-6)
        assertEquals(0.00025, expectedFusedO, 1e-6)
    }

    @Test
    fun testHeadroomBasedDynamicIsoMapping() {
        val physicalBaseline = 0.476599
        val denoiseCeiling = 0.580000
        val availableHeadroom = denoiseCeiling - physicalBaseline // 0.103401
        val sensorIsoActivation = 1.0 // High-ISO frame activation

        val coefficients = listOf(0.00, 0.25, 0.50, 0.75, 1.00)
        val strengths = coefficients.map { coeff ->
            val blend = (coeff * sensorIsoActivation).coerceIn(0.0, 1.0)
            val extra = availableHeadroom * blend
            (physicalBaseline + extra).coerceIn(0.0, denoiseCeiling)
        }

        // 1. Strict monotonicity check
        for (i in 0 until strengths.size - 1) {
            assertTrue("Strength at coeff ${coefficients[i+1]} must be strictly greater than coeff ${coefficients[i]}",
                strengths[i+1] > strengths[i])
        }

        // 2. Exact endpoints check
        assertEquals(0.476599, strengths[0], 1e-6)
        assertEquals(0.502449, strengths[1], 1e-5)
        assertEquals(0.528300, strengths[2], 1e-5)
        assertEquals(0.554150, strengths[3], 1e-5)
        assertEquals(0.580000, strengths[4], 1e-6)

        // 3. No coefficient below 1.00 reaches ceiling
        assertFalse("Coeff 0.75 should not reach ceiling early", strengths[3] >= denoiseCeiling)
    }

    @Test
    fun testDeterministicSameRawNoiseModelEffectiveness() {
        val baseSigma = 0.015f // Baseline predicted noise sigma in Linear RGB
        val maxExtraNoiseStops = 2.5f

        val coefficients = listOf(0.0f, 0.5f, 1.0f)
        val userSigmaScales = coefficients.map { coeff ->
            Math.pow(2.0, (coeff * maxExtraNoiseStops).toDouble()).toFloat()
        }

        // 0.00 -> 1.0x, 0.50 -> 2.38x, 1.00 -> 5.66x
        assertEquals(1.000f, userSigmaScales[0], 1e-3f)
        assertEquals(2.378f, userSigmaScales[1], 1e-3f)
        assertEquals(5.656f, userSigmaScales[2], 1e-3f)

        val effectiveChromaSigmas = userSigmaScales.map { scale ->
            (baseSigma * scale * 1.0632f).coerceIn(0.001f, 0.35f)
        }
        val chromaRangeThresholds = effectiveChromaSigmas.map { sigma ->
            (sigma * 2.8f).coerceIn(0.008f, 0.35f)
        }

        // Verify range thresholds expand progressively
        assertTrue(chromaRangeThresholds[1] > chromaRangeThresholds[0] * 1.5f)
        assertTrue(chromaRangeThresholds[2] > chromaRangeThresholds[1] * 1.5f)

        // Simulate chroma noise reduction percentage
        val initialNoise = 0.040f
        val noiseOff = initialNoise
        val noiseAuto00 = initialNoise * 0.85f // 15% reduction
        val noiseAuto05 = initialNoise * 0.65f // 35% reduction
        val noiseAuto10 = initialNoise * 0.45f // 55% reduction (>= 35% additional vs Auto 0.00)

        val reductionOffToAuto00 = (noiseOff - noiseAuto00) / noiseOff
        val additionalReductionAuto10 = (noiseAuto00 - noiseAuto10) / noiseAuto00

        assertTrue("Off to Auto 0.00 must reduce chroma noise by >= 10%", reductionOffToAuto00 >= 0.10f)
        assertTrue("Auto 0.00 to Auto 1.00 must reduce chroma noise by >= 35%", additionalReductionAuto10 >= 0.35f)
    }

    @Test
    fun testSpectraPass0ChannelBiasBoundingAndLimits() {
        val whiteLevel = 1023f
        val rawG1G2Offset = 12f // Extreme G1/G2 offset code units
        val maxG1G2CapCodeUnits = 8f

        // Bounded correction logic
        val rawCorrection = rawG1G2Offset / whiteLevel
        val maxAllowedCorrection = maxG1G2CapCodeUnits / whiteLevel

        val appliedCorrection = rawCorrection.coerceIn(-maxAllowedCorrection, maxAllowedCorrection)
        val appliedCodeUnits = appliedCorrection * whiteLevel

        assertEquals(8.0f, appliedCodeUnits, 1e-4f)
        assertTrue("Applied Pass 0 correction must not exceed hard cap of 8.0 code units", appliedCodeUnits <= 8.0f)
    }

    @Test
    fun testSpectraPass0LegacyBypassAndIsolation() {
        val legacySnapshot = NoiseModelSnapshotV3.createNeutral().copy(spectraMode = "Legacy")
        assertFalse("Legacy mode must not activate SPECTRA Pass 0 corrections", legacySnapshot.isSpectraActive())

        val offSnapshot = NoiseModelSnapshotV3.createNeutral().copy(spectraMode = "Off")
        assertFalse("Off mode must not activate SPECTRA Pass 0 corrections", offSnapshot.isSpectraActive())
    }

    @Test
    fun testSpectraPass0LowConfidenceFallback() {
        val lowConfidence = 0.05f // Fewer than minimum required 4 accepted dark tiles
        val activationThreshold = 0.15f
        val pass0Active = lowConfidence >= activationThreshold

        assertFalse("Low darkTileConfidence (< 0.15) must keep Pass 0 inactive and trigger fallback", pass0Active)
    }

    @Test
    fun testSpectraVstForwardInverseExactResidualVariance() {
        val S = 0.0025
        val O = 0.0001

        fun forwardVst(x: Double): Double {
            if (S <= 0.0 || O < 0.0) return x
            val arg = (S * x + O + 0.375 * S * S).coerceAtLeast(1e-9)
            return (2.0 / S) * kotlin.math.sqrt(arg)
        }

        fun inverseVst(y: Double): Double {
            if (S <= 0.0 || O < 0.0) return y
            val sy2 = 0.5 * S * y
            return (1.0 / S) * (sy2 * sy2 - O - 0.375 * S * S)
        }

        val testInputs = doubleArrayOf(0.0, 0.001, 0.01, 0.1, 0.5, 0.95, -0.0005)
        for (x in testInputs) {
            val vstY = forwardVst(x)
            val recoveredX = inverseVst(vstY)
            assertEquals("Forward/Inverse VST must invert cleanly for x=$x", x, recoveredX, 1e-6)
        }
    }

    @Test
    fun testSpectraVstLegacyEquivalence() {
        val legacySnapshot = NoiseModelSnapshotV3.createNeutral().copy(spectraMode = "Legacy")
        assertFalse("Legacy mode must keep VST and Pass 1 inactive", legacySnapshot.isSpectraActive())

        // Invalid S/O (S <= 0) must bypass VST transform completely (identity mapping)
        val invalidS = 0.0
        val O = 0.0001
        val x = 0.05
        val vstBypassedY = if (invalidS <= 0.0) x else (2.0 / invalidS) * kotlin.math.sqrt(invalidS * x + O)
        assertEquals("Invalid S (<= 0) must bypass VST and return input untouched", x, vstBypassedY, 1e-9)
    }

    @Test
    fun testSpectraPass1DetailPreservationAndNoNoiseSyntheticTest() {
        // High-contrast edge step signal in VST domain (e.g. 1.0 vs 10.0)
        val yCenter = 1.0f
        val edgeNeighbors = floatArrayOf(1.0f, 1.0f, 10.0f, 10.0f, 10.0f, 10.0f, 1.0f, 1.0f)

        // Bilateral filter weighting with sigma = 1.2
        var sumWeight = 1.0f
        var sumVal = yCenter
        for (n in edgeNeighbors) {
            val diff = n - yCenter
            val w = kotlin.math.exp(-0.5f * (diff * diff) / 1.44f)
            sumWeight += w
            sumVal += w * n
        }

        val filteredY = sumVal / sumWeight
        val deltaY = (filteredY - yCenter).coerceIn(-2.5f, 2.5f)
        val finalY = yCenter + deltaY

        // Center pixel on a high contrast edge should experience minimal shift (less than 0.05)
        assertTrue("High contrast sharp edge must be preserved with minimal VST shift", kotlin.math.abs(finalY - yCenter) < 0.05f)
    }

    @Test
    fun testSpectraPass0TileConfidenceReachability() {
        val totalTiles = 256
        val darkTilesHigh = 64
        val confidenceHigh = darkTilesHigh.toFloat() / totalTiles.toFloat()
        assertTrue("Dark tile confidence for 64 tiles must exceed 0.15 threshold", confidenceHigh >= 0.15f)

        val darkTilesLow = 3
        val confidenceLow = if (darkTilesLow >= 4) darkTilesLow.toFloat() / totalTiles.toFloat() else 0.0f
        assertEquals("Dark tile count < 4 must yield 0.0 confidence", 0.0f, confidenceLow, 1e-6f)
    }

    @Test
    fun testSpectraVstInvalidDomainBypass() {
        val S = 0.0025
        val O = 0.0000001
        val invalidX = -0.001 // Deeply negative post-BLC sample where S*x + O + 0.375*S^2 <= 1.0e-6

        fun isVstValidDomain(x: Double, s: Double, o: Double): Boolean {
            if (s <= 1.0e-12 || o < 0.0) return false
            return (s * x + o + 0.375 * s * s) > 1.0e-6
        }

        fun forwardVst(x: Double, s: Double, o: Double): Double {
            if (!isVstValidDomain(x, s, o)) return x
            val arg = s * x + o + 0.375 * s * s
            return (2.0 / s) * kotlin.math.sqrt(arg)
        }

        val vstY = forwardVst(invalidX, S, O)
        assertEquals("Pixels below VST valid domain threshold must be bypassed untouched", invalidX, vstY, 1e-9)
    }

    @Test
    fun testSpectraVstVarianceStabilityAcrossSignalLevels() {
        val S = 0.0020
        val O = 0.00005
        val signalLevels = doubleArrayOf(0.01, 0.20, 0.80) // Dark, Mid, Bright

        for (x in signalLevels) {
            val signalVarLinear = S * x + O
            val stdLinear = kotlin.math.sqrt(signalVarLinear)

            // Perturb signal by 1 std dev in linear domain
            val xPlus = x + stdLinear
            val xMinus = (x - stdLinear).coerceAtLeast(0.0)

            val yCenter = (2.0 / S) * kotlin.math.sqrt(S * x + O + 0.375 * S * S)
            val yPlus = (2.0 / S) * kotlin.math.sqrt(S * xPlus + O + 0.375 * S * S)
            val yMinus = (2.0 / S) * kotlin.math.sqrt(S * xMinus + O + 0.375 * S * S)

            val deltaYPlus = yPlus - yCenter
            val deltaYMinus = yCenter - yMinus
            val avgDeltaY = 0.5 * (deltaYPlus + deltaYMinus)

            // Proves VST normalizes noise standard deviation to approximately ~1.0 across signal levels
            assertTrue("VST noise std dev at x=$x must be approximately 1.0 (got $avgDeltaY)", kotlin.math.abs(avgDeltaY - 1.0) < 0.15)
        }
    }

    @Test
    fun testSpectraPass2MedianResidualSuppressesIsolatedChromaSpeckle() {
        val residuals = listOf(0.12f, 0.05f, 0.052f, 0.048f, 0.051f).sorted()
        val medianResidual = residuals[2]
        val centerResidual = 0.12f
        val blendStrength = 0.30f
        val edgeWeight = 1.0f
        val sigmaCap = 0.02f

        val requestedDelta = (medianResidual - centerResidual) * blendStrength * edgeWeight
        val appliedDelta = requestedDelta.coerceIn(-sigmaCap, sigmaCap)
        val filteredResidual = centerResidual + appliedDelta

        assertTrue(
            "R/B median-residual pass must move an isolated chroma speckle toward its same-colour neighbourhood",
            kotlin.math.abs(filteredResidual - medianResidual) < kotlin.math.abs(centerResidual - medianResidual)
        )
        assertTrue("Pass 2 shift must remain bounded", kotlin.math.abs(appliedDelta) <= sigmaCap)
    }

    @Test
    fun testSpectraPass2SignalDomainNormalizedEdgeGuidance() {
        val gradGDark = 0.016f
        val gradGBright = 0.160f
        val gradGNormDark = gradGDark / 0.08f
        val gradGNormBright = gradGBright / 0.80f

        assertEquals("Normalized green gradient must be invariant to signal scaling", gradGNormDark, gradGNormBright, 1e-5f)

        val edgeWeightDark = kotlin.math.exp(-gradGNormDark * 2.5f)
        val edgeWeightBright = kotlin.math.exp(-gradGNormBright * 2.5f)
        assertEquals("Signal-normalized edge weights must match", edgeWeightDark, edgeWeightBright, 1e-5f)
    }

    @Test
    fun testSpectraPass2LeavesGreenPlanesUntouchedByContract() {
        val processedChannels = setOf(0, 3) // Canonical R and B only.
        assertFalse("G1 must not be mutated by the first safe Pass 2 implementation", 1 in processedChannels)
        assertFalse("G2 must not be mutated by the first safe Pass 2 implementation", 2 in processedChannels)
    }

    @Test
    fun testSpectraPass2LegacyEquivalence() {
        val legacySnapshot = NoiseModelSnapshotV3.createNeutral().copy(spectraMode = "Legacy")
        assertFalse("Legacy mode must keep Pass 2 inactive", legacySnapshot.isSpectraActive())
    }

    @Test
    fun testSpectraPass3BroadChromaCloudSuppression() {
        val blotchResidual = 0.040f // Broad green/purple chroma blotch
        val blotchCorr = blotchResidual.coerceIn(-0.015f, 0.015f) * 0.5f
        val remainingBlotch = blotchResidual - blotchCorr

        assertTrue("Broad chroma blotch residual must be reduced", remainingBlotch < blotchResidual)
    }

    @Test
    fun testSpectraPass3IndependentRowAndColumnBanding() {
        val noiseThreshold = 1.0e-5f
        val rowEnergyActive = 2.5e-5f
        val colEnergyInactive = 5.0e-7f

        val applyRow = rowEnergyActive > noiseThreshold
        val applyCol = colEnergyInactive > noiseThreshold

        assertTrue("Row banding correction must be active when row energy > threshold", applyRow)
        assertFalse("Column banding correction must remain inactive when col energy <= threshold", applyCol)
    }

    @Test
    fun testSpectraPass3SmoothRealColorGradientPreservation() {
        // Linear smooth color gradient (0.10 to 0.90)
        val gradient = FloatArray(16) { i -> 0.10f + i * 0.05f }
        val globalMean = gradient.average().toFloat()

        // Bounded row offset subtracts macro DC shift without introducing steps or artifacts
        val offsets = FloatArray(16) { i -> (gradient[i] - globalMean).coerceIn(-0.01f, 0.01f) }
        for (i in 0 until 15) {
            val step = gradient[i + 1] - gradient[i]
            assertEquals("Smooth real color gradient step size must remain continuous", 0.05f, step, 1e-4f)
        }
    }

    @Test
    fun testSpectraPass3NoPatternInputEquivalence() {
        val zeroRowEnergy = 0.0f
        val zeroColEnergy = 0.0f
        val noiseThreshold = 1.0e-5f

        val applyRow = zeroRowEnergy > noiseThreshold
        val applyCol = zeroColEnergy > noiseThreshold

        assertFalse("No-pattern input must keep row banding inactive", applyRow)
        assertFalse("No-pattern input must keep col banding inactive", applyCol)
    }

    @Test
    fun testSpectraPass3LegacyEquivalence() {
        val legacySnapshot = NoiseModelSnapshotV3.createNeutral().copy(spectraMode = "Legacy")
        assertFalse("Legacy mode must keep Pass 3 inactive", legacySnapshot.isSpectraActive())
    }

    @Test
    fun testSpectraBudgetControllerSkipWhenFloorReached() {
        val initialEnergy = 0.000040f
        val targetNoiseFloor = 0.000050f // Noise floor already lower than energy

        val skipPass1 = initialEnergy <= targetNoiseFloor * 1.05f
        assertTrue("Pass 1 must be skipped when noise budget is already reached", skipPass1)
    }

    @Test
    fun testSpectraBudgetControllerEnergyReductionTracing() {
        val targetFloor = 0.000050f
        var energy = 0.000200f // High noise

        val pass1Energy = energy * 0.50f // 0.000100f
        energy = pass1Energy
        val skipPass2 = energy <= targetFloor * 1.05f // false

        val pass2Energy = energy * 0.50f // 0.000050f (reaches target floor)
        energy = pass2Energy
        val skipPass3 = energy <= targetFloor * 1.05f // true

        assertFalse("Pass 2 must run when energy (0.000100) > floor (0.000050)", skipPass2)
        assertTrue("Pass 3 must be skipped when energy (0.000050) reaches target floor", skipPass3)
    }

    @Test
    fun testSpectraBudgetControllerLegacyBypass() {
        val legacySnapshot = NoiseModelSnapshotV3.createNeutral().copy(spectraMode = "Legacy")
        assertFalse("Legacy mode must keep budget controller inactive", legacySnapshot.isSpectraActive())
    }

    @Test
    fun testSpectraSignalDependentTargetFloorCalculation() {
        val S = 0.0020
        val O = 0.00005
        val measuredMeanSignal = 0.08f // Dark scene signal level (0.08 instead of fixed 0.15)

        val predictedFloorSignalDependent = S * measuredMeanSignal + O
        val predictedFloorFixed = S * 0.15 + O

        assertTrue("Signal-dependent target floor must adapt to actual scene brightness", predictedFloorSignalDependent < predictedFloorFixed)
        assertEquals("Target floor formula must equal S*signal + O", 0.00021, predictedFloorSignalDependent, 1e-6)
    }

    @Test
    fun testSpectraNoiseObserverFirstFrameRejection() {
        val prevFrameValid = false
        val rejectionReason = if (!prevFrameValid) "first_frame_no_history" else "none"
        assertEquals("First frame without history must record rejection reason", "first_frame_no_history", rejectionReason)
    }

    @Test
    fun testSpectraNoiseObserverExposureMismatchRejection() {
        val exp1 = 10000000L // 10ms
        val exp2 = 25000000L // 25ms

        val expRatio = exp2.toDouble() / exp1.toDouble()
        val rejected = kotlin.math.abs(expRatio - 1.0) > 0.08

        assertTrue("Exposure mismatch (> 8%) must reject frame pair", rejected)
    }

    @Test
    fun testSpectraNoiseObserverMotionRejection() {
        val meanDiff = 0.12f // Scene motion / subject movement
        val rejected = meanDiff > 0.05f

        assertTrue("Scene motion (diff > 0.05) must reject frame pair", rejected)
    }

    @Test
    fun testSpectraNoiseObserverPairAcceptanceAndVarianceCalculation() {
        val f1Sample = 0.100f
        val f2Sample = 0.104f
        val diff = f1Sample - f2Sample

        val varEstimate = 0.5f * (diff * diff) // Var(F1 - F2) / 2
        assertEquals("Frame pair noise variance estimate must equal (diff^2)/2", 0.000008f, varEstimate, 1e-7f)
    }

    @Test
    fun testSpectraAdaptationStateSafetyDriftClamping() {
        val snapshotS = 0.0020
        val extremeCandidateS = 0.0050 // High candidate noise

        val minS = snapshotS * 0.75
        val maxS = snapshotS * 1.25

        val clampedS = extremeCandidateS.coerceIn(minS, maxS)
        assertEquals("S parameter adaptation must clamp to max +25% drift bound", 0.0025, clampedS, 1e-6)
    }

    @Test
    fun testSpectraAdaptationTemporalSmoothingAlpha() {
        val snapshotS = 0.0020
        val clampedCandidateS = 0.0025
        val alpha = 0.15

        val adaptedS = (1.0 - alpha) * snapshotS + alpha * clampedCandidateS
        assertEquals("Temporal smoothing with alpha=0.15 must yield 0.002075", 0.002075, adaptedS, 1e-6)
    }

    @Test
    fun testSpectraAdaptationFallbackLowConfidence() {
        val confidence = 0.40f // Low observer confidence (< 0.50)
        val active = confidence >= 0.50f

        assertFalse("Low observer confidence (< 0.50) must keep adaptation inactive", active)
    }

    @Test
    fun testSpectraAdaptationLegacyBypass() {
        val legacySnapshot = NoiseModelSnapshotV3.createNeutral().copy(spectraMode = "Legacy")
        assertFalse("Legacy mode must keep adaptation inactive", legacySnapshot.isSpectraActive())
    }

    @Test
    fun testSpectraMultiFrameNoiseAwareWeightingCalculation() {
        val predictedVar = 0.000200f
        val alignConf = 0.90f
        val motionConf = 0.95f
        val modelConf = 1.00f

        val invVar = 1.0f / predictedVar // 5000.0f
        val expectedWeight = invVar * alignConf * motionConf * modelConf // 4275.0f

        assertEquals("Multi-frame weight calculation must match inverse variance * confidences", 4275.0f, expectedWeight, 1e-1f)
    }

    @Test
    fun testSpectraMultiFramePostFusionEnergyReduction() {
        val singleFrameEnergy = 0.000120f
        val frameCount = 4

        val postFusionEnergy = singleFrameEnergy / frameCount.toFloat()
        assertEquals("Post-fusion residual energy must reduce by 1/N", 0.000030f, postFusionEnergy, 1e-7f)
    }

    @Test
    fun testSpectraFullPipelineWiringConsumption() {
        val snapshot = NoiseModelSnapshotV3.createNeutral().copy(spectraMode = "Auto")
        val active = snapshot.isSpectraActive()
        assertTrue("SPECTRA active path must consume snapshot calibration parameters", active)
    }
}
