package com.bncam.data.profile

import java.util.UUID

/**
 * Persistent profile identity/ownership metadata.
 *
 * ISP, capture, tone, colour, noise, detail and JPEG settings are intentionally NOT stored in
 * this object. Their single source of truth is SettingsRepository's profile-scoped setting keys
 * and the portable `.bnc` snapshot contract. Keeping settings here as a second model previously
 * created stale/default values that could disagree with the active profile UI and renderer.
 */
data class IspProfileConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val ownerStableLensKey: String,
    val schemaVersion: Int = SCHEMA_VERSION,
    val revision: Long = 1L,
    val isDefault: Boolean = false,
    val lastModifiedMs: Long = System.currentTimeMillis()
) {
    val ownerLensId: String get() = ownerStableLensKey
    val targetLensId: String get() = ownerStableLensKey

    companion object {
        /** Metadata schema only. Portable profile settings use BncProfileCodec's schema. */
        const val SCHEMA_VERSION = 5

        fun createDefault(lensId: String, name: String = "Default Profile"): IspProfileConfig {
            return IspProfileConfig(
                id = "${lensId}_profile_default",
                name = name,
                ownerStableLensKey = lensId,
                isDefault = true
            )
        }
    }

    fun toCameraProfile(isUnlocked: Boolean = true): CameraProfile {
        return CameraProfile(
            id = id,
            name = name,
            targetLensId = ownerStableLensKey,
            isVisibleInUi = true,
            isLocked = !isUnlocked
        )
    }
}
