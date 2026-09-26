package com.bncam

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bncam.core.engine.ImageUtils
import com.bncam.core.nativebridge.NativeEngineLoader
import com.bncam.core.vulkan.VulkanRuntimeConfig
import com.bncam.core.vulkan.VulkanRuntimeOwner
import com.bncam.core.vulkan.VulkanRuntimeState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RawPreviewRecoveryDeviceTest {
    @Test fun bothRawFormatsRenderThroughCpuVisibleRecovery() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        NativeEngineLoader.ensureLoaded()
        VulkanRuntimeOwner.attach(context)
        assertEquals(VulkanRuntimeState.READY, VulkanRuntimeOwner.initialize(
            VulkanRuntimeConfig(debugValidationRequested = true, requireAndroidHardwareBuffer = true)
        ).state)
        val report = ImageUtils.validateRawPreviewRecoveryNative()
        File(context.filesDir, "raw_preview_recovery_validation.txt").writeText(report)
        assertTrue(report, report.contains("frames=120;allPassed=true"))
    }
}
