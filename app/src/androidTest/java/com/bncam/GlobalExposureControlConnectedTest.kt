package com.bncam

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bncam.core.capture.*
import com.bncam.data.settings.*
import com.bncam.ui.screens.capture.*
import com.bncam.ui.screens.settings.AppSettingsScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlobalExposureControlConnectedTest {
    @get:Rule val ui = createComposeRule()
    private fun repository() = SettingsRepository(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test fun appSettingsExposeOnlyTheTwoGlobalAutomaticControllers() {
        val repo = repository()
        val previous = runBlocking { repo.exposureControlFlow.first() }
        try {
            runBlocking { repo.setExposureControl(SensorExposureMode.STANDARD_AUTO) }
            ui.setContent { AppSettingsScreen("unknown") {} }
            ui.onNodeWithText("Exposure Control").performClick()
            ui.onNodeWithText("Manual", useUnmergedTree = true).assertDoesNotExist()
            ui.waitUntil(5000) { runBlocking { repo.exposureControlFlow.first() == SensorExposureMode.BN_AUTO } }
            ui.onNodeWithText("BnC Auto").assertExists()
        } finally { runBlocking { repo.setExposureControl(previous) } }
    }

    @Test fun assignableTileKeepsNineSlotsAndManualInputs() {
        val repo = repository()
        val previous = runBlocking { repo.exposureControlFlow.first() }
        val previousSlots = runBlocking { repo.quickSettingsAssignmentsFlow.first() }
        var manual by mutableStateOf(false)
        try {
            runBlocking {
                repo.setExposureControl(SensorExposureMode.STANDARD_AUTO)
                repo.setQuickSettingsAssignments(ViewfinderQuickSettingIds.defaults)
            }
            ui.setContent {
                val mode by repo.exposureControlFlow.collectAsState(SensorExposureMode.STANDARD_AUTO)
                val slots by repo.quickSettingsAssignmentsFlow.collectAsState(ViewfinderQuickSettingIds.defaults)
                val scope = rememberCoroutineScope()
                ViewfinderQuickSettingsOverlay(
                    flashMode = "Off", timerDurationSeconds = 0, watermarkEnabled = false,
                    outputPolicy = OutputPolicy.JPEG, viewfinderStream = ViewfinderStream.YUV,
                    geotagEnabled = false, focusPeakingEnabled = false,
                    meteringMode = MeteringMode.AUTO_DEFAULT_AE,
                    exposureControlLabel = if (manual) "Manual" else mode.label,
                    onExposureControlToggle = { scope.launch { repo.toggleExposureControl() } },
                    histogramEnabled = false, focusTrackingEnabled = false, horizonLevelerEnabled = false,
                    faceDetectionEnabled = false, quickSettingAssignments = slots,
                    viewfinderMode = ViewfinderMode.PHOTO, ultraHdrEnabled = false,
                    onFlashModeChange = {}, onTimerDurationChange = {}, onWatermarkEnabledChange = {},
                    onOutputPolicyChange = {}, onViewfinderStreamChange = {}, onGeotagEnabledChange = {},
                    onFocusPeakingEnabledChange = {}, onMeteringModeChange = {}, onHistogramEnabledChange = {},
                    onFocusTrackingEnabledChange = {}, onHorizonLevelerEnabledChange = {}, onFaceDetectionEnabledChange = {},
                    onQuickSettingAssigned = { slot, id -> scope.launch {
                        repo.setQuickSettingsAssignments(slots.toMutableList().apply { this[slot] = id })
                    } }, onShotModeSelected = {}, onDismiss = {}
                )
            }
            ui.onNodeWithText("AE Control").assertDoesNotExist()
            ui.onNodeWithContentDescription("Edit quick settings").performClick()
            ui.onNodeWithText("Setting 1").performClick()
            ui.onNodeWithText("AE Control").performClick()
            ui.onNodeWithContentDescription("Back").performClick()
            ui.onNodeWithText("Metering").assertExists()
            ui.onNodeWithText("Flash").assertDoesNotExist()
            ui.onNodeWithText("AE Control").performClick()
            ui.waitUntil(5000) { runBlocking { repo.exposureControlFlow.first() == SensorExposureMode.BN_AUTO } }
            ui.onNodeWithText("BnC Auto").assertExists()
            ui.runOnIdle { manual = true }
            ui.onNodeWithText("Manual").assertExists()
            ui.onNodeWithText("AE Control").performClick()
            ui.waitUntil(5000) { runBlocking { repo.exposureControlFlow.first() == SensorExposureMode.STANDARD_AUTO } }
            ui.runOnIdle { assertTrue(manual) }
            assertEquals(9, runBlocking { repo.quickSettingsAssignmentsFlow.first().size })
        } finally { runBlocking {
            repo.setExposureControl(previous)
            repo.setQuickSettingsAssignments(previousSlots)
        } }
    }
}

@RunWith(AndroidJUnit4::class)
class GlobalExposureControlRepositoryConnectedTest {
    private fun repository() = SettingsRepository(InstrumentationRegistry.getInstrumentation().targetContext)
    @Test fun legacyProfilesCannotOverwriteGlobalPreference() = runBlocking {
        val repo = repository()
        val previous = repo.exposureControlFlow.first()
        val previousSlots = repo.quickSettingsAssignmentsFlow.first()
        val profile = "global_control_test_profile"
        try {
            repo.setExposureControl(SensorExposureMode.BN_AUTO)
            for (legacy in listOf("STANDARD_AUTO", "BN_AUTO", "MANUAL")) {
                repo.setProfileStringOverride(profile, CaptureSettingKeys.SENSOR_EXPOSURE_MODE, legacy)
                assertEquals(SensorExposureMode.BN_AUTO, repository().exposureControlFlow.first())
            }
            assertEquals(previousSlots, repo.quickSettingsAssignmentsFlow.first())
            assertEquals(9, previousSlots.size)
            try {
                repo.setExposureControl(SensorExposureMode.MANUAL)
                fail("Manual must never be stored as the auto preference")
            } catch (_: IllegalArgumentException) { }
            assertEquals(SensorExposureMode.BN_AUTO, repo.exposureControlFlow.first())
            repo.toggleExposureControl()
            assertEquals(SensorExposureMode.STANDARD_AUTO, repository().exposureControlFlow.first())
        } finally {
            repo.clearProfileOverrideValues(profile, listOf(ProfileSettingSpec(
                CaptureSettingKeys.SENSOR_EXPOSURE_MODE, ProfileSettingValueType.STRING)))
            repo.setExposureControl(previous)
        }
    }

}
