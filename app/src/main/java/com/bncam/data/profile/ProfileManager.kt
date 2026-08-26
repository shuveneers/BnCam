package com.bncam.data.profile

import com.bncam.core.engine.CaptureStrategy

object ProfileManager {

    fun getProfilesForLens(
        lensId: String,
        activeCount: Int,
        savedNames: Map<String, String>, // Map van profileId naar opgeslagen naam
        savedModes: Map<String, CaptureStrategy> = emptyMap()
    ): List<CameraProfile> {
        val profiles = mutableListOf<CameraProfile>()

        // 0. Altijd aanwezig: Disabled
        val disabledId = "${lensId}_disabled"
        profiles.add(
            CameraProfile(
                id = disabledId,
                name = savedNames[disabledId] ?: "Disabled",
                targetLensId = lensId,
                captureStrategy = savedModes[disabledId] ?: CaptureStrategy.SINGLE_FRAME_ZSL,
                noiseReductionMode = 0,
                edgeEnhancementMode = 0,
                isVisibleInUi = true,
                isLocked = false
            )
        )

        // 1 t/m 12. De dynamische slots
        for (i in 1..12) {
            val isVisibleAndUnlocked = i <= activeCount
            val profileId = "${lensId}_profile_$i"

            profiles.add(
                CameraProfile(
                    id = profileId,
                    name = savedNames[profileId] ?: "Profile $i",
                    targetLensId = lensId,
                    captureStrategy = savedModes[profileId] ?: CaptureStrategy.SINGLE_FRAME_ZSL,
                    noiseReductionMode = 1,
                    edgeEnhancementMode = 1,
                    isVisibleInUi = isVisibleAndUnlocked,
                    isLocked = !isVisibleAndUnlocked
                )
            )
        }

        return profiles
    }
}