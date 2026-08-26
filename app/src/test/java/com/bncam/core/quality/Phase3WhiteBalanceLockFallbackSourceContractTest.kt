package com.bncam.core.quality

import java.io.File
import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class Phase3WhiteBalanceLockFallbackSourceContractTest {
    @Test
    fun `yuv wb remains defensive after explicit user lock removal`() {
        val manager = File("src/main/java/com/bncam/core/engine/BnCameraManager.kt").readText()
        val state = File("src/main/java/com/bncam/core/quality/WhiteBalanceStateEngine.kt").readText()

        assertFalse(manager.contains("whiteBalanceLockRequested"))
        assertFalse(manager.contains("fun setWhiteBalanceLock"))
        assertTrue(manager.contains("ProfileYuvAwbMapper.resolve("))
        assertTrue(manager.contains("CaptureRequest.CONTROL_AWB_LOCK, false"))
        assertTrue(state.contains("private val storedGains: FloatArray = gains.copyOf()"))
        assertTrue(state.contains("get() = storedGains.copyOf()"))
        assertTrue(state.contains("fun copyGains(): FloatArray = storedGains.copyOf()"))
    }
}
