package com.bncam

import android.graphics.ImageFormat
import com.bncam.core.capture.FrameCapacityPolicy
import com.bncam.core.capture.FrameOrigin
import com.bncam.data.profile.ProfileManager
import com.bncam.ui.navigation.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileAndBufferRegressionTest {
    @Test
    fun `YUV RAW10 and RAW_SENSOR capacity contracts stay unchanged`() {
        assertEquals(FrameOrigin.YUV, FrameCapacityPolicy.frameOrigin(ImageFormat.YUV_420_888))
        assertEquals(FrameOrigin.RAW10, FrameCapacityPolicy.frameOrigin(ImageFormat.RAW10))
        assertEquals(FrameOrigin.RAW_SENSOR, FrameCapacityPolicy.frameOrigin(ImageFormat.RAW_SENSOR))
        assertEquals(listOf(35, 35, 15), FrameOrigin.entries.map(FrameCapacityPolicy::warmBufferTarget))
        assertEquals(listOf(25, 25, 10), FrameOrigin.entries.map(FrameCapacityPolicy::maximumProcessingFrames))
    }

    @Test
    fun `authoritative profile slots remain one through twelve`() {
        val profiles = ProfileManager.getProfilesForLens("camera0", 5, emptyMap())
        val slots = profiles.filter { it.id.contains("_profile_") }

        assertEquals(12, slots.size)
        assertEquals("camera0_profile_1", slots.first().id)
        assertEquals("camera0_profile_12", slots.last().id)
        assertTrue(slots.take(5).all { it.isVisibleInUi && !it.isLocked })
        assertTrue(slots.drop(5).all { !it.isVisibleInUi && it.isLocked })
    }

    @Test
    fun `profile list and viewfinder use the unchanged editor route identity`() {
        assertEquals("profile_edit/camera0/7", Routes.profileEdit("camera0", 7))
        assertEquals("lens_detail/camera0/awb", Routes.lensAwbCalibration("camera0"))
        assertFalse(Routes.lensAwbCalibration("camera0").startsWith("profile_edit/"))
    }
}
