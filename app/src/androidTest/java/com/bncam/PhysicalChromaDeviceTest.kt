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
class PhysicalChromaDeviceTest {
    @Test fun physicalChromaCpuAndProductionGpuPreserveDetail() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        NativeEngineLoader.ensureLoaded()
        VulkanRuntimeOwner.attach(context)
        assertEquals(VulkanRuntimeState.READY, VulkanRuntimeOwner.initialize(
            VulkanRuntimeConfig(debugValidationRequested = true, requireAndroidHardwareBuffer = true)
        ).state)
        val report = ImageUtils.validatePhysicalChromaNative()
        File(context.filesDir, "physical_chroma_validation.txt").writeText(report)
        assertTrue(report, report.contains("GPU\n"))
        assertFalse(report, report.contains("allPassed=false"))
        assertEquals(report, 2, "allPassed=true".toRegex().findAll(report).count())
    }
}
