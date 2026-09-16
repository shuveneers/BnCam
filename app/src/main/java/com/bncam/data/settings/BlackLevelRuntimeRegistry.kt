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

data class BlackLevelRuntimeResolution(
    val settings: LensBlackLevelControlSettings,
    val settingsReady: Boolean
)

/**
 * Capture-safe bridge from DataStore into non-suspending RAW black-level resolution.
 *
 * The first unresolved lookup may perform one IO read. Afterwards a per-lens DataStore observer
 * keeps the cache coherent, including the existing global "Reset lens hardware settings" path
 * which deletes the whole hardware_lens_<id>_* prefix without calling this registry directly.
 */
object BlackLevelRuntimeRegistry {
    private val cache = ConcurrentHashMap<String, LensBlackLevelControlSettings>()
    private val observedLensKeys = ConcurrentHashMap.newKeySet<String>()
    private val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun invalidate(lensId: String) {
        cache.remove(SettingsRepository.safeLensKeyPart(lensId))
    }

    fun invalidateAll() {
        cache.clear()
    }

    fun resolve(lensId: String): BlackLevelRuntimeResolution {
        val stableLensKey = SettingsRepository.safeLensKeyPart(lensId)
        val context = BnCamProcessContext.getOrNull()
        if (context != null) ensureObserved(context, lensId, stableLensKey)

        cache[stableLensKey]?.let {
            return BlackLevelRuntimeResolution(it, settingsReady = true)
        }

        if (context == null) {
            return BlackLevelRuntimeResolution(LensBlackLevelControlSettings(), settingsReady = false)
        }
        val loaded = loadBlocking(context, lensId)
            ?: return BlackLevelRuntimeResolution(LensBlackLevelControlSettings(), settingsReady = false)
        cache[stableLensKey] = loaded
        return BlackLevelRuntimeResolution(loaded, settingsReady = true)
    }

    private fun ensureObserved(context: Context, lensId: String, stableLensKey: String) {
        if (!observedLensKeys.add(stableLensKey)) return
        observerScope.launch {
            BlackLevelSettingsStore(context).settingsFlow(lensId).collectLatest { settings ->
                cache[stableLensKey] = settings.sanitized()
            }
        }
    }

    private fun loadBlocking(context: Context, lensId: String): LensBlackLevelControlSettings? =
        runCatching {
            runBlocking(Dispatchers.IO) {
                BlackLevelSettingsStore(context).get(lensId)
            }
        }.getOrNull()
}
