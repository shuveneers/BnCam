package com.bncam.core.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureRequestGraphIntegrationSourceContractTest {
    private fun appRoot(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun managerSource(): String =
        File(appRoot(), "src/main/java/com/bncam/core/engine/BnCameraManager.kt").readText()

    @Test
    fun `still and burst capture targets resolve from capture role graph`() {
        val manager = managerSource()

        assertFalse(manager.contains("builder.addTarget(reader.surface)"))
        assertFalse(manager.contains("stillBuilder.addTarget(reader.surface)"))
        assertTrue(manager.contains("val captureBindings = boundConfiguration.captureBindings()"))
        assertTrue(manager.contains("captureBindings.forEach { binding ->"))
        assertTrue(manager.contains("builder.addTarget(binding.surface)"))
        assertTrue(manager.contains("reason = \"HDR_ENHANCED_MAIN_${'$'}{index.toString().padStart(2, '0')}\""))
        assertTrue(manager.contains("reason = \"HDR_BRACKET_${'$'}{framePlan.role.name}\""))
        assertTrue(manager.contains("reason = \"FLASH_STILL_CAPTURE\""))
    }

    @Test
    fun `capture graph is owned by exact configured session lifecycle`() {
        val manager = managerSource()

        assertTrue(manager.contains("sessionBoundStreamConfigurations[session] = boundStreamConfiguration"))
        assertTrue(manager.contains("sessionBoundStreamConfigurations.remove(session)"))
        assertTrue(manager.contains("check(captureSession === session)"))
        assertTrue(manager.contains("primaryBinding.imageReader === expectedPrimaryReader"))
    }

    @Test
    fun `control trigger one shots keep the repeating target builder`() {
        val manager = managerSource()

        assertTrue(manager.contains("reason = \"FLASH_AF_TRIGGER\""))
        assertTrue(manager.contains("reason = \"FLASH_AE_PRECAPTURE_TRIGGER\""))
        assertTrue(manager.contains("builder = previewBuilder"))
    }
}
