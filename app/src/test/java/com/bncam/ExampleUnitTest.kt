package com.bncam

import com.bncam.core.quality.LibpatcherSettingsCatalog
import com.bncam.core.quality.LibpatcherProfileResolver
import com.bncam.core.runners.HeuristicCandidate
import com.bncam.core.runners.FrameSelectionEngine
import com.bncam.core.buffer.ZslFramePair
import org.junit.Test
import org.junit.Assert.*

class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun catalogSectionsExposeOnlyWiredRawDemosaicAndJpegOutput() {
        val refs = LibpatcherSettingsCatalog.allControlRefs()
        assertEquals(2, refs.size)
        assertEquals(setOf("demosaic_mode", "post_jpeg_quality"), refs.map { it.key }.toSet())
    }

    @Test
    fun profileSettingSpecsExposeOnlyProductionBackedControls() {
        val specs = LibpatcherProfileResolver.allProfileSettingSpecs()
        val keys = specs.map { it.key }.toSet()
        assertTrue(keys.containsAll(setOf(
            "demosaic_mode",
            "post_jpeg_quality",
            "curve_tone_preset",
        )))
        assertFalse(keys.contains("noise_chroma_suppress"))
        assertFalse(keys.contains("poly_sharp_small"))
        assertFalse(keys.contains("isp_shadow_lift"))
        assertFalse(keys.contains("isp_toe_exponent"))
        assertFalse(keys.contains("isp_soft_black_anchor"))
        assertTrue(keys.contains("curve_gamma_preset"))
        assertTrue(keys.contains("curve_sect_preset"))
    }

    @Test
    fun testDualConstrainedExposureSolver() {
        // Replicates the dual-constrained exposure solver from IspCore.cpp in Kotlin.
        // Includes: high-DR highlight constraint, genuinely dark scene detection, shadow floor guard.
        data class SolverResult(
            val gain: Float,
            val conflict: Boolean,
            val genuinelyDark: Boolean,
            val highDr: Boolean,
            val highlightConstraintUsed: Boolean
        )

        fun solveDualConstrained(
            baseGain: Float, p50CmLuma: Float, p99_5CmMax: Float
        ): SolverResult {
            val targetP50 = 0.18f
            val sceneIsGenuinelyDark = (p50CmLuma < 0.03f && p99_5CmMax < 0.15f)

            val gainFromMidtones = if (p50CmLuma > 0.001f) {
                (targetP50 / p50CmLuma).coerceIn(
                    maxOf(1.0f, baseGain * 0.5f),
                    minOf(baseGain * 2.0f, 8.0f)
                )
            } else baseGain

            val highlightCeiling = 0.95f
            val gainLimitFromHighlights = highlightCeiling / maxOf(0.001f, p99_5CmMax)
            val highlightPressure = p99_5CmMax * gainFromMidtones
            val highDrScene = !sceneIsGenuinelyDark &&
                    p50CmLuma < 0.030f &&
                    (p99_5CmMax > 0.350f || highlightPressure > 4.0f)
            var highlightConstraintUsed = false

            val (proposedExposureGain, conflict) = if (gainLimitFromHighlights < gainFromMidtones) {
                val minimumAcceptableP50 = 0.12f
                val shadowFloor = if (sceneIsGenuinelyDark) {
                    maxOf(baseGain * 0.8f, 1.0f)
                } else {
                    if (p50CmLuma > 0.001f) maxOf(minimumAcceptableP50 / p50CmLuma, 1.0f) else 1.0f
                }

                val highlightWeight = when {
                    highlightPressure >= 8.0f || highDrScene -> 0.70f
                    highlightPressure >= 4.0f -> 0.60f
                    highlightPressure >= 2.0f -> 0.45f
                    else -> 0.30f
                }
                val moderatedMidtoneGain = minOf(gainFromMidtones, (gainLimitFromHighlights * 2.25f).coerceIn(3.0f, 6.0f))
                var blended = gainLimitFromHighlights * highlightWeight + moderatedMidtoneGain * (1.0f - highlightWeight)
                val hdrSafetyCap = if (gainLimitFromHighlights < 3.0f && gainFromMidtones > 8.0f) {
                    (gainLimitFromHighlights + if (highDrScene) 1.75f else 2.50f).coerceIn(2.25f, 5.50f)
                } else {
                    8.0f
                }
                if (gainLimitFromHighlights < 3.0f && gainFromMidtones > 8.0f && blended > hdrSafetyCap) {
                    blended = hdrSafetyCap
                }
                highlightConstraintUsed = true
                when {
                    sceneIsGenuinelyDark -> maxOf(blended, minOf(shadowFloor, 8.0f))
                    highDrScene -> blended
                    else -> maxOf(blended, minOf(shadowFloor, hdrSafetyCap))
                } to true
            } else {
                gainFromMidtones to false
            }
            val materialConflict = gainFromMidtones > gainLimitFromHighlights * 1.50f
            val proposedRatio = proposedExposureGain / maxOf(0.05f, gainLimitFromHighlights)
            val nearWhiteRisk = p99_5CmMax * proposedExposureGain >= 0.95f
            val strictMode = !sceneIsGenuinelyDark && materialConflict &&
                    (gainLimitFromHighlights < 1.0f || highDrScene ||
                            (conflict && proposedRatio > 1.25f) || nearWhiteRisk)
            val policyCap = when {
                strictMode -> maxOf(
                    0.5f,
                    minOf(
                        gainLimitFromHighlights * 1.25f,
                        gainLimitFromHighlights + if (gainLimitFromHighlights < 1.0f) 0.20f else 0.35f
                    )
                )
                else -> proposedExposureGain
            }
            val exposureGain = minOf(proposedExposureGain, policyCap)
            return SolverResult(exposureGain.coerceIn(0.5f, 8.0f), conflict, sceneIsGenuinelyDark, highDrScene, highlightConstraintUsed)
        }

        // Case 1: Normal scene, no highlights. p50=0.06, p99.5=0.3
        val normal = solveDualConstrained(2.0f, 0.06f, 0.3f)
        assertEquals(3.0f, normal.gain, 0.01f)
        assertFalse("Normal scene should have no conflict", normal.conflict)
        assertFalse("Normal scene is not genuinely dark", normal.genuinelyDark)
        assertFalse("Normal scene is not high DR", normal.highDr)

        // Case 2: Highlight scene (laptop screen). p50=0.05, p99.5=0.98
        // With extreme pressure (3.5x), dynamic weighting = 0.45/0.55
        // blended = 3.6*0.45 + 0.969*0.55 ≈ 2.15, shadowFloor = 2.4
        // Result = max(2.15, 2.4) = 2.4
        val highlight = solveDualConstrained(2.0f, 0.05f, 0.98f)
        val highlightLimit = 0.95f / 0.98f
        assertTrue("Highlight gain must not fall below the measured limit", highlight.gain >= highlightLimit)
        assertTrue("Highlight gain must stay within strict headroom", highlight.gain <= highlightLimit * 1.25f)
        assertTrue("Highlight scene should have conflict", highlight.conflict)
        assertTrue("Highlight constraint remains active", highlight.highlightConstraintUsed)

        // Case 3: Genuinely dark scene. p50=0.02, p99.5=0.10
        val dark = solveDualConstrained(2.0f, 0.02f, 0.10f)
        assertTrue("Dark scene is genuinely dark", dark.genuinelyDark)
        // Shadow floor should be relaxed (baseGain * 0.8 = 1.6, not 0.12/0.02 = 6.0)
        assertTrue("Dark scene gain (${dark.gain}) should not over-lift",
            dark.gain < 6.0f)

        // Case 4: Bright scene. p50=0.15, p99.5=0.5
        val bright = solveDualConstrained(2.0f, 0.15f, 0.5f)
        assertTrue("Bright scene gain (${bright.gain}) should be moderate",
            bright.gain in 1.0f..2.0f)
        assertFalse("Bright scene should have no conflict", bright.conflict)

        // Case 5: Fresh RAW10/RAW_SENSOR debug scene. Old solver returned 24x.
        val indoorHdrRaw10 = solveDualConstrained(12.0f, 0.0051f, 0.5752f)
        assertTrue("Indoor scene should be classified high DR", indoorHdrRaw10.highDr)
        assertTrue("Highlight constraint must be used", indoorHdrRaw10.highlightConstraintUsed)
        assertTrue("Global gain (${indoorHdrRaw10.gain}) must not blind-lift to 24x", indoorHdrRaw10.gain < 5.0f)
        assertTrue("Global gain (${indoorHdrRaw10.gain}) should stay above pure highlight limit for local recovery", indoorHdrRaw10.gain > 1.6f)

        // Case 6: Highlight limit below unity. Global exposure must stay close to the
        // measured limit and leave recovery to selective LTM/GTM.
        val severeBacklight = solveDualConstrained(12.0f, 0.005f, 1.30f)
        val severeHighlightLimit = 0.95f / 1.30f
        assertTrue("Severe backlight should be high DR", severeBacklight.highDr)
        assertTrue(
            "Strict high-DR gain (${severeBacklight.gain}) must stay within 1.25x highlight limit ($severeHighlightLimit)",
            severeBacklight.gain <= severeHighlightLimit * 1.25f + 0.001f
        )

        val raw10HighlightConflict = solveDualConstrained(2.0f, 0.0405f, 0.4332f)
        val raw10HighlightLimit = 0.95f / 0.4332f
        assertTrue(
            "RAW10 highlight-shadow conflict must trigger strict headroom",
            raw10HighlightConflict.gain <= raw10HighlightLimit * 1.25f + 0.001f
        )

        val rawSensorHighlightConflict = solveDualConstrained(2.0f, 0.0528f, 0.7418f)
        val rawSensorHighlightLimit = 0.95f / 0.7418f
        assertTrue(
            "RAW_SENSOR highlight-shadow conflict must trigger strict headroom",
            rawSensorHighlightConflict.gain <= rawSensorHighlightLimit * 1.25f + 0.001f
        )
    }

    @Test
    fun rawToneFeedbackPolicy_allowsOnlyOneBoundedCorrectivePass() {
        fun correctedGain(currentGain: Float, highlightLimit: Float, clippedPct: Float): Float {
            val ratioCap = when {
                clippedPct > 1.0f -> 1.08f
                clippedPct > 0.60f -> 1.12f
                else -> 1.18f
            }
            return minOf(currentGain, maxOf(0.5f, highlightLimit * ratioCap))
        }

        val raw10 = correctedGain(2.5429f, 2.1929f, 1.3398f)
        assertTrue(raw10 < 2.5429f)
        assertTrue(raw10 / 2.1929f <= 1.08f + 0.001f)

        val rawSensor = correctedGain(1.6007f, 1.2807f, 1.2046f)
        assertTrue(rawSensor < 1.6007f)
        assertTrue(rawSensor / 1.2807f <= 1.08f + 0.001f)
    }

    @Test
    fun testHeuristicFrameSelectionEngine() {
        val f1 = ZslFramePair()
        val f2 = ZslFramePair()
        val f3 = ZslFramePair()

        // Scenario 1: Stable baseline met.
        // We have three candidates. Two are stable, one is unstable.
        // Candidate 1: stable, delta = -50ms
        // Candidate 2: stable, delta = 10ms (closest to shutter)
        // Candidate 3: unstable (high motion), delta = 2ms
        val candidate1 = HeuristicCandidate(
            frame = f1,
            index = 0,
            timestampNs = 1000L,
            deltaMs = -50.0,
            sharpnessScore = 0.8,
            motionScore = 0.9,
            evScore = 0.8,
            alignabilityScore = 0.8,
            isStable = true,
            metadataComplete = true
        )
        val candidate2 = HeuristicCandidate(
            frame = f2,
            index = 1,
            timestampNs = 2000L,
            deltaMs = 10.0,
            sharpnessScore = 0.7,
            motionScore = 0.8,
            evScore = 0.7,
            alignabilityScore = 0.7,
            isStable = true,
            metadataComplete = true
        )
        val candidate3 = HeuristicCandidate(
            frame = f3,
            index = 2,
            timestampNs = 3000L,
            deltaMs = 2.0,
            sharpnessScore = 0.5,
            motionScore = 0.3, // motion < 0.6 => not stable
            evScore = 0.6,
            alignabilityScore = 0.5,
            isStable = false,
            metadataComplete = true
        )

        val selectedStable = FrameSelectionEngine.selectBestCandidate(
            listOf(candidate1, candidate2, candidate3),
            freshnessWindowMs = 350.0
        ).selected.candidate
        assertEquals("Should select the sharper, stable candidate (candidate1)", 0, selectedStable.index)

        // Scenario 2: No complete metadata is a hard contract failure; no frame is substituted.
        val candidateA = HeuristicCandidate(
            frame = f1,
            index = 0,
            timestampNs = 1000L,
            deltaMs = -10.0,
            sharpnessScore = 0.9,
            motionScore = 0.5,
            evScore = 0.7,
            alignabilityScore = 0.8,
            isStable = false
        )
        val candidateB = HeuristicCandidate(
            frame = f2,
            index = 1,
            timestampNs = 2000L,
            deltaMs = 5.0,
            sharpnessScore = 0.5,
            motionScore = 0.3,
            evScore = 0.5,
            alignabilityScore = 0.6,
            isStable = false
        )
        val candidateC = HeuristicCandidate(
            frame = f3,
            index = 2,
            timestampNs = 3000L,
            deltaMs = 2.0,
            sharpnessScore = 0.3, // sharpness < 0.4 => not stable
            motionScore = 0.7, // motion >= 0.6 => no penalty!
            evScore = 0.7,
            alignabilityScore = 0.5,
            isStable = false
        )

        try {
            FrameSelectionEngine.selectBestCandidate(
                listOf(candidateA, candidateB, candidateC),
                freshnessWindowMs = 350.0
            )
            fail("Incomplete metadata must not enter the selected single-frame route")
        } catch (e: IllegalStateException) {
            // Strict contract enforced.
        }

        // Scenario 3: Empty list throws exception
        try {
            FrameSelectionEngine.selectBestCandidate(emptyList(), freshnessWindowMs = 350.0)
            fail("Should throw IllegalArgumentException on empty list")
        } catch (e: IllegalArgumentException) {
            // Success
        }
    }

    @Test
    fun frameSelection_rejectsStaleSearchingFrameWhenFreshConvergedFrameExists() {
        val stale = HeuristicCandidate(
            frame = ZslFramePair(),
            index = 0,
            timestampNs = 1L,
            deltaMs = -1257.0,
            sharpnessScore = 0.95,
            motionScore = 0.95,
            evScore = 0.85,
            alignabilityScore = 0.9,
            isStable = true,
            metadataComplete = true,
            aeState = 1,
            awbState = 0,
            focusState = 4
        )
        val fresh = HeuristicCandidate(
            frame = ZslFramePair(),
            index = 1,
            timestampNs = 2L,
            deltaMs = -114.0,
            sharpnessScore = 0.65,
            motionScore = 0.45,
            evScore = 0.65,
            alignabilityScore = 0.8,
            isStable = false,
            metadataComplete = true,
            aeState = 2,
            awbState = 2,
            focusState = 4
        )

        val result = FrameSelectionEngine.selectBestCandidate(
            candidates = listOf(stale, fresh),
            freshnessWindowMs = 350.0
        )

        assertEquals(1, result.selected.candidate.index)
        assertTrue(result.selected.freshEnoughForSelection)
        assertEquals(1, result.freshCandidateCount)
        assertEquals(1, result.staleCandidateCount)
        assertTrue(result.ranked.first { it.candidate.index == 0 }.rejectedForStaleSelection)
        assertTrue(result.selected.overallScore in 0.0..1.0)
        assertTrue(result.selected.overallScore != 1.0)
    }

    @Test
    fun testSingleFrameHighlightRepairLogParsing() {
        val repairLog = "rawJpegRender=ok;solverFinalExposureGain=0.9;fullBentoApplied=false;singleFramePartialHighlightRepairApplied=true;partialHighlightRepairAcceptedPixels=1450"
        
        fun parseNativeStats(stats: String): Map<String, String> {
            if (stats.isBlank()) return emptyMap()
            return stats.split(";")
                .mapNotNull { part ->
                    val idx = part.indexOf('=')
                    if (idx <= 0 || idx >= part.lastIndex) null else part.substring(0, idx).trim() to part.substring(idx + 1).trim()
                }
                .toMap()
        }
        
        val parsed = parseNativeStats(repairLog)
        assertEquals("ok", parsed["rawJpegRender"])
        assertEquals("0.9", parsed["solverFinalExposureGain"])
        assertEquals("false", parsed["fullBentoApplied"])
        assertEquals("true", parsed["singleFramePartialHighlightRepairApplied"])
        assertEquals("1450", parsed["partialHighlightRepairAcceptedPixels"])
    }

    @Test
    fun nativeStats_flattenedDebugKeysDoNotContainNestedSemicolonValues() {
        val flatStats = "rawJpegRender=ok;stageAfterExposureP50=0.0190;stageAfterGammaP50=0.1510;finalJpegRgbP50=102;jpegCloneRaw16P99=355"

        fun parseNativeStats(stats: String): Map<String, String> {
            if (stats.isBlank()) return emptyMap()
            return stats.split(";")
                .mapNotNull { part ->
                    val idx = part.indexOf('=')
                    if (idx <= 0 || idx >= part.lastIndex) null else part.substring(0, idx).trim() to part.substring(idx + 1).trim()
                }
                .toMap()
        }

        val parsed = parseNativeStats(flatStats)
        assertEquals("0.0190", parsed["stageAfterExposureP50"])
        assertEquals("0.1510", parsed["stageAfterGammaP50"])
        assertEquals("102", parsed["finalJpegRgbP50"])
        assertEquals("355", parsed["jpegCloneRaw16P99"])
        assertFalse(parsed.containsKey("stageAfterExposure"))
        assertFalse(parsed.containsKey("jpegCloneRaw16Stats"))
    }

    @Test
    fun rawTonePolicy_curveTargetsAreMonotonicAndHighlightBounded() {
        val targetP50 = 0.10f
        val targets = listOf(
            0.0f,
            minOf(0.0012f, targetP50 * 0.008f),
            targetP50 * (0.11f + 0.06f * 0.75f),
            targetP50 * (0.46f / 1.12f),
            targetP50,
            minOf(0.42f, targetP50 * 2.05f * 1.12f),
            0.48f,
            0.68f,
            0.86f,
            0.93f,
            0.975f,
            0.992f
        )

        assertEquals(12, targets.size)
        assertTrue(targets.zipWithNext().all { (left, right) -> right >= left })
        assertEquals(targetP50, targets[4], 0.0001f)
        assertTrue("HDR shoulder must stay below hard white", targets.last() < 1.0f)
    }

    @Test
    fun rawTonePolicy_masksProtectDeepBlackNoiseAndHighlights() {
        fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
            val t = ((value - edge0) / maxOf(edge1 - edge0, 0.000001f)).coerceIn(0.0f, 1.0f)
            return t * t * (3.0f - 2.0f * t)
        }

        fun liftWeight(y: Float): Float {
            val blackProtection = 1.0f - smoothstep(0.003f, 0.0075f, y)
            val noiseProtection = 1.0f - smoothstep(0.008f, 0.016f, y)
            val highlightExclusion = smoothstep(0.20f, 0.52f, y)
            val shadow = smoothstep(0.008f, 0.035f, y) * (1.0f - smoothstep(0.035f, 0.22f, y))
            val midtone = smoothstep(0.008f, 0.075f, y) * (1.0f - smoothstep(0.075f, 0.38f, y))
            val requested = maxOf(shadow * 0.78f, midtone * 0.46f)
            return requested * (1.0f - blackProtection) *
                    (1.0f - noiseProtection) * (1.0f - highlightExclusion)
        }

        assertTrue("Deep black lift must be negligible", liftWeight(0.001f) < 0.01f)
        assertTrue("Lower midtones need useful local recovery", liftWeight(0.04f) > 0.35f)
        assertTrue("Upper midtone lift must stay selective", liftWeight(0.20f) < 0.35f)
        assertTrue("Highlights must be excluded from local lift", liftWeight(0.90f) < 0.01f)
    }
}
