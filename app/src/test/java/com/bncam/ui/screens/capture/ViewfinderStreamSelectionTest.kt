package com.bncam.ui.screens.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewfinderStreamSelectionTest {
    @Test
    fun yuvSettingAlwaysUsesHalPreview() {
        ViewfinderEffectiveSource.entries.forEach { profileSource ->
            assertEquals(
                ViewfinderEffectiveSource.YUV,
                resolveEffectiveViewfinderSource(ViewfinderStream.YUV, profileSource)
            )
        }
    }

    @Test
    fun selectedBufferTracksRawProfilesButKeepsYuvOnYuvProfile() {
        assertEquals(
            ViewfinderEffectiveSource.YUV,
            resolveEffectiveViewfinderSource(
                ViewfinderStream.SELECTED_BUFFER,
                ViewfinderEffectiveSource.YUV
            )
        )
        assertEquals(
            ViewfinderEffectiveSource.RAW10,
            resolveEffectiveViewfinderSource(
                ViewfinderStream.SELECTED_BUFFER,
                ViewfinderEffectiveSource.RAW10
            )
        )
        assertEquals(
            ViewfinderEffectiveSource.RAW_SENSOR,
            resolveEffectiveViewfinderSource(
                ViewfinderStream.SELECTED_BUFFER,
                ViewfinderEffectiveSource.RAW_SENSOR
            )
        )
    }

    @Test
    fun persistedValuesAreStableAndUnknownValuesFallBackToYuv() {
        assertEquals("YUV", ViewfinderStream.YUV.persistedValue)
        assertEquals("SELECTED_BUFFER", ViewfinderStream.SELECTED_BUFFER.persistedValue)
        assertEquals(ViewfinderStream.YUV, ViewfinderStream.parse(null))
        assertEquals(ViewfinderStream.YUV, ViewfinderStream.parse("future_value"))
        assertEquals(ViewfinderStream.SELECTED_BUFFER, ViewfinderStream.parse("Selected buffer"))
    }
}
