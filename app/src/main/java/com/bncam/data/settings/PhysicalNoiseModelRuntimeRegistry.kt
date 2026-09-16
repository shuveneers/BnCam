package com.bncam.data.settings

import android.content.Context
import com.bncam.BnCamProcessContext
import com.bncam.core.quality.NoiseModelCfaPattern
import com.bncam.core.quality.NoiseModelSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

/**
 * Capture-safe bridge from DataStore to the non-suspending shutter-time snapshot constructor.
 * Settings are cached by stable lens key. UI writes invalidate the relevant cache entry; preset
 * catalog mutations invalidate all entries because any lens can select a global user preset.
 */
object PhysicalNoiseModelRuntimeRegistry {
    private data class CachedSettings(
        val settings: LensPhysicalNoiseModelSettings,
        val userPresets: List<NoiseModelPreset>
    )

    private val cache = ConcurrentHashMap<String, CachedSettings>()

    fun initialize(context: Context) {
        BnCamProcessContext.initialize(context)
    }

    fun invalidate(lensId: String) {
        cache.remove(SettingsRepository.safeLensKeyPart(lensId))
    }

    fun invalidateAll() {
        cache.clear()
    }

    fun resolve(
        lensId: String,
        captureIso: Int,
        cfaName: String,
        cameraS: DoubleArray,
        cameraO: DoubleArray
    ): PhysicalNoiseModelCaptureResolution? {
        val cfaPattern = runCatching { NoiseModelCfaPattern.fromName(cfaName) }.getOrNull()
            ?: return null
        val stableLensKey = SettingsRepository.safeLensKeyPart(lensId)
        val context = BnCamProcessContext.getOrNull()
        val cached = cache[stableLensKey]
        val loaded = cached ?: context?.let { loadBlocking(it, lensId) }?.also {
            cache[stableLensKey] = it
        }

        // If process context is not ready, preserve physical continuity with OEM rather than using
        // a stale legacy Manual/Off path. This state is transient during process startup only.
        if (loaded == null) {
            val fallbackSettings = LensPhysicalNoiseModelSettings(source = NoiseModelSource.OEM)
            return PhysicalNoiseModelCaptureResolver.resolve(
                lensId = lensId,
                captureIso = captureIso,
                cfaPattern = cfaPattern,
                cameraS = cameraS,
                cameraO = cameraO,
                settings = fallbackSettings,
                userPresets = emptyList()
            ).copy(settingsReady = false)
        }

        return PhysicalNoiseModelCaptureResolver.resolve(
            lensId = lensId,
            captureIso = captureIso,
            cfaPattern = cfaPattern,
            cameraS = cameraS,
            cameraO = cameraO,
            settings = loaded.settings,
            userPresets = loaded.userPresets
        )
    }

    private fun loadBlocking(context: Context, lensId: String): CachedSettings? = runCatching {
        runBlocking(Dispatchers.IO) {
            val settings = PhysicalNoiseModelSettingsStore(context).get(lensId)
            val userPresets = PhysicalNoiseModelPresetStore(context).userPresetsFlow.first()
            CachedSettings(settings, userPresets)
        }
    }.getOrNull()
}
