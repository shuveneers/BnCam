package com.bncam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class MeteringModeDefaultTest {
    @Test
    fun `missing or unknown metering resolves to camera default auto AE`() {
        assertEquals(MeteringMode.AUTO_DEFAULT_AE, MeteringMode.fromSetting(""))
        assertEquals(MeteringMode.AUTO_DEFAULT_AE, MeteringMode.fromSetting("unknown-value"))
        assertEquals(MeteringMode.AUTO_DEFAULT_AE, MeteringMode.fromSetting("Auto"))
    }

    @Test
    fun `legacy evaluative values migrate to auto instead of preserving old controller`() {
        assertEquals(
            MeteringMode.AUTO_DEFAULT_AE,
            MeteringMode.fromSetting("Evaluative / Highlight Protect")
        )
        assertEquals(MeteringMode.AUTO_DEFAULT_AE, MeteringMode.fromSetting("Matrix"))
    }

    @Test
    fun `all four canonical user choices remain stable`() {
        assertEquals(MeteringMode.AUTO_DEFAULT_AE, MeteringMode.fromSetting("Auto"))
        assertEquals(MeteringMode.CENTER_WEIGHTED, MeteringMode.fromSetting("Center Weighted"))
        assertEquals(MeteringMode.FRAME_AVERAGE, MeteringMode.fromSetting("Frame Average"))
        assertEquals(MeteringMode.SPOT, MeteringMode.fromSetting("Spot"))
    }
}
