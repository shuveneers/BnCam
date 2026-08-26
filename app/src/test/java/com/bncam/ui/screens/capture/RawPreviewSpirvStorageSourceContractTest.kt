package com.bncam.ui.screens.capture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawPreviewSpirvStorageSourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/CMakeLists.txt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun source(relative: String): String = File(appDir, relative).readText()

    @Test
    fun `RAW preview shaders use zero-runtime-init SPIR-V storage`() {
        val cmake = source("src/main/cpp/CMakeLists.txt")
        val generator = source("src/main/cpp/vulkan/cmake/EmbedSpirvConstArray.cmake")

        val rawPreviewBlock = cmake.substringAfter("set(BNCAM_RAW_PREVIEW_SPV")
            .substringBefore("# GPU-resident RAW preview output kernel")
        val rawPreviewImageBlock = cmake.substringAfter("set(BNCAM_RAW_PREVIEW_IMAGE_SPV")
            .substringBefore("# Canonical RAW10 unpack kernel")

        assertTrue(rawPreviewBlock.contains("EmbedSpirvConstArray.cmake"))
        assertTrue(rawPreviewImageBlock.contains("EmbedSpirvConstArray.cmake"))
        assertTrue(generator.contains("inline constexpr std::array"))
        assertTrue(generator.contains("noexcept"))
        assertFalse(generator.contains("#include <vector>"))
        assertFalse(generator.contains("static const std::vector"))
        assertFalse(generator.contains("std::initializer_list"))
    }

    @Test
    fun `other Vulkan shader embedding remains unchanged in this repair`() {
        val cmake = source("src/main/cpp/CMakeLists.txt")
        val spectraBlock = cmake.substringAfter("set(BNCAM_RESIDENT_POST_DEMOSAIC_SPV")
            .substringBefore("# Lightweight fused live RAW viewfinder kernel")

        assertTrue(spectraBlock.contains("EmbedSpirv.cmake"))
        assertFalse(spectraBlock.contains("EmbedSpirvConstArray.cmake"))
    }
}
