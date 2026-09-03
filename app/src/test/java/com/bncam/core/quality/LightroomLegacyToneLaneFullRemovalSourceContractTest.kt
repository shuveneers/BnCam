package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LightroomLegacyToneLaneFullRemovalSourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/engine/ImageUtils.kt").isFile }
        ?: error("Cannot locate app module")

    private fun source(relative: String): String = File(appDir, relative).readText()

    @Test
    fun `legacy shadow toe and soft black lane is absent from production`() {
        val production = listOf(
            "src/main/java/com/bncam/core/capture/CaptureRecipe.kt",
            "src/main/java/com/bncam/core/capture/CaptureRecipeFactory.kt",
            "src/main/java/com/bncam/core/quality/RenderQualityConfig.kt",
            "src/main/java/com/bncam/core/engine/ImageUtils.kt",
            "src/main/java/com/bncam/core/runners/SingleFrameRunner.kt",
            "src/main/java/com/bncam/core/runners/MultiFrameRunner.kt",
            "src/main/cpp/NativeRenderQualityConfig.h",
            "src/main/cpp/native-lib.cpp",
            "src/main/cpp/IspCore.cpp",
            "src/main/cpp/GpuIsp.cpp"
        ).joinToString("\n") { source(it) }

        listOf(
            "shadowLift",
            "toeExponent",
            "softBlackPointAnchor",
            "userSoftBlackAnchor",
            "u_shadowLift",
            "u_toeExponent",
            "u_softBlackPointAnchor"
        ).forEach { legacy -> assertFalse("Legacy tone identifier still present: $legacy", production.contains(legacy)) }
    }

    @Test
    fun `yuv diagnostics state legacy lane removal rather than fake shadow application`() {
        val nativeLib = source("src/main/cpp/native-lib.cpp")
        assertTrue(nativeLib.contains("legacyNativeToneLaneRemoved=true"))
        assertFalse(nativeLib.contains("shadowLiftApplied=true"))
    }
}
