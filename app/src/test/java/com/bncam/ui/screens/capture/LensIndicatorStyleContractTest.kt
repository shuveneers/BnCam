package com.bncam.ui.screens.capture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class LensIndicatorStyleContractTest {
    private fun source(path: String): String {
        val roots = listOf(File("."), File("app"))
        return roots.asSequence().map { File(it, path) }.firstOrNull { it.isFile }?.readText()
            ?: error("Missing source file: $path")
    }

    @Test
    fun `lens indicator styles are persistent and default to floating`() {
        val pref = source("src/main/java/com/bncam/data/settings/LensIndicatorStylePreference.kt")
        assertTrue(pref.contains("FLOATING(\"floating\", \"Floating\")"))
        assertTrue(pref.contains("LIST(\"popup_list\", \"List\")"))
        assertTrue(pref.contains("DIRECT_LIST(\"list\", \"Direct list\")"))
        assertTrue(pref.contains("?: FLOATING"))
        assertTrue(pref.contains("lens_indicator_style"))
    }

    @Test
    fun `app settings exposes all three lens indicator styles under interaction settings`() {
        val settings = source("src/main/java/com/bncam/ui/screens/settings/AppSettingsScreen.kt")
        assertTrue(settings.contains("SettingsCard(title = \"Device & Interaction\")"))
        assertTrue(settings.contains("title = \"Lens indicator style\""))
        assertTrue(settings.contains("LensIndicatorStyle.FLOATING -> LensIndicatorStyle.LIST"))
        assertTrue(settings.contains("LensIndicatorStyle.LIST -> LensIndicatorStyle.DIRECT_LIST"))
        assertTrue(settings.contains("LensIndicatorStyle.DIRECT_LIST -> LensIndicatorStyle.FLOATING"))
    }

    @Test
    fun `camera screen switches among floating popup list and direct list without changing lens routing`() {
        val camera = source("src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt")
        assertTrue(camera.contains("LensIndicatorStyle.FLOATING ->"))
        assertTrue(camera.contains("LensIndicatorStyle.LIST ->"))
        assertTrue(camera.contains("LensIndicatorStyle.DIRECT_LIST ->"))
        assertTrue(camera.contains("ViewfinderLensSelector("))
        assertTrue(camera.contains("ViewfinderLensPopupList("))
        assertTrue(camera.contains("ViewfinderLensList("))
        assertTrue(camera.contains("onLensSelected = onLensSelected"))
    }

    @Test
    fun `popup list keeps the master indicator and labels the active sensor`() {
        val selector = source("src/main/java/com/bncam/ui/screens/capture/ViewfinderSelectors.kt")
        val popupList = selector.substringAfter("internal fun ViewfinderLensPopupList(")
            .substringBefore("internal fun ViewfinderLensList(")
        assertTrue(popupList.contains("modifier = Modifier.size(72.dp)"))
        assertTrue(popupList.contains("AnchorPopupPositionProvider(placeAbove = true"))
        assertTrue(popupList.contains("text = \"Active\""))
        assertTrue(popupList.contains("AccentPistachio.copy(alpha = 0.78f)"))
        assertTrue(popupList.contains("orderedLenses.forEach"))
        assertTrue(popupList.contains("dismissOnClickOutside = true"))
    }
}
