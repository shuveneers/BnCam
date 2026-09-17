package com.bncam.core.debug

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class Phase15VisualEffectDebugSourceContractTest {
    @Test
    fun `raw debug exposes one compact phase15 ab signature`() {
        val source = File("src/main/java/com/bncam/core/debug/ShotLogger.kt").readText()
        val required = listOf(
            "Phase 15 Visual A/B Signature",
            "Physical selected source",
            "Physical effective source",
            "Physical preset",
            "Effective Noise ISO",
            "Physical S R/Gr/Gb/B",
            "Physical O R/Gr/Gb/B",
            "Neural enabled",
            "Neural luma",
            "Neural chroma",
            "Neural detail protection",
            "Neural low-frequency cleanup",
            "Neural master authority",
            "Neural adaptive response",
            "Neural inference published",
            "Neural pixel mutation",
            "Input sigma CFA",
            "Correction RMS CFA",
            "Posterior/input variance ratio CFA",
            "Neural effect status"
        )
        required.forEach { token ->
            assertTrue(source.contains(token), "Missing Phase-15 debug token: $token")
        }
    }

    @Test
    fun `phase15 debug reads measured neural effect telemetry rather than invented visual score`() {
        val source = File("src/main/java/com/bncam/core/debug/ShotLogger.kt").readText()
        assertTrue(source.contains("spectraNeuralCorrectionRmsCfa"))
        assertTrue(source.contains("spectraNeuralPosteriorToInputVarianceRatioCfa"))
        assertTrue(source.contains("spectraNeuralMeanInputSigmaCfa"))
        assertTrue(!source.contains("Phase15VisualScore"))
        assertTrue(!source.contains("phase15Winner"))
    }
    @Test
    fun `phase15 neural writeback uses physical snr evidence exactly once`() {
        val shader = File("src/main/cpp/vulkan/shaders/neural_writeback.comp").readText()
        val authority = File("src/main/cpp/SpectraNeuralAdaptiveAuthority.h").readText()

        assertTrue(shader.contains("vec4 adaptiveGain=noiseEvidence;"))
        assertTrue(!shader.contains("phase6AdaptiveGain"))
        assertTrue(!shader.contains("sqrt(clamp(invSnr"))
        assertTrue(authority.contains("return neuralAdaptiveNoiseEvidence(normalizedSignal, sigma);"))
        assertTrue(!authority.contains("phase6Scale"))
    }

}
