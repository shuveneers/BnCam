package com.bncam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MethodRegistryTruthTest {
    @Test
    fun autoRecordsActualSourceSpecificImplementations() {
        val alignment = MultiFrameAlignmentRegistry.resolve("Auto", FrameOrigin.RAW10)
        val yuvFusion = MultiFrameFusionRegistry.resolve("Auto", FrameOrigin.YUV)
        val rawFusion = MultiFrameFusionRegistry.resolve("Auto", FrameOrigin.RAW_SENSOR)

        assertEquals("phase_correlation_fast", alignment.resolvedId)
        assertEquals("weighted_average", yuvFusion.resolvedId)
        assertEquals("robust_mean", rawFusion.resolvedId)
        assertFalse(alignment.fallback)
    }

    @Test
    fun plannedMethodsAreRegisteredButNotSelectable() {
        assertTrue(
            MultiFrameAlignmentRegistry.registeredMethods.any {
                it.id == "optical_flow_quality" && it.availability == MethodAvailability.PLANNED
            }
        )
        assertFalse(MultiFrameAlignmentRegistry.isValid("Optical Flow Quality"))
        assertFalse(MultiFrameFusionRegistry.isValid("Wiener Fusion"))
    }

    @Test
    fun unavailableAndUnknownValuesResolveExplicitly() {
        val planned =
            MultiFrameAlignmentRegistry.resolve("Tile Pyramid", FrameOrigin.RAW10)
        val unknown =
            MultiFrameFusionRegistry.resolve("Imaginary Neural Fusion", FrameOrigin.YUV)

        assertTrue(planned.fallback)
        assertEquals(
            "registered_method_not_connected_to_production_runner",
            planned.reason
        )
        assertTrue(unknown.fallback)
        assertEquals("weighted_average", unknown.resolvedId)
        assertEquals("unknown_legacy_method_resolved_explicitly", unknown.reason)
    }

    @Test
    fun frameAndAnchorRegistriesNameCurrentAlgorithmsTruthfully() {
        assertEquals(
            "latest_complete",
            FrameSelectionRegistry.resolvedFor(CaptureMode.MULTI).id
        )
        assertEquals(
            "balanced",
            FrameSelectionRegistry.resolvedFor(CaptureMode.SINGLE).id
        )
        assertEquals(
            "latest_selected",
            AnchorSelectionRegistry.resolvedFor(CaptureMode.MULTI).id
        )
    }
}
