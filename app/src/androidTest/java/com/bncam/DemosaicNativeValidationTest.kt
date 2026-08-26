package com.bncam

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bncam.core.engine.ImageUtils
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DemosaicNativeValidationTest {
    @Test
    fun bilinearMalvarAndMenonSyntheticValidationPassInNativeLibrary() {
        val result = ImageUtils.validateDemosaicImplementation()
        assertTrue(result, result.contains("patterns=RGGB,BGGR,GRBG,GBRG"))
        assertTrue(result, result.contains("colourScienceReferenceVector=true"))
        assertTrue(result, result.contains("bilinearForcesBilinear=true"))
        assertTrue(result, result.contains("allPassed=true"))
    }
}
