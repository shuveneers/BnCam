package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectraRenderRequestAuthoritySourceContractTest {
    @Test
    fun `render JNI request comes from immutable profile intent not downstream calibration state`() {
        val source = File("src/main/java/com/bncam/core/engine/ImageUtils.kt").readText()

        assertTrue(
            "render handoff must snapshot the profile-owned SPECTRA request",
            source.contains(
                "val spectraRequestedByProfile = qualityConfig?.profileNoiseTuning?.spectraEnabled == true"
            )
        )
        assertTrue(
            "JNI must receive the profile-owned request intent",
            source.contains("spectraProcessingEnabled = spectraRequestedByProfile")
        )
        assertFalse(
            "downstream FinalSensorCalibration must not own the JNI request intent",
            source.contains("spectraProcessingEnabled = finalCal?.spectraProcessingEnabled == true")
        )
    }
}
