package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tracks the ACTIVE single-shot RAW tone path, not the retired sceneKey/global-EV pipeline. */
class AdaptiveRawToneSourceContractTest {
    @Test
    fun `single frame raw uses local noise aware FLLF then one display mapper then profile`() {
        val isp = File("src/main/cpp/IspCore.cpp").readText()
        val fllf = File("src/main/cpp/FastLocalLaplacianPolicy.h").readText()
        val shader = File("src/main/cpp/vulkan/shaders/spectra_tone_resident.comp").readText()

        // RAW/Spectra OFF is a distinct render lane. Camera2 acquisition, not a hidden
        // FLLF sceneKey, owns the exposure. A deliberate profile exposure is downstream.
        assertTrue(isp.contains("const bool neutralDefaultRaw = meta.singleShotRaw && meta.calibration.spectraProcessingMode == 0;"))
        assertTrue(isp.contains("const float exposureGain = profileExposureGain * postRawGain;"))
        assertTrue(isp.contains("request.fllfEnabled = fllfPlan.enabled;"))
        assertTrue(isp.contains("request.rawJpegBaseVibrance = neutralDefaultRaw ? -1.0f : 1.0f;"))

        // Scene-darkness alone cannot move the DC/global base or authorize lift. Missing
        // physical noise-model confidence must not be treated as proof of clean shadows.
        assertTrue(fllf.contains("const float localShadowPressure = out.shadowPressure *"))
        assertTrue(fllf.contains("const float shadowNoiseTrust = fllfSmoothstep("))
        assertTrue(fllf.contains("out.maxLiftEv = std::clamp("))
        assertTrue(fllf.contains("out.maxCompressEv = std::clamp("))
        assertTrue(fllf.contains("bool enabled = false;"))
        assertTrue(shader.contains("float fllfRemapBand("))
        assertTrue(shader.contains("float coarseBand = coarseLog - broadBase;"))
        assertTrue(shader.contains("float correctionEv = fllfJointDelta("))

        assertFalse(shader.contains("applyBroadShadowPlacement"))
        assertFalse(shader.contains("float rawRequestedEv = log2(sceneKey / localLuma);"))

        val runTone = shader.substringAfter("void runTone(uvec2 gid) {")
        val raw = runTone.substringAfter("if (pc.isRawBayer != 0u) {")
            .substringBefore("    } else {")
        val local = raw.indexOf("rgb = applyFllfLocalExposure(gid, rgb);")
        val explicitExposure = raw.indexOf("rgb *= max(0.0, pc.exposureGain);")
        val displayMapper = raw.indexOf("softHighlightTone(rgb) : pbrNeutralToneMapping(rgb)")
        val explicitTone = raw.indexOf("rgb = applyToneLookLut(rgb);")
        val explicitColor = raw.indexOf("rgb = applyProfileColor(rgb, ivec2(gid));")
        assertTrue(local >= 0)
        assertTrue(explicitExposure > local)
        assertTrue(displayMapper > explicitExposure)
        assertTrue(explicitTone > displayMapper)
        assertTrue(explicitColor > explicitTone)
    }
}
