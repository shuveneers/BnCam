package com.bncam.ui.screens.capture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawPreviewShaderCompilePolicySourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/CMakeLists.txt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun source(relative: String): String = File(appDir, relative).readText()

    @Test
    fun `live RAW preview shaders use build optimized SPIR-V policy`() {
        val cmake = source("src/main/cpp/CMakeLists.txt")

        val rawPreviewCommand = cmake
            .substringBefore("${'$'}{CMAKE_CURRENT_SOURCE_DIR}/vulkan/shaders/raw_preview.comp")
            .substringAfterLast("COMMAND \"${'$'}{BNCAM_GLSLC}\"")
        val rawPreviewImageCommand = cmake
            .substringBefore("${'$'}{CMAKE_CURRENT_SOURCE_DIR}/vulkan/shaders/raw_preview_image.comp")
            .substringAfterLast("COMMAND \"${'$'}{BNCAM_GLSLC}\"")

        assertTrue(rawPreviewCommand.contains("--target-env=vulkan1.1 -O"))
        assertTrue(rawPreviewImageCommand.contains("--target-env=vulkan1.1 -O"))
        assertFalse(rawPreviewCommand.contains("--target-env=vulkan1.1 -O0"))
        assertFalse(rawPreviewCommand.contains("--target-env=vulkan1.1 -Os"))
        assertFalse(rawPreviewImageCommand.contains("--target-env=vulkan1.1 -O0"))
        assertFalse(rawPreviewImageCommand.contains("--target-env=vulkan1.1 -Os"))

        // Delta 46's static-array embedding remains mandatory; this delta only changes
        // the runtime pipeline path is unchanged; only build-time SPIR-V optimization is enabled for the two live-preview modules.
        assertTrue(cmake.contains("EmbedSpirvConstArray.cmake"))
        assertTrue(cmake.contains("-DSYMBOL=getRawPreviewSpirv"))
        assertTrue(cmake.contains("-DSYMBOL=getRawPreviewImageSpirv"))
    }
}
