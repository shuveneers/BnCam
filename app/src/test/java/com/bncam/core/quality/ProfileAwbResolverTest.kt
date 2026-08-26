package com.bncam.core.quality

import com.bncam.data.settings.ProfileAwbModels
import com.bncam.data.settings.ProfileAwbModes
import com.bncam.data.settings.ProfileAwbSettings
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileAwbResolverTest {
    @Test
    fun `system mode preserves exact per-frame Camera2 gains`() {
        val camera = floatArrayOf(2.1f, 1.0f, 1.02f, 1.7f)
        val resolved = ProfileAwbResolver.resolve(camera, ProfileAwbSettings())

        assertArrayEquals(camera, resolved.gains, 0.000001f)
        assertFalse(resolved.profileOverrideApplied)
    }

    @Test
    fun `manual mode replaces rather than multiplies Camera2 AWB`() {
        val settings = ProfileAwbSettings(
            mode = ProfileAwbModes.MANUAL_KELVIN,
            kelvin = 3200,
            illuminantModel = ProfileAwbModels.PLANCKIAN
        )
        val fromWarmFrame = ProfileAwbResolver.resolve(floatArrayOf(2.4f, 1f, 1f, 1.3f), settings)
        val fromCoolFrame = ProfileAwbResolver.resolve(floatArrayOf(1.3f, 1f, 1f, 2.4f), settings)

        assertArrayEquals(fromWarmFrame.gains, fromCoolFrame.gains, 0.000001f)
        assertTrue(fromWarmFrame.profileOverrideApplied)
    }

    @Test
    fun `independent profile references produce different RAW gains`() {
        val camera = floatArrayOf(2f, 1f, 1f, 1.5f)
        val profile1 = ProfileAwbResolver.resolve(
            camera,
            ProfileAwbSettings(mode = ProfileAwbModes.BRAND_REFERENCE, brand = "Canon", preset = "Daylight", kelvin = 5200)
        )
        val profile2 = ProfileAwbResolver.resolve(
            camera,
            ProfileAwbSettings(mode = ProfileAwbModes.BRAND_REFERENCE, brand = "Fujifilm", preset = "Shade", kelvin = 10000)
        )

        assertTrue(profile1.gains.indices.any { kotlin.math.abs(profile1.gains[it] - profile2.gains[it]) > 0.01f })
        assertTrue(profile1.source.contains("Canon"))
        assertTrue(profile2.source.contains("Fujifilm"))
    }

    @Test
    fun `tint changes both green planes while keeping them equal`() {
        val base = floatArrayOf(2f, 1f, 1f, 1.5f)
        val neutral = ProfileAwbResolver.resolve(base, ProfileAwbSettings(mode = ProfileAwbModes.MANUAL_KELVIN, tint = 0f))
        val tinted = ProfileAwbResolver.resolve(base, ProfileAwbSettings(mode = ProfileAwbModes.MANUAL_KELVIN, tint = 0.75f))

        assertTrue(kotlin.math.abs(neutral.gains[1] - tinted.gains[1]) > 0.01f)
        assertTrue(kotlin.math.abs(tinted.gains[1] - tinted.gains[2]) < 0.000001f)
    }
    @Test
    fun `brand reference intensity zero is no effect and one is full reference`() {
        val camera = floatArrayOf(2.2f, 1.05f, 1.0f, 1.45f)
        val off = ProfileAwbResolver.resolve(
            camera,
            ProfileAwbSettings(
                mode = ProfileAwbModes.BRAND_REFERENCE,
                brand = "Fujifilm",
                preset = "Shade",
                kelvin = 8000,
                referenceIntensity = 0f
            )
        )
        val full = ProfileAwbResolver.resolve(
            camera,
            ProfileAwbSettings(
                mode = ProfileAwbModes.BRAND_REFERENCE,
                brand = "Fujifilm",
                preset = "Shade",
                kelvin = 8000,
                referenceIntensity = 1f
            )
        )

        assertArrayEquals(camera, off.gains, 0.000001f)
        assertFalse(off.profileOverrideApplied)
        assertTrue(full.profileOverrideApplied)
        assertTrue(full.gains.indices.any { kotlin.math.abs(full.gains[it] - camera[it]) > 0.01f })
    }

    @Test
    fun `brand reference intensity is clamped and intermediate result stays between endpoints`() {
        val camera = floatArrayOf(2.0f, 1.0f, 1.0f, 1.4f)
        val half = ProfileAwbResolver.resolve(
            camera,
            ProfileAwbSettings(
                mode = ProfileAwbModes.BRAND_REFERENCE,
                kelvin = 6500,
                referenceIntensity = 0.5f
            )
        )
        val full = ProfileAwbResolver.resolve(
            camera,
            ProfileAwbSettings(
                mode = ProfileAwbModes.BRAND_REFERENCE,
                kelvin = 6500,
                referenceIntensity = 1f
            )
        )
        half.gains.indices.forEach { index ->
            val low = minOf(camera[index], full.gains[index]) - 0.000001f
            val high = maxOf(camera[index], full.gains[index]) + 0.000001f
            assertTrue(half.gains[index] in low..high)
        }
    }

}
