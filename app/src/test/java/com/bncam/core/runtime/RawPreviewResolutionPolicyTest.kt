package com.bncam.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class RawPreviewResolutionPolicyTest {
    @Test
    fun provenPreviewTierUsesFourCellDecimationFor4096By3072Raw() {
        assertEquals(
            4,
            RawPreviewResolutionPolicy.cfaCellDecimation(4096, 3072)
        )
        assertEquals(
            1024 to 768,
            RawPreviewResolutionPolicy.outputDimensions(4096, 3072)
        )
    }
}
