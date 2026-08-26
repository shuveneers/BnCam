package com.bncam

import android.content.BroadcastReceiver
import com.bncam.data.settings.SettingsRepository

/**
 * Release builds do not register an external benchmark receiver and contain no debug action names.
 */
object BenchmarkDebugReceiverController {
    fun register(
        activity: MainActivity,
        repository: SettingsRepository
    ): BroadcastReceiver? = null
}
