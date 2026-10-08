package com.bncam

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bncam.core.engine.ImageUtils
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DemosaicNativeValidationTest {
    @Test
    fun classicalSyntheticValidationAndCurrentSlotMappingPassInNativeLibrary() {
        val result = ImageUtils.validateDemosaicImplementation()
        assertTrue(result, result.contains("patterns=RGGB,BGGR,GRBG,GBRG"))
        assertTrue(result, result.contains("colourScienceReferenceVector=true"))
        // Persisted Normal slot intentionally resolves to pure Malvar; bilinear is reference-only.
        assertTrue(result, result.contains("normalForcesMalvar=true"))
        assertTrue(result, result.contains("legacySlot2ForcesAmaze=true"))
        assertTrue(result, result.contains("allPassed=true"))
    }
}
