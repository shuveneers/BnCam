package com.bncam.core.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResidualNoiseStateMilestone7Test {
    @Test
    fun `milestone 7 native stages reach final jpeg residual contract`() {
        val state = ResidualNoiseState.fromNativeStats(
            mapOf(
                "spectraResidualDomain" to "FINAL_JPEG_RGB",
                "spectraResidualValueStage" to "FINAL_JPEG_PRE_ENCODE",
                "spectraResidualLastObservedStage" to "JPEG_ENCODED",
                "spectraResidualPropagationStatus" to
                    "MILESTONE_7_FINAL_JPEG_RESIDUAL_STATE_MEASURED_PRE_ENCODE",
                "spectraPostQuantizationStage" to "POST_QUANTIZATION_8BIT",
                "spectraPostQuantizationStatus" to "PROPAGATED",
                "spectraPostQuantizationVarianceY" to "0.0011",
                "spectraFinalJpegStage" to "FINAL_JPEG_PRE_ENCODE",
                "spectraFinalJpegStatus" to "PROPAGATED",
                "spectraFinalJpegVarianceY" to "0.00115",
                "spectraMeasuredPreSharpenVarianceY" to "0.0012",
                "spectraMeasuredPreSharpenSampleCount" to "5000",
                "spectraMeasuredPostIspStage" to "FINAL_JPEG_PRE_ENCODE_AFTER_SHARPEN",
                "spectraMeasuredPostIspVarianceY" to "0.00122",
                "spectraMeasuredPostIspSampleCount" to "5000"
            )
        )

        assertEquals("FINAL_JPEG_RGB", state.domain)
        assertEquals("FINAL_JPEG_PRE_ENCODE", state.valueStage)
        assertEquals("POST_QUANTIZATION_8BIT", state.postQuantization.stage)
        assertEquals("FINAL_JPEG_PRE_ENCODE", state.finalJpeg.stage)
        assertEquals(0.00115, state.finalJpeg.varianceY, 0.0)
        assertEquals(5000L, state.measuredPreSharpenSampleCount)
        assertEquals("FINAL_JPEG_PRE_ENCODE_AFTER_SHARPEN", state.measuredPostIspStage)
        val transitions = state.toTraceMap()["transitions"] as Map<*, *>
        assertTrue(transitions.containsKey("quantization"))
        assertTrue(transitions.containsKey("noiseAwareSharpen"))
    }
}
