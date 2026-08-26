package com.bncam

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bncam.core.debug.DiagnosticsAggregator
import com.bncam.core.nativebridge.NativeEngineLoader
import com.bncam.core.vulkan.VulkanCapabilityStatus
import com.bncam.core.vulkan.VulkanRuntimeConfig
import com.bncam.core.vulkan.VulkanRuntimeOwner
import com.bncam.core.vulkan.VulkanRuntimeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VulkanDeviceVerificationTest {

    @Test
    fun verifyVulkanRuntimeTruthOnConnectedDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertNotNull(context)

        // 1. Ensure NativeEngineLoader & attachment
        NativeEngineLoader.ensureLoaded()
        VulkanRuntimeOwner.attach(context)

        // 2. Initialize Vulkan runtime
        val snapshot = VulkanRuntimeOwner.initialize(
            VulkanRuntimeConfig(
                debugValidationRequested = true,
                requireAndroidHardwareBuffer = true
            )
        )

        // 3. Verify state and handles
        assertEquals(VulkanRuntimeState.READY, snapshot.state)
        assertTrue(snapshot.loaderAvailable)
        assertTrue(snapshot.runtimeInitialized)
        assertNotNull(snapshot.selectedDevice)
        assertTrue(snapshot.selectedDevice!!.contains("Adreno", ignoreCase = true))

        val identity = snapshot.runtimeIdentity
        assertTrue(identity.startsWith("bncam-vulkan-runtime-"))

        assertEquals(1L, snapshot.instanceCreationCount)
        assertEquals(1L, snapshot.deviceCreationCount)
        assertEquals(0L, snapshot.inFlightSubmissionCount)
        assertFalse(snapshot.productionVulkanActive)
        assertTrue(snapshot.activeProductionStages.isEmpty())

        // 4. Persistence check: subsequent snapshot queries retain exact identity & counts = 1
        for (i in 1..10) {
            val s = VulkanRuntimeOwner.snapshot()
            assertEquals(VulkanRuntimeState.READY, s.state)
            assertEquals(identity, s.runtimeIdentity)
            assertEquals(1L, s.instanceCreationCount)
            assertEquals(1L, s.deviceCreationCount)
        }

        // 5. Idempotent re-initialization check
        val s2 = VulkanRuntimeOwner.initialize(
            VulkanRuntimeConfig(
                debugValidationRequested = true,
                requireAndroidHardwareBuffer = true
            )
        )
        assertEquals(VulkanRuntimeState.READY, s2.state)
        assertEquals(identity, s2.runtimeIdentity)
        assertEquals(1L, s2.instanceCreationCount)
        assertEquals(1L, s2.deviceCreationCount)

        // 6. Application-level diagnostic export test. Diagnostics are now routed through the
        // unified central sink instead of writing a Vulkan-specific file bundle.
        DiagnosticsAggregator.initialize(context)
        val exportSuccess = VulkanRuntimeOwner.exportApplicationDiagnostics()
        assertTrue(exportSuccess)
        assertNotNull(DiagnosticsAggregator.streamPath(DiagnosticsAggregator.Stream.ISP))
        assertNotNull(DiagnosticsAggregator.streamPath(DiagnosticsAggregator.Stream.PERFORMANCE))

        // 7. Verify diagnostics export content
        val export = VulkanRuntimeOwner.diagnosticExport()
        assertTrue(export.runtimeText.contains("State: READY"))
        assertTrue(export.runtimeText.contains("Adreno"))
        assertTrue(export.capabilitiesText.contains("VULKAN CAPABILITIES"))
        assertTrue(export.capabilitiesText.contains("VK_ANDROID_external_memory_android_hardware_buffer"))
    }
}
