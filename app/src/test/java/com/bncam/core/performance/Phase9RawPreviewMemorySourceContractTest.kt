package com.bncam.core.performance

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Phase9RawPreviewMemorySourceContractTest {
    private val appDir = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .firstOrNull { File(it, "src/main/java/com/bncam/ui/screens/capture/RawPreviewRenderer.kt").isFile }
        ?: error("Unable to locate app module")

    @Test
    fun `raw preview host rgba storage is lazy and shared for resident output`() {
        val renderer = File(appDir, "src/main/java/com/bncam/ui/screens/capture/RawPreviewRenderer.kt").readText()
        val slotDeclaration = renderer.substringAfter("private class OutputSlot").substringBefore("private val allOutputSlots")
        assertFalse(slotDeclaration.contains("val rgba: ByteBuffer = ByteBuffer.allocateDirect(MAX_OUTPUT_BYTES)"))
        assertTrue(slotDeclaration.contains("private var cpuRgba: ByteBuffer? = null"))
        assertTrue(slotDeclaration.contains("fun ensureCpuRgbaBuffer(): ByteBuffer"))
        assertTrue(renderer.contains("private var gpuFallbackScratchRgba: ByteBuffer? = null"))
        assertTrue(renderer.contains("ensureGpuFallbackScratchRgba().apply { clear() }"))
        assertTrue(renderer.contains("slot.ensureCpuRgbaBuffer().apply { clear() }"))
        assertTrue(renderer.contains("transitionFrameDropped=true"))
    }

    @Test
    fun `memory optimization preserves fixed preview quality and releases inactive host references`() {
        val renderer = File(appDir, "src/main/java/com/bncam/ui/screens/capture/RawPreviewRenderer.kt").readText()
        assertTrue(renderer.contains("RawPreviewResolutionPolicy.QUALITY_MAX_WIDTH"))
        assertTrue(renderer.contains("RawPreviewResolutionPolicy.QUALITY_MAX_HEIGHT"))
        assertTrue(renderer.contains("allOutputSlots.forEach(OutputSlot::releaseCpuBufferReferences)"))
        assertTrue(renderer.contains("gpuFallbackScratchRgba = null"))
        assertTrue(renderer.contains("val analysisBuffer = if (mlAnalysisRequested)"))
    }
}
