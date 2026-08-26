package com.bncam.core.quality

import java.io.File
import org.junit.Test
import org.junit.Assert.assertTrue

class Phase3NoiseCalibrationSeparationSourceContractTest {
    @Test
    fun `spectra processing authority is separate from physical noise calibration`() {
        val calibration = File("src/main/java/com/bncam/core/quality/SensorCalibration.kt").readText()
        val nativeConfig = File("src/main/cpp/NativeRenderQualityConfig.h").readText()
        val nativeBridge = File("src/main/cpp/native-lib.cpp").readText()
        val isp = File("src/main/cpp/IspCore.cpp").readText()
        val imageUtils = File("src/main/java/com/bncam/core/engine/ImageUtils.kt").readText()

        assertTrue(calibration.contains("NoiseModelAuthorityPolicy.resolve("))
        assertTrue(calibration.contains("spectraProcessingRequested = profileNoiseTuning?.spectraEnabled ?: false"))
        assertTrue(calibration.contains("val effectiveNoiseMode = noiseAuthority.physicalNoiseMode"))
        assertTrue(calibration.contains("spectraMode = noiseAuthority.spectraProcessingMode"))
        assertTrue(nativeConfig.contains("spectraProcessingMode"))
        assertTrue(nativeBridge.contains("spectraProcessingEnabled == JNI_TRUE"))
        assertTrue(isp.contains("meta.calibration.spectraProcessingMode != 0"))
        assertTrue(isp.contains("meta.calibration.noiseModelMode == 0"))
        assertTrue(imageUtils.contains("physicalDynamicIsoCoeff +"))
        assertTrue(imageUtils.contains("profileNoiseTuning?.spectraEnabled == true"))
        assertTrue(imageUtils.contains("profileDynamicIsoBoost.coerceIn(0.0f, 1.0f)"))
    }
}
