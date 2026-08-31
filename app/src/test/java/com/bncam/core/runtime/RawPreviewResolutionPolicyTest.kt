package com.bncam.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RawPreviewResolutionPolicyTest {
    @Test
    fun twelveMegapixelFourByThreeUsesThreeXCellDecimation() {
        assertEquals(3, RawPreviewResolutionPolicy.cfaCellDecimation(4096, 3072))
        assertEquals(1364 to 1024, RawPreviewResolutionPolicy.outputDimensions(4096, 3072))
    }

    @Test
    fun outputNeverExceedsProductQualityTarget() {
        val sources = listOf(4096 to 3072, 4080 to 3060, 8192 to 6144, 4000 to 3000, 4032 to 3024)
        sources.forEach { (width, height) ->
  val (outWidth, outHeight) = RawPreviewResolutionPolicy.outputDimensions(width, height)
  assertTrue(outWidth <= RawPreviewResolutionPolicy.QUALITY_MAX_WIDTH)
  assertTrue(outHeight <= RawPreviewResolutionPolicy.QUALITY_MAX_HEIGHT)
  assertEquals(0, outWidth and 1)
  assertEquals(0, outHeight and 1)
        }
    }
}
