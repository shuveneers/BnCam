package com.bncam.ui.screens.capture

import com.bncam.core.runtime.RawPreviewQualityTier
import com.bncam.core.runtime.RawPreviewResolutionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RawPreviewPhase8QualityConnectivitySourceContractTest {
    @Test
    fun balancedRemainsShippingDefault() {
        assertEquals(1440, RawPreviewQualityTier.BALANCED.maxWidth)
        assertEquals(1080, RawPreviewQualityTier.BALANCED.maxHeight)
        assertEquals(
            RawPreviewResolutionPolicy.outputDimensions(4096, 3072),
            RawPreviewResolutionPolicy.outputDimensions(4096, 3072, RawPreviewQualityTier.BALANCED)
        )
    }

    @Test
    fun sharpPrototypeIsHigherResolutionButExplicitOnly() {
        val balanced = RawPreviewResolutionPolicy.outputDimensions(4096, 3072)
        val sharp = RawPreviewResolutionPolicy.sharpPrototypeDimensions(4096, 3072)
        assertTrue(sharp.first > balanced.first)
        assertTrue(sharp.second > balanced.second)
        assertTrue(sharp.first <= RawPreviewResolutionPolicy.SHARP_PROTOTYPE_MAX_WIDTH)
        assertTrue(sharp.second <= RawPreviewResolutionPolicy.SHARP_PROTOTYPE_MAX_HEIGHT)
    }
}
