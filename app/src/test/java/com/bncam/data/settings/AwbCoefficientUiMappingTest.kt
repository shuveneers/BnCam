package com.bncam.data.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class AwbCoefficientUiMappingTest {
    @Test
    fun signedEndpointsMapToExistingCoefficientEndpoints() {
        assertEquals(0.25f, AwbCoefficientUiMapping.fromSigned(-1.0f), 1.0e-6f)
        assertEquals(1.00f, AwbCoefficientUiMapping.fromSigned(0.0f), 1.0e-6f)
        assertEquals(5.00f, AwbCoefficientUiMapping.fromSigned(1.0f), 1.0e-6f)
        assertEquals(-1.0f, AwbCoefficientUiMapping.toSigned(0.25f), 1.0e-6f)
        assertEquals(0.0f, AwbCoefficientUiMapping.toSigned(1.0f), 1.0e-6f)
        assertEquals(1.0f, AwbCoefficientUiMapping.toSigned(5.0f), 1.0e-6f)
    }

    @Test
    fun signedMappingRoundTripsAcrossBothHalves() {
        listOf(-1.0f, -0.75f, -0.25f, 0.0f, 0.25f, 0.75f, 1.0f).forEach { signed ->
            val coefficient = AwbCoefficientUiMapping.fromSigned(signed)
            assertEquals(signed, AwbCoefficientUiMapping.toSigned(coefficient), 1.0e-5f)
        }
    }
}
