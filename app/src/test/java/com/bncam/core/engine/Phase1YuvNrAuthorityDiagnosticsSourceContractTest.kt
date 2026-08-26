package com.bncam.core.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase1YuvNrAuthorityDiagnosticsSourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/cpp/native-lib.cpp").isFile }
        ?: error("Cannot locate app module")

    @Test
    fun productionVulkanYuvReportsResolvedNrAuthorityInsteadOfHardcodedOff() {
        val native = File(appDir, "src/main/cpp/native-lib.cpp").readText()
        assertTrue(native.contains("yuvResolvedGpuLumaNrBlend"))
        assertTrue(native.contains("yuvResolvedGpuChromaNrBlend"))
        assertTrue(native.contains("request.profileNrLumaBlend > 0.002f || request.profileNrChromaBlend > 0.002f"))
        assertTrue(native.contains("yuvDenoiseStrengthResolved ="))
    }
}
