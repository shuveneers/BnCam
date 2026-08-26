package com.bncam.core.vulkan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VulkanResourceModelsTest {

    @Test
    fun resourceTypesAndStatesMatchNativeContract() {
        assertEquals(
            VulkanResourceType.EXTERNAL_AHARDWAREBUFFER,
            VulkanResourceType.fromNativeName("EXTERNAL_AHARDWAREBUFFER")
        )
        assertEquals(
            VulkanResourceType.VMA_OWNED_BUFFER,
            VulkanResourceType.fromNativeName("VMA_OWNED_BUFFER")
        )
        assertEquals(
            VulkanResourceType.VMA_OWNED_IMAGE,
            VulkanResourceType.fromNativeName("VMA_OWNED_IMAGE")
        )
        assertEquals(
            VulkanResourceType.PERSISTENT_STAGING_BUFFER,
            VulkanResourceType.fromNativeName("PERSISTENT_STAGING_BUFFER")
        )
        assertEquals(
            VulkanResourceType.BORROWED_CAPTURE_RESOURCE,
            VulkanResourceType.fromNativeName("BORROWED_CAPTURE_RESOURCE")
        )

        assertEquals(VulkanResourceState.ACQUIRED, VulkanResourceState.fromNativeName("ACQUIRED"))
        assertEquals(VulkanResourceState.IMPORTING, VulkanResourceState.fromNativeName("IMPORTING"))
        assertEquals(VulkanResourceState.VULKAN_READY, VulkanResourceState.fromNativeName("VULKAN_READY"))
        assertEquals(VulkanResourceState.IN_FLIGHT, VulkanResourceState.fromNativeName("IN_FLIGHT"))
        assertEquals(VulkanResourceState.RELEASE_PENDING, VulkanResourceState.fromNativeName("RELEASE_PENDING"))
        assertEquals(VulkanResourceState.RELEASED, VulkanResourceState.fromNativeName("RELEASED"))
        assertEquals(VulkanResourceState.FAILED, VulkanResourceState.fromNativeName("FAILED"))

        assertEquals(
            VulkanImportPath.DIRECT_AHARDWAREBUFFER_IMPORT,
            VulkanImportPath.fromNativeName("DIRECT_AHARDWAREBUFFER_IMPORT")
        )
        assertEquals(
            VulkanImportPath.NATIVE_LOCK_TO_PERSISTENT_STAGING,
            VulkanImportPath.fromNativeName("NATIVE_LOCK_TO_PERSISTENT_STAGING")
        )
        assertEquals(
            VulkanImportPath.UNSUPPORTED,
            VulkanImportPath.fromNativeName("UNSUPPORTED")
        )
    }

    @Test
    fun resourceIdentityTracksGenerationAndState() {
        val identity = VulkanResourceIdentity(
            resourceId = "res-yuv-001",
            generationId = 1001L,
            type = VulkanResourceType.EXTERNAL_AHARDWAREBUFFER,
            state = VulkanResourceState.ACQUIRED,
            path = VulkanImportPath.DIRECT_AHARDWAREBUFFER_IMPORT
        )

        assertEquals("res-yuv-001", identity.resourceId)
        assertEquals(1001L, identity.generationId)
        assertEquals(VulkanResourceType.EXTERNAL_AHARDWAREBUFFER, identity.type)
        assertEquals(VulkanResourceState.ACQUIRED, identity.state)
        assertEquals(VulkanImportPath.DIRECT_AHARDWAREBUFFER_IMPORT, identity.path)
    }

    @Test
    fun sourceImportDecisionModelPreservesTruth() {
        val decision = SourceImportDecisionModel(
            sourceName = "YUV",
            androidFormat = 35,
            resolvedPath = VulkanImportPath.DIRECT_AHARDWAREBUFFER_IMPORT,
            fallbackPath = VulkanImportPath.NATIVE_LOCK_TO_PERSISTENT_STAGING,
            ownership = VulkanResourceType.EXTERNAL_AHARDWAREBUFFER,
            directImportEligible = true,
            requiredAcquireFence = true,
            requiredReleaseFence = true,
            knownBlocker = ""
        )

        assertEquals("YUV", decision.sourceName)
        assertTrue(decision.directImportEligible)
        assertEquals(VulkanImportPath.DIRECT_AHARDWAREBUFFER_IMPORT, decision.resolvedPath)
        assertTrue(decision.requiredAcquireFence)
        assertTrue(decision.requiredReleaseFence)
    }
}
