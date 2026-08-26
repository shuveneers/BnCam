package com.bncam

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bncam.ui.screens.settings.lens_profiles.LensDetailScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Phase4Block2ConnectedTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun lensDetailScreenRendersAllThreeCalibrationCards() {
        composeTestRule.setContent {
            LensDetailScreen(
                lensId = "0",
                initialProfileAmount = "6",
                onProfileAmountChanged = {},
                onNavigateToProfileList = {},
                onNavigateBack = {}
            )
        }

        // Verify lens identity details
        composeTestRule.onNodeWithText("Lens 0 Calibration").assertExists()
        composeTestRule.onNodeWithText("stableLensKey").assertExists()

        // Verify all 3 calibration cards exist
        composeTestRule.onNodeWithText("Noise Model Calibration").assertExists()
        composeTestRule.onNodeWithText("Color Transform Scheme").assertExists()
        composeTestRule.onNodeWithText("Black Level Calibration").assertExists()
    }
}
