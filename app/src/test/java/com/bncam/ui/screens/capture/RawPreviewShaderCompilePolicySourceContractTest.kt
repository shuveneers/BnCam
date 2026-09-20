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
    fun `live RAW preview shaders keep performance optimization without shaderc ID overflow`() {
        val cmake = source("src/main/cpp/CMakeLists.txt")

        assertTrue(cmake.contains("find_program(BNCAM_SPIRV_OPT"))
        assertTrue(cmake.contains("NAMES spirv-opt spirv-opt.exe"))
        assertTrue(cmake.contains("if(BNCAM_GLSLC AND BNCAM_SPIRV_OPT)"))

        val fullBlock = cmake.substringAfter("set(BNCAM_RAW_PREVIEW_UNOPT_SPV")
            .substringBefore("# GPU-resident RAW preview output kernel")
        val imageBlock = cmake.substringAfter("set(BNCAM_RAW_PREVIEW_IMAGE_UNOPT_SPV")
            .substringBefore("# Canonical RAW10 unpack kernel")

        for (block in listOf(fullBlock, imageBlock)) {
            // glslc is front-end only here. Its in-process -O path is the NDK 28 path
            // that overflowed SPIRV-Tools' default optimizer ID budget.
            val glslcCommand = block.substringAfter("COMMAND \"${'$'}{BNCAM_GLSLC}\"")
                .substringBefore("COMMAND \"${'$'}{BNCAM_SPIRV_OPT}\"")
            assertFalse(glslcCommand.contains(" -O"))
            assertFalse(glslcCommand.contains(" -Os"))

            // Keep only bounded non-cloning SPIRV-Tools passes. The stock -O/-Os
            // recipes perform exhaustive inlining / loop unrolling and can explode this module.
            val optCommand = block.substringAfter("COMMAND \"${'$'}{BNCAM_SPIRV_OPT}\"")
                .substringBefore("COMMAND \"${'$'}{CMAKE_COMMAND}\"")
            assertTrue(optCommand.contains("--target-env=vulkan1.1"))
            assertTrue(optCommand.contains("--eliminate-dead-functions"))
            assertTrue(optCommand.contains("--eliminate-dead-branches"))
            assertTrue(optCommand.contains("--eliminate-dead-code-aggressive"))
            assertTrue(optCommand.contains("--simplify-instructions"))
            assertTrue(optCommand.contains("--redundancy-elimination"))
            assertTrue(optCommand.contains("--cfg-cleanup"))
            assertTrue(optCommand.contains("--compact-ids"))
            assertFalse(optCommand.contains("--max-id-bound"))
            assertFalse(optCommand.contains(" -O"))
            assertFalse(optCommand.contains(" -Os"))
            assertFalse(optCommand.contains("--inline-entry-points-exhaustive"))
            assertFalse(optCommand.contains("--loop-unroll"))
        }

        assertTrue(cmake.contains("EmbedSpirvConstArray.cmake"))
        assertTrue(cmake.contains("-DSYMBOL=getRawPreviewSpirv"))
        assertTrue(cmake.contains("-DSYMBOL=getRawPreviewImageSpirv"))
    }
}
