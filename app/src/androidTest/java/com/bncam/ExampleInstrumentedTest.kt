package com.bncam

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*
import com.bncam.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.bncam", appContext.packageName)
    }

    @Test
    fun cameraAndPerLensSettingsPersistThroughDataStore() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = SettingsRepository(context)
        val previousMetering = repository.meteringStyleFlow.first()
        val lensId = "instrumentation_test_lens"
        val previousBlackMode = repository.getBlackLevelModeFlow(lensId).first()
        try {
            repository.setMeteringStyle("Center Weighted")
            repository.setBlackLevelMode(lensId, "Manual")

            assertEquals("Center Weighted", repository.meteringStyleFlow.first())
            assertEquals("Manual", repository.getBlackLevelModeFlow(lensId).first())
        } finally {
            repository.setMeteringStyle(previousMetering)
            repository.setBlackLevelMode(lensId, previousBlackMode)
        }
    }
}
