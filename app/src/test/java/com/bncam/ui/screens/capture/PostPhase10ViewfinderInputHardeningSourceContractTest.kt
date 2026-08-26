package com.bncam.ui.screens.capture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class PostPhase10ViewfinderInputHardeningSourceContractTest {
    private val appDir = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .firstOrNull { File(it, "src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt").isFile }
        ?: error("Unable to locate app module")

    private fun read(path: String) = File(appDir, path).readText()

    @Test
    fun `quick settings downward swipe excludes side control lanes and active slider drags`() {
        val camera = read("src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt")
        assertTrue("sideControlExclusionPx = 96.dp.toPx()" in camera)
        assertTrue("leftSliderGestureActive || rightSliderGestureActive" in camera)
        assertTrue("onGestureActiveChange = { leftSliderGestureActive = it }" in camera)
        assertTrue("onGestureActiveChange = { rightSliderGestureActive = it }" in camera)
    }

    @Test
    fun `camera root requires a second back press while nested navigation remains owner of its back stack`() {
        val camera = read("src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt")
        val navigation = read("src/main/java/com/bncam/ui/navigation/AppNavigation.kt")
        assertTrue("BackHandler(enabled = isRootCameraRoute && !quickSettingsExpanded)" in camera)
        assertTrue("now - lastExitBackPressElapsedMs <= 2_000L" in camera)
        assertTrue("isRootCameraRoute = isRootCameraRoute" in navigation)
        assertTrue("currentBackStackEntryAsState()" in navigation)
    }

    @Test
    fun `orientation rotation reaches profile side slider focus and dedicated exposure controls`() {
        val camera = read("src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt")
        val selectors = read("src/main/java/com/bncam/ui/screens/capture/ViewfinderSelectors.kt")
        assertTrue("uiRotationDegrees = animatedUiRotation" in camera)
        assertTrue("modifier = Modifier.rotate(uiRotationDegrees)" in selectors)
        assertTrue("onGestureActiveChange: (Boolean) -> Unit" in camera)
    }
}
