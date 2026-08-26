package com.bncam.ui.screens.capture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YuvCaptureWhiteBalanceContractTest {
    private fun source(path: String): String {
        val roots = listOf(File("."), File("app"))
        return roots.asSequence().map { File(it, path) }.firstOrNull { it.isFile }?.readText()
            ?: error("Missing source file: $path")
    }

    @Test
    fun `vulkan yuv jpeg white balance operates in linear light`() {
        val shader = source("src/main/cpp/vulkan/shaders/yuv_single_frame_isp.comp")
        assertTrue(shader.contains("float srgbToLinearComponent(float c)"))
        assertTrue(shader.contains("float linearToSrgbComponent(float c)"))
        assertTrue(shader.contains("linearRgb *= vec3(pc.wbRed, pc.wbGreen, pc.wbBlue)"))
        assertFalse(shader.contains("rgb.r * pc.wbRed"))
        assertFalse(shader.contains("rgb.g * pc.wbGreen"))
        assertFalse(shader.contains("rgb.b * pc.wbBlue"))
    }

    @Test
    fun `cpu yuv jpeg failsafe matches linear light wb domain`() {
        val native = source("src/main/cpp/native-lib.cpp")
        assertTrue(native.contains("linearToSrgbComponent(srgbToLinearComponent(b) * wbB)"))
        assertTrue(native.contains("linearToSrgbComponent(srgbToLinearComponent(g) * wbG)"))
        assertTrue(native.contains("linearToSrgbComponent(srgbToLinearComponent(r) * wbR)"))
        assertFalse(native.contains("(row[x][0] / 255.0f) * wbB"))
    }
}
