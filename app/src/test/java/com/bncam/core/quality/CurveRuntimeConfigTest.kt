package com.bncam.core.quality

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurveRuntimeConfigTest {
    @Test
    fun linearDefaultsAreNeutral() {
        val config = CurveRuntimeConfig.linear()

        assertFalse(config.toneActive)
        assertFalse(config.gammaActive)
        assertFalse(config.sectionActive)
        assertFalse(config.anyActive)
    }

    @Test
    fun presetNodesAreRuntimeActive() {
        val tone = ProfileCurveDefaults.runtimeNodes(
            ProfileCurveDefaults.TYPE_TONE,
            ProfileCurveDefaults.PRESET_GENTLE_CONTRAST,
            ProfileCurveDefaults.pointsForPreset(
                ProfileCurveDefaults.TYPE_TONE,
                ProfileCurveDefaults.PRESET_GENTLE_CONTRAST
            )
        )
        val config = CurveRuntimeConfig(
            tonePreset = ProfileCurveDefaults.PRESET_GENTLE_CONTRAST,
            toneNodes = tone,
            gammaPreset = ProfileCurveDefaults.PRESET_DEFAULT,
            gammaNodes = ProfileCurveDefaults.linearNodes(ProfileCurveDefaults.TYPE_GAMMA),
            sectionPreset = ProfileCurveDefaults.PRESET_DEFAULT,
            sectionNodes = ProfileCurveDefaults.linearNodes(ProfileCurveDefaults.TYPE_SECT)
        )

        assertTrue(config.toneActive)
        assertTrue(config.anyActive)
        assertFalse(config.gammaActive)
        assertFalse(config.sectionActive)
    }
}
