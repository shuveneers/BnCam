package com.bncam.ui.screens.capture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Phase7ViewfinderSelectorsContractTest {
    private fun source(path: String): String {
        val roots = listOf(File("."), File("app"))
        return roots.asSequence().map { File(it, path) }.firstOrNull { it.isFile }?.readText()
            ?: error("Missing source file: $path")
    }

    @Test
    fun `camera screen uses dedicated lens and profile selectors`() {
        val camera = source("src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt")
        assertFalse(camera.contains("DropdownMenu("))
        assertTrue(camera.contains("ViewfinderProfileSelector("))
        assertTrue(camera.contains("ViewfinderLensSelector("))
    }

    @Test
    fun `lens selector keeps radial drag halo strong haptics and camera switch glyph`() {
        val selector = source("src/main/java/com/bncam/ui/screens/capture/ViewfinderSelectors.kt")
        assertTrue(selector.contains("visibleLenses: List<LensInfo>"))
        assertTrue(selector.contains("detectDragGestures("))
        assertTrue(selector.contains("nearestRadialSelectorIndex("))
        assertTrue(selector.contains("HapticFeedbackType.LongPress"))
        assertTrue(selector.contains("Icons.Default.Cameraswitch"))
        assertTrue(selector.contains("hoveredIndex?.let { index -> selectorLenses.getOrNull(index)?.let(onLensSelected) }"))
        assertTrue(selector.contains("CameraCharacteristics.LENS_FACING_FRONT"))
        assertFalse(selector.contains("text = \"Selfie\""))
    }

    @Test
    fun `profile popup is screen wide three columns and only popup content rotates`() {
        val selector = source("src/main/java/com/bncam/ui/screens/capture/ViewfinderSelectors.kt")
        val profileSection = selector.substringAfter("internal fun ViewfinderProfileSelector(")
            .substringBefore("internal fun ViewfinderLensSelector(")
        assertTrue(profileSection.contains("orderedProfiles.chunked(3)"))
        assertTrue(profileSection.contains("repeat(3 - rowProfiles.size)"))
        assertTrue(profileSection.contains("Modifier.fillMaxWidth().padding(horizontal = 12.dp)"))
        assertTrue(profileSection.contains(".rotate(uiRotationDegrees)"))
        val collapsedButton = profileSection.substringBefore("if (expanded)")
        assertFalse(collapsedButton.contains("rotate(uiRotationDegrees)"))
    }

    @Test
    fun `profile callbacks and outside dismissal stay authoritative`() {
        val selector = source("src/main/java/com/bncam/ui/screens/capture/ViewfinderSelectors.kt")
        val profileSection = selector.substringAfter("internal fun ViewfinderProfileSelector(")
            .substringBefore("internal fun ViewfinderLensSelector(")
        assertTrue(profileSection.contains("onProfileSelected(profile)"))
        assertTrue(profileSection.contains("onOpenProfileSettings(profile)"))
        assertTrue(profileSection.contains("focusable = true"))
        assertTrue(profileSection.contains("dismissOnClickOutside = true"))
    }
}
