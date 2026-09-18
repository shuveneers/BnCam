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

data class WhiteLevelRuntimeResolution(
    val settings: LensWhiteLevelSettings,
    val settingsReady: Boolean
)

/**
 * Capture-safe bridge from DataStore into the future non-suspending RAW White Level authority.
 *
 * This registry deliberately contains no Camera2 resolution logic. It only freezes the user's
 * per-lens selection. RawWhiteAuthorityPolicy will remain the single owner of metadata/manual
 * precedence once processing integration is added.
 */
object WhiteLevelRuntimeRegistry {
    private val cache = ConcurrentHashMap<String, LensWhiteLevelSettings>()
    private val observedLensKeys = ConcurrentHashMap.newKeySet<String>()
    private val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun invalidate(lensId: String) {
        cache.remove(SettingsRepository.safeLensKeyPart(lensId))
    }

    fun invalidateAll() {
        cache.clear()
    }

    fun resolve(lensId: String): WhiteLevelRuntimeResolution {
        val stableLensKey = SettingsRepository.safeLensKeyPart(lensId)
        val context = BnCamProcessContext.getOrNull()
        if (context != null) ensureObserved(context, lensId, stableLensKey)

        cache[stableLensKey]?.let {
            return WhiteLevelRuntimeResolution(it, settingsReady = true)
        }

        if (context == null) {
            return WhiteLevelRuntimeResolution(LensWhiteLevelSettings(), settingsReady = false)
        }
        val loaded = loadBlocking(context, lensId)
            ?: return WhiteLevelRuntimeResolution(LensWhiteLevelSettings(), settingsReady = false)
        cache[stableLensKey] = loaded
        return WhiteLevelRuntimeResolution(loaded, settingsReady = true)
    }

    private fun ensureObserved(context: Context, lensId: String, stableLensKey: String) {
        if (!observedLensKeys.add(stableLensKey)) return
        observerScope.launch {
            WhiteLevelSettingsStore(context).settingsFlow(lensId).collectLatest { settings ->
                cache[stableLensKey] = settings.sanitized()
            }
        }
    }

    private fun loadBlocking(context: Context, lensId: String): LensWhiteLevelSettings? =
        runCatching {
            runBlocking(Dispatchers.IO) {
                WhiteLevelSettingsStore(context).get(lensId)
            }
        }.getOrNull()
}
