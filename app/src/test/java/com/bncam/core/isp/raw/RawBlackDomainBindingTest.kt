package com.bncam.core.isp.raw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawBlackDomainBindingTest {
    @Test
    fun `manual changes developed black but never physical raw16 payload black`() {
        val base = contract(
            payloadBlack = listOf(64, 65, 66, 67),
            dynamic = listOf(60f, 61f, 62f, 63f),
            static = listOf(64f, 65f, 66f, 67f)
        )

        val bound = RawBlackDomainBinding.bindDevelopedAuthority(
            contract = base,
            requestedMode = RawBlackAuthorityMode.MANUAL,
            fallbackCanonicalLevelsInPayloadDomain = listOf(100f, 101f, 102f, 103f),
            fallbackSource = "Black Level v2 Manual mosaic override"
        )

        assertEquals(listOf(64, 65, 66, 67), bound.payloadBlackLevels)
        assertEquals(base.nativeBlackLevels, bound.nativeBlackLevels)
        assertEquals(listOf(100f, 101f, 102f, 103f), bound.developedRawBlackLevels)
        assertTrue(bound.manualOverrideUsed)
        assertFalse(bound.developedBlackMetadataAuthoritative)
    }

    @Test
    fun `system uses static metadata and leaves dng payload untouched`() {
        val base = contract(
            payloadBlack = listOf(64, 64, 64, 64),
            dynamic = listOf(10f, 20f, 30f, 40f),
            static = listOf(64f, 65f, 66f, 67f)
        )

        val bound = RawBlackDomainBinding.bindDevelopedAuthority(
            contract = base,
            requestedMode = RawBlackAuthorityMode.SYSTEM,
            fallbackCanonicalLevelsInPayloadDomain = listOf(1f, 1f, 1f, 1f),
            fallbackSource = "fallback"
        )

        assertEquals(listOf(64, 64, 64, 64), bound.payloadBlackLevels)
        assertEquals(listOf(64f, 65f, 66f, 67f), bound.developedRawBlackLevels)
        assertTrue(bound.developedRawBlackLevelSource.startsWith("SYSTEM_METADATA:"))
    }

    @Test
    fun `dynamic half strength blends static to same-frame dynamic`() {
        val base = contract(
            payloadBlack = listOf(64, 64, 64, 64),
            dynamic = listOf(60f, 62f, 70f, 72f),
            static = listOf(64f, 66f, 68f, 70f)
        )

        val bound = RawBlackDomainBinding.bindDevelopedAuthority(
            contract = base,
            requestedMode = RawBlackAuthorityMode.DYNAMIC,
            dynamicStrength = 0.5f,
            fallbackCanonicalLevelsInPayloadDomain = listOf(1f, 1f, 1f, 1f),
            fallbackSource = "fallback"
        )

        assertEquals(listOf(62f, 64f, 69f, 71f), bound.developedRawBlackLevels)
        assertTrue(bound.developedRawBlackLevelSource.contains("strength=0.50"))
        assertEquals(listOf(64, 64, 64, 64), bound.payloadBlackLevels)
    }

    @Test
    fun `missing dynamic metadata stays on system baseline`() {
        val base = contract(
            payloadBlack = listOf(64, 65, 66, 67),
            dynamic = null,
            static = listOf(64f, 65f, 66f, 67f)
        )

        val bound = RawBlackDomainBinding.bindDevelopedAuthority(
            contract = base,
            requestedMode = RawBlackAuthorityMode.DYNAMIC,
            dynamicStrength = 0.25f,
            fallbackCanonicalLevelsInPayloadDomain = listOf(16f, 16f, 16f, 16f),
            fallbackSource = "fallback"
        )

        assertEquals(listOf(64f, 65f, 66f, 67f), bound.developedRawBlackLevels)
        assertEquals("dynamic_black_metadata_unavailable_system_fallback", bound.developedBlackFallbackReason)
        assertTrue(bound.validationWarnings.any { it.contains("Dynamic black requested") })
    }

    @Test
    fun `manual values scale exactly once into payload white domain`() {
        val scaled = RawBlackDomainBinding.scaleCanonicalToPayload(
            canonicalLevels = listOf(256f, 512f, 768f, 4095f),
            sourceWhiteLevel = 4095,
            payloadWhiteLevel = 1023
        )

        val scale = 1023f / 4095f
        assertEquals(256f * scale, scaled[0], 1e-4f)
        assertEquals(512f * scale, scaled[1], 1e-4f)
        assertEquals(768f * scale, scaled[2], 1e-4f)
        assertEquals(1022f, scaled[3], 1e-4f)
    }

    private fun contract(
        cfa: Int = 0,
        payloadBlack: List<Int>,
        dynamic: List<Float>?,
        static: List<Float>,
        extraWarnings: List<String> = emptyList()
    ): RawDomainContract = RawDomainContract(
        sourceFormat = RawInputSource.RAW_SENSOR,
        width = 16,
        height = 16,
        nativeBitDepth = 12,
        sourceStorageAlignment = RawStorageAlignment.RIGHT_JUSTIFIED,
        masterStorageAlignment = RawStorageAlignment.RIGHT_JUSTIFIED,
        nativeBlackLevels = payloadBlack,
        nativeWhiteLevel = 4095,
        payloadBlackLevels = payloadBlack,
        payloadWhiteLevel = 4095,
        developedRawBlackLevels = RawLevelOrder.mosaicToCanonical(payloadBlack.map { it.toFloat() }, cfa),
        developedRawBlackLevelSource = "test baseline",
        developedBlackMetadataAuthoritative = true,
        developedBlackFallbackReason = "none",
        cfaPattern = cfa,
        cfaName = "test",
        cfaOriginX = 0,
        cfaOriginY = 0,
        activeArray = RawContractRect(0, 0, 16, 16),
        cropRegion = RawContractRect(0, 0, 16, 16),
        preCorrectionArray = RawContractRect(0, 0, 16, 16),
        sourceRowStrideBytes = 32,
        sourcePixelStrideBytes = 2,
        masterRowStrideBytes = 32,
        masterPixelStrideBytes = 2,
        dynamicBlackLevelUsed = dynamic != null,
        dynamicWhiteLevelUsed = false,
        staticBlackLevelUsed = dynamic == null,
        staticWhiteLevelUsed = true,
        manualOverrideUsed = false,
        physicalCameraId = null,
        lensId = "test",
        lensShadingState = RawLensShadingState.UNKNOWN,
        sampleTransform = RawSampleTransform.RAW_SENSOR_RIGHT_JUSTIFIED_TO_PAYLOAD,
        sensorInfoWhiteLevel = 4095,
        sensorDynamicWhiteLevel = null,
        sensorBlackLevelPatternValues = static,
        sensorDynamicBlackLevelValues = dynamic,
        chosenBlackLevelSource = "physical payload metadata",
        chosenWhiteLevelSource = "test",
        masterStorageScale = 1f,
        masterStorageLeftShift = 0,
        masterStorageContract = "RIGHT_JUSTIFIED_PAYLOAD_CODE_VALUES_IN_UINT16",
        validationWarnings = extraWarnings
    )
}
