package com.bncam.data.settings

import android.content.Context
import com.bncam.BnCamProcessContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

data class LensAwbCalibrationRuntimeResolution(
    val settings: LensAwbCalibrationSettings,
    val settingsReady: Boolean
)

object LensAwbCalibrationRuntimeRegistry {
    private val cache = ConcurrentHashMap<String, LensAwbCalibrationSettings>()
    private val observed = ConcurrentHashMap.newKeySet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Publishes a successfully persisted Lens-ID AWB state directly to capture runtime.
     * This removes the DataStore-observer timing window between the settings UI and shutter.
     */
    fun publish(lensId: String, settings: LensAwbCalibrationSettings) {
        cache[SettingsRepository.safeLensKeyPart(lensId)] = settings.sanitized()
    }

    fun invalidate(lensId: String) {
        cache.remove(SettingsRepository.safeLensKeyPart(lensId))
    }

    fun invalidateAll() = cache.clear()

    fun resolve(lensId: String): LensAwbCalibrationRuntimeResolution {
        val key = SettingsRepository.safeLensKeyPart(lensId)
        val context = BnCamProcessContext.getOrNull()
        if (context != null) ensureObserved(context, lensId, key)
        cache[key]?.let { return LensAwbCalibrationRuntimeResolution(it, true) }
        if (context == null) return LensAwbCalibrationRuntimeResolution(LensAwbCalibrationSettings(), false)
        val loaded = runCatching {
            runBlocking(Dispatchers.IO) { LensAwbCalibrationSettingsStore(context).get(lensId) }
        }.getOrNull() ?: return LensAwbCalibrationRuntimeResolution(LensAwbCalibrationSettings(), false)
        cache[key] = loaded
        return LensAwbCalibrationRuntimeResolution(loaded, true)
    }

    private fun ensureObserved(context: Context, lensId: String, key: String) {
        if (!observed.add(key)) return
        scope.launch {
            LensAwbCalibrationSettingsStore(context).settingsFlow(lensId).collectLatest { settings ->
                cache[key] = settings.sanitized()
            }
        }
    }
}
