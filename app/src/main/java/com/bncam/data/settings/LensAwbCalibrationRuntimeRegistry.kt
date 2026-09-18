package com.bncam.data.settings

import android.content.Context
import com.bncam.BnCamProcessContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class LensAwbCalibrationRuntimeResolution(
    val settings: LensAwbCalibrationSettings,
    val settingsReady: Boolean
)

/**
 * Compatibility-named AWB runtime registry.
 *
 * AWB is profile-owned from delta 23 onward. The lens cache is retained only as a one-time
 * migration/fallback source for installations that already persisted delta 20/21 lens AWB.
 */
object LensAwbCalibrationRuntimeRegistry {
    private val profileCache = ConcurrentHashMap<String, LensAwbCalibrationSettings>()
    private val legacyLensCache = ConcurrentHashMap<String, LensAwbCalibrationSettings>()
    private val observedProfiles = ConcurrentHashMap.newKeySet<String>()
    private val activeProfileObserverStarted = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var activeProfileId: String? = null

    /** Legacy lens publication used only for migration/fallback compatibility. */
    fun publish(lensId: String, settings: LensAwbCalibrationSettings) {
        legacyLensCache[SettingsRepository.safeLensKeyPart(lensId)] = settings.sanitized()
    }

    /** Immediate profile publication removes the DataStore timing window after a UI edit. */
    fun publishProfile(profileId: String, settings: LensAwbCalibrationSettings) {
        if (profileId.isBlank()) return
        profileCache[profileId] = settings.sanitized()
    }

    fun activateProfile(profileId: String?) {
        activeProfileId = profileId?.takeIf { it.isNotBlank() && !it.endsWith("_disabled") }
        val context = BnCamProcessContext.getOrNull() ?: return
        ensureActiveProfileObserved(context)
        activeProfileId?.let { ensureProfileObserved(context, it) }
    }

    fun invalidate(lensId: String) {
        legacyLensCache.remove(SettingsRepository.safeLensKeyPart(lensId))
    }

    fun invalidateAll() {
        profileCache.clear()
        legacyLensCache.clear()
    }

    fun resolve(lensId: String): LensAwbCalibrationRuntimeResolution {
        val context = BnCamProcessContext.getOrNull()
        if (context == null) {
            val legacy = legacyLensCache[SettingsRepository.safeLensKeyPart(lensId)]
            return LensAwbCalibrationRuntimeResolution(legacy ?: LensAwbCalibrationSettings(), legacy != null)
        }

        ensureActiveProfileObserved(context)
        val profileId = activeProfileId ?: runCatching {
            runBlocking(Dispatchers.IO) { SettingsRepository(context).activeProfileIdFlow.first() }
        }.getOrNull()?.takeIf { it.isNotBlank() && !it.endsWith("_disabled") }?.also {
            activeProfileId = it
        }

        if (profileId != null) {
            ensureProfileObserved(context, profileId)
            profileCache[profileId]?.let { return LensAwbCalibrationRuntimeResolution(it, true) }
            val loaded = runCatching {
                runBlocking(Dispatchers.IO) { ProfileAwbCalibrationSettingsStore(context).get(profileId) }
            }.getOrNull()
            if (loaded != null) {
                profileCache[profileId] = loaded
                return LensAwbCalibrationRuntimeResolution(loaded, true)
            }
        }

        // Startup/legacy fallback only. Once an active profile exists, that profile is authoritative.
        val lensKey = SettingsRepository.safeLensKeyPart(lensId)
        legacyLensCache[lensKey]?.let { return LensAwbCalibrationRuntimeResolution(it, true) }
        val legacy = runCatching {
            runBlocking(Dispatchers.IO) { LensAwbCalibrationSettingsStore(context).get(lensId) }
        }.getOrNull() ?: return LensAwbCalibrationRuntimeResolution(LensAwbCalibrationSettings(), false)
        legacyLensCache[lensKey] = legacy
        return LensAwbCalibrationRuntimeResolution(legacy, true)
    }

    private fun ensureActiveProfileObserved(context: Context) {
        if (!activeProfileObserverStarted.compareAndSet(false, true)) return
        scope.launch {
            SettingsRepository(context).activeProfileIdFlow.collectLatest { profileId ->
                activeProfileId = profileId?.takeIf { it.isNotBlank() && !it.endsWith("_disabled") }
                activeProfileId?.let { ensureProfileObserved(context, it) }
            }
        }
    }

    private fun ensureProfileObserved(context: Context, profileId: String) {
        if (!observedProfiles.add(profileId)) return
        scope.launch {
            ProfileAwbCalibrationSettingsStore(context).settingsFlow(profileId).collectLatest { settings ->
                profileCache[profileId] = settings.sanitized()
            }
        }
    }
}
