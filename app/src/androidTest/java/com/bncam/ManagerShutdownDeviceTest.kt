package com.bncam

import android.Manifest
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bncam.core.engine.BnCameraManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManagerShutdownDeviceTest {
    @Test fun activityDestructionFinishesCameraManagerShutdownWithoutThreadCrash() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, Manifest.permission.CAMERA)
        var previous: BnCameraManager? = null
        repeat(2) {
            val scenario = ActivityScenario.launch(MainActivity::class.java)
            try {
                val deadline = SystemClock.elapsedRealtime() + 15_000
                while (BnCameraManager.activeInstance == null && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
                val manager = requireNotNull(BnCameraManager.activeInstance)
                assertNotSame(previous, manager)
                previous = manager
                SystemClock.sleep(1000)
            } finally { scenario.close() }
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (BnCameraManager.activeInstance != null && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
            assertNull("shutdown did not release the Activity-scoped manager", BnCameraManager.activeInstance)
        }
    }
}
