package com.bncam.core.isp.raw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawWhiteDomainBindingTest {
    @Test
    fun `auto dynamic changes developed white but not physical payload white`() {
        val base = contract(
            source = RawInputSource.RAW_SENSOR,
            nativeWhite = 4095,
            payloadWhite = 4095,
            dynamicWhite = 4000,
            staticWhite = 4095
        )

        val bound = RawWhiteDomainBinding.bindDevelopedAuthority(
            contract = base,
            requestedMode = RawWhiteAuthorityMode.AUTO
        )

        assertEquals(4095, bound.payloadWhiteLevel)
        assertEquals(4000, bound.developedRawWhiteLevel)
        assertTrue(bound.developedWhiteMetadataAuthoritative)
        assertFalse(bound.developedWhiteManualOverrideUsed)
    }

    @Test
    fun `manual raw sensor preset is developed only and leaves dng white untouched`() {
        val base = contract(
            source = RawInputSource.RAW_SENSOR,
            nativeWhite = 16383,
            payloadWhite = 16383,
            dynamicWhite = 16000,
            staticWhite = 16383
        )

        val bound = RawWhiteDomainBinding.bindDevelopedAuthority(
            contract = base,
            requestedMode = RawWhiteAuthorityMode.MANUAL,
            manualWhiteLevel = 4095
        )

        assertEquals(16383, bound.payloadWhiteLevel)
        assertEquals(16383, bound.nativeWhiteLevel)
        assertEquals(4095, bound.developedRawWhiteLevel)
        assertTrue(bound.developedWhiteManualOverrideUsed)
        assertTrue(bound.validationWarnings.any { it.contains("physical RAW16/DNG white metadata remains unchanged") })
    }

    @Test
    fun `raw10 manual 1023 remains literal developed white and does not rewrite payload`() {
        val base = contract(
            source = RawInputSource.RAW10,
            nativeWhite = 1023,
            payloadWhite = 4095,
            nativeBlack = listOf(64, 64, 64, 64),
            payloadBlack = listOf(256, 256, 256, 256),
            dynamicWhite = 4095,
            staticWhite = 4095
        )

        val bound = RawWhiteDomainBinding.bindDevelopedAuthority(
            contract = base,
            requestedMode = RawWhiteAuthorityMode.MANUAL,
            manualWhiteLevel = 1023
        )
        assertEquals(1023, bound.developedRawWhiteLevel)
        assertEquals(4095, bound.payloadWhiteLevel)
        assertTrue(bound.developedWhiteManualOverrideUsed)
    }

    @Test
    fun `manual white below developed black falls back to physical payload authority`() {
        val base = contract(
            source = RawInputSource.RAW_SENSOR,
            nativeWhite = 4095,
            payloadWhite = 4095,
            payloadBlack = listOf(1500, 1500, 1500, 1500),
            developedBlack = listOf(1500f, 1500f, 1500f, 1500f),
            dynamicWhite = 4000,
            staticWhite = 4095
        )

        val bound = RawWhiteDomainBinding.bindDevelopedAuthority(
            contract = base,
            requestedMode = RawWhiteAuthorityMode.MANUAL,
            manualWhiteLevel = 1023
        )

        assertEquals(4095, bound.developedRawWhiteLevel)
        assertFalse(bound.developedWhiteManualOverrideUsed)
        assertEquals(
            "selected_white_not_above_developed_black_physical_payload_used",
            bound.developedWhiteFallbackReason
        )
    }

    private fun contract(
        source: RawInputSource,
        nativeWhite: Int,
        payloadWhite: Int,
        nativeBlack: List<Int> = listOf(64, 64, 64, 64),
        payloadBlack: List<Int> = nativeBlack,
        developedBlack: List<Float> = payloadBlack.map { it.toFloat() },
        dynamicWhite: Int?,
        staticWhite: Int?
    ): RawDomainContract = RawDomainContract(
        sourceFormat = source,
        width = 16,
        height = 16,
        nativeBitDepth = if (source == RawInputSource.RAW10) 10 else 14,
        sourceStorageAlignment = if (source == RawInputSource.RAW10) RawStorageAlignment.PACKED else RawStorageAlignment.RIGHT_JUSTIFIED,
        masterStorageAlignment = RawStorageAlignment.RIGHT_JUSTIFIED,
        nativeBlackLevels = nativeBlack,
        nativeWhiteLevel = nativeWhite,
        payloadBlackLevels = payloadBlack,
        payloadWhiteLevel = payloadWhite,
        developedRawBlackLevels = developedBlack,
        developedRawBlackLevelSource = "test black",
        developedBlackMetadataAuthoritative = true,
        developedBlackFallbackReason = "none",
        cfaPattern = 0,
        cfaName = "RGGB",
        cfaOriginX = 0,
        cfaOriginY = 0,
        activeArray = RawContractRect(0, 0, 16, 16),
        cropRegion = RawContractRect(0, 0, 16, 16),
        preCorrectionArray = RawContractRect(0, 0, 16, 16),
        sourceRowStrideBytes = if (source == RawInputSource.RAW10) 20 else 32,
        sourcePixelStrideBytes = if (source == RawInputSource.RAW10) 0 else 2,
        masterRowStrideBytes = 32,
        masterPixelStrideBytes = 2,
        dynamicBlackLevelUsed = false,
        dynamicWhiteLevelUsed = dynamicWhite != null,
        staticBlackLevelUsed = true,
        staticWhiteLevelUsed = dynamicWhite == null && staticWhite != null,
        manualOverrideUsed = false,
        physicalCameraId = null,
        lensId = "test",
        lensShadingState = RawLensShadingState.UNKNOWN,
        sampleTransform = if (source == RawInputSource.RAW10) {
            RawSampleTransform.RAW10_PACKED_TO_BLACK_ANCHORED_PAYLOAD
        } else {
            RawSampleTransform.RAW_SENSOR_RIGHT_JUSTIFIED_TO_PAYLOAD
        },
        sensorInfoWhiteLevel = staticWhite,
        sensorDynamicWhiteLevel = dynamicWhite,
        sensorBlackLevelPatternValues = payloadBlack.map { it.toFloat() },
        sensorDynamicBlackLevelValues = null,
        chosenBlackLevelSource = "test physical black",
        chosenWhiteLevelSource = "test physical white",
        masterStorageScale = 1f,
        masterStorageLeftShift = 0,
        masterStorageContract = "RIGHT_JUSTIFIED_PAYLOAD_CODE_VALUES_IN_UINT16",
        validationWarnings = emptyList()
    )
}
