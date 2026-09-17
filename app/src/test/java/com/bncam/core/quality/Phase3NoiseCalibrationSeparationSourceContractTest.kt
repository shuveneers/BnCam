package com.bncam.core.quality

import java.io.File
import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class Phase3NoiseCalibrationSeparationSourceContractTest {
    @Test
    fun `spectra processing authority is separate from physical noise calibration`() {
        val calibration = File("src/main/java/com/bncam/core/quality/SensorCalibration.kt").readText()
        val nativeConfig = File("src/main/cpp/NativeRenderQualityConfig.h").readText()
        val nativeBridge = File("src/main/cpp/native-lib.cpp").readText()
        val isp = File("src/main/cpp/IspCore.cpp").readText()
        val imageUtils = File("src/main/java/com/bncam/core/engine/ImageUtils.kt").readText()

        assertFalse(calibration.contains("NoiseModelAuthorityPolicy.resolve("))
        assertTrue(calibration.contains("spectraProcessingRequested = profileNoiseTuning?.spectraEnabled ?: false"))
        assertTrue(calibration.contains("val effectiveNoiseMode = if (cameraNoiseCanonical?.isNotEmpty() == true) \"Auto\" else \"Off\""))
        assertTrue(calibration.contains("val spectraProcessingEnabled = spectraProcessingRequested"))
        assertTrue(calibration.contains("spectraMode = if (spectraProcessingEnabled) \"On\" else \"Off\""))
        assertFalse(calibration.contains("noiseAuthority."))
        assertTrue(nativeConfig.contains("spectraProcessingMode"))
        assertTrue(nativeBridge.contains("spectraProcessingEnabled == JNI_TRUE"))
        assertTrue(isp.contains("meta.calibration.spectraProcessingMode != 0"))
        assertTrue(imageUtils.contains("profileNoiseTuning?.spectraEnabled == true"))
    }
}
