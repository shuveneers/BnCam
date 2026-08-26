package com.bncam.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewfinderSliderAssignmentTest {
    @Test
    fun `iso shutter and zoom are not assignable side controls`() {
        assertFalse(ViewfinderSliderAssignment.entries.any { it.name == "ISO" })
        assertFalse(ViewfinderSliderAssignment.entries.any { it.name == "SHUTTER" })
        assertFalse(ViewfinderSliderAssignment.entries.any { it.name == "ZOOM" })
    }

    @Test
    fun `legacy iso shutter and zoom assignments migrate to off`() {
        assertEquals(ViewfinderSliderAssignment.OFF, ViewfinderSliderAssignment.fromPersisted("ISO"))
        assertEquals(ViewfinderSliderAssignment.OFF, ViewfinderSliderAssignment.fromPersisted("Shutter speed"))
        assertEquals(ViewfinderSliderAssignment.OFF, ViewfinderSliderAssignment.fromPersisted("shutter"))
        assertEquals(ViewfinderSliderAssignment.OFF, ViewfinderSliderAssignment.fromPersisted("zoom"))
    }

    @Test
    fun `viewfinder settings describe dedicated exposure controls as dials`() {
        val roots = listOf(java.io.File("."), java.io.File("app"))
        val screen = roots.asSequence()
            .map { java.io.File(it, "src/main/java/com/bncam/ui/screens/settings/ViewfinderScreen.kt") }
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("Missing ViewfinderScreen.kt")

        assertTrue(screen.contains("title = \"Shutter dial\""))
        assertTrue(screen.contains("title = \"ISO dial\""))
        assertFalse(screen.contains("title = \"Shutter slider\""))
        assertFalse(screen.contains("title = \"ISO slider\""))
        assertFalse(screen.contains("halo control next to the mode selector"))
    }
}
