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
        assertTrue(camera.contains("onExpandedChange = { lensSelectorExpanded = it }"))
        assertTrue(camera.contains("Brush.radialGradient("))
        assertTrue(camera.contains("val radius = minOf(size.width * 0.96f, 380.dp.toPx())"))
        assertTrue(camera.contains("ViewfinderLensPopupList("))
        assertTrue(camera.contains("ViewfinderLensList("))
        assertTrue(camera.contains("LensIndicatorStyle.FLOATING"))
        assertTrue(camera.contains("LensIndicatorStyle.DIRECT_LIST"))
    }

    @Test
    fun `lens selector keeps radial drag halo strong haptics and camera switch glyph`() {
        val selector = source("src/main/java/com/bncam/ui/screens/capture/ViewfinderSelectors.kt")
        assertTrue(selector.contains("visibleLenses: List<LensInfo>"))
        assertTrue(selector.contains("detectDragGestures("))
        assertTrue(selector.contains("nearestLensSelectorIndex("))
        assertTrue(selector.contains("lensSelectorPoint("))
        assertTrue(selector.contains("buttonDiameterDp = 42f"))
        assertTrue(selector.contains("onExpandedChange: (Boolean) -> Unit = {}"))
        assertTrue(selector.contains("tapping the same lens indicator a second time always closes Floating mode"))
        assertTrue(selector.contains("internal fun ViewfinderLensPopupList("))
        assertTrue(selector.contains("internal fun ViewfinderLensList("))
        assertTrue(selector.contains("selfieUsesOuterArc = selfieLens != null && rearLenses.size >= 3"))
        assertFalse(selector.contains("One free-form cloud behind the entire selector"))
        assertTrue(selector.contains("HapticFeedbackType.LongPress"))
        assertTrue(selector.contains("Icons.Default.Cameraswitch"))
        assertTrue(selector.contains("hoveredIndex?.let { index -> selectorLenses.getOrNull(index)?.let(onLensSelected) }"))
        assertTrue(selector.contains("CameraCharacteristics.LENS_FACING_FRONT"))
        val floatingSection = selector.substringAfter("internal fun ViewfinderLensSelector(")
            .substringBefore("internal fun ViewfinderLensPopupList(")
        assertFalse(floatingSection.contains("text = \"Selfie\""))
    }

    @Test
    fun `profile popup is screen wide three columns and only popup content rotates`() {
        val selector = source("src/main/java/com/bncam/ui/screens/capture/ViewfinderSelectors.kt")
        val profileSection = selector.substringAfter("internal fun ViewfinderProfileSelector(")
            .substringBefore("internal fun ViewfinderLensSelector(")
        assertTrue(profileSection.contains("filterNot { it.id.endsWith(\"_disabled\") }"))
        assertTrue(profileSection.contains("orderedProfiles.chunked(3)"))
        assertTrue(profileSection.contains("disabledProfileEntry?.let { disabled ->"))
        assertTrue(profileSection.contains("onProfileSelected(disabled)"))
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
