package com.bncam.core.vulkan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VulkanRuntimeModelsTest {
    @Test
    fun nativeStateCodesAreStable() {
        assertEquals(VulkanRuntimeState.UNINITIALIZED, VulkanRuntimeState.fromNativeCode(0))
        assertEquals(VulkanRuntimeState.READY, VulkanRuntimeState.fromNativeCode(2))
        assertEquals(VulkanRuntimeState.DESTROYED, VulkanRuntimeState.fromNativeCode(6))
        assertEquals(VulkanRuntimeState.FAILED, VulkanRuntimeState.fromNativeCode(999))
    }

    @Test
    fun preparedSnapshotCannotClaimProductionVulkan() {
        val snapshot = VulkanRuntimeSnapshot(
            schemaVersion = 1,
            state = VulkanRuntimeState.UNINITIALIZED,
            runtimeIdentity = "runtime-1",
            loaderAvailable = false,
            runtimeInitialized = false,
            selectedDevice = null,
            enabledExtensions = emptyList(),
            enabledFeatures = emptyList(),
            missingRequirements = listOf("bootstrap"),
            activeProductionStages = emptyList(),
            instanceCreationCount = 0,
            deviceCreationCount = 0,
            initializeRequestCount = 0,
            shutdownRequestCount = 0,
            inFlightSubmissionCount = 0,
            lastFailure = VulkanRuntimeFailure("", "", false),
            nativeBridgeAvailable = true
        )

        assertFalse(snapshot.productionVulkanActive)
        assertTrue(snapshot.toJson().contains("\"activeProductionStages\":[]"))
        assertTrue(snapshot.toJson().contains("\"runtimeIdentity\":\"runtime-1\""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun readyRequiresASelectedDevice() {
        VulkanRuntimeSnapshot(
            schemaVersion = 1,
            state = VulkanRuntimeState.READY,
            runtimeIdentity = "runtime-1",
            loaderAvailable = true,
            runtimeInitialized = true,
            selectedDevice = null,
            enabledExtensions = emptyList(),
            enabledFeatures = emptyList(),
            missingRequirements = emptyList(),
            activeProductionStages = emptyList(),
            instanceCreationCount = 1,
            deviceCreationCount = 1,
            initializeRequestCount = 1,
            shutdownRequestCount = 0,
            inFlightSubmissionCount = 0,
            lastFailure = VulkanRuntimeFailure("", "", false),
            nativeBridgeAvailable = true
        )
    }

    @Test
    fun bridgeFailureIsBoundedAndTruthful() {
        val snapshot = VulkanRuntimeSnapshot.bridgeUnavailable("x".repeat(700))
        assertEquals(VulkanRuntimeState.UNAVAILABLE, snapshot.state)
        assertFalse(snapshot.nativeBridgeAvailable)
        assertTrue(snapshot.lastFailure.message.length <= 512)
        assertTrue(snapshot.activeProductionStages.isEmpty())
    }

    @Test
    fun capabilityStatusesDistinguishCorePromotionAndExtensions() {
        val statuses = VulkanCapabilityStatus.entries
        assertTrue(statuses.contains(VulkanCapabilityStatus.SUPPORTED_BY_CORE_VERSION))
        assertTrue(statuses.contains(VulkanCapabilityStatus.SUPPORTED_BY_EXTENSION))
        assertTrue(statuses.contains(VulkanCapabilityStatus.ENABLED_AS_CORE_FEATURE))
        assertTrue(statuses.contains(VulkanCapabilityStatus.ENABLED_AS_EXTENSION))
        assertTrue(statuses.contains(VulkanCapabilityStatus.SUPPORTED_NOT_ENABLED))
    }
}
