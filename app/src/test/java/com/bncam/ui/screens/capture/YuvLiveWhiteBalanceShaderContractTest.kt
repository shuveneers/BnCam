package com.bncam.ui.screens.capture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YuvLiveWhiteBalanceShaderContractTest {
    private val source = File("src/main/java/com/bncam/ui/screens/capture/FocusPeakingView.kt").readText()

    @Test
    fun `yuv live white balance is applied in linear light`() {
        assertTrue(source.contains("vec3 srgbToLinear(vec3 c)"))
        assertTrue(source.contains("vec3 linearToSrgb(vec3 c)"))
        assertTrue(source.contains("linearRgb *= uLiveWhiteBalance"))
        assertTrue(source.contains("rgb = applyLiveWhiteBalance(rgb)"))
        assertFalse(source.contains("rgb *= uLiveWhiteBalance"))
    }
}
