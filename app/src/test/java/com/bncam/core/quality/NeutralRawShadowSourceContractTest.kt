package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeutralRawShadowSourceContractTest {
    @Test
    fun `raw default uses local FLLF without an independent broad shadow lift`() {
        val shader = File("src/main/cpp/vulkan/shaders/spectra_tone_resident.comp").readText()
        assertFalse(shader.contains("applyBroadShadowPlacement"))

        val runTone = shader.substringAfter("void runTone(uvec2 gid) {")
        val raw = runTone.substringAfter("if (pc.isRawBayer != 0u) {")
            .substringBefore("    } else {")
        val fllf = raw.indexOf("rgb = applyFllfLocalExposure(gid, rgb);")
        val profileExposure = raw.indexOf("rgb *= max(0.0, pc.exposureGain);")
        val display = raw.indexOf("softHighlightTone(rgb) : pbrNeutralToneMapping(rgb)")
        val userTone = raw.indexOf("rgb = applyToneLookLut(rgb);")
        assertTrue(fllf >= 0)
        assertTrue(profileExposure > fllf)
        assertTrue(display > profileExposure)
        assertTrue(userTone > display)
    }
}
