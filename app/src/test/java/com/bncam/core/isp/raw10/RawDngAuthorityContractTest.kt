package com.bncam.core.isp.raw10

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RawDngAuthorityContractTest {
    private val safe = RawDngAuthorityContract.Input(true, true, true, true, true, true, true)

    @Test fun exactFrameAuthorityAllowsDng() {
        assertTrue(RawDngAuthorityContract.evaluate(safe).safeForDng)
    }

    @Test fun foreignRuntimeCameraIdIsRejected() {
        assertEquals(
            "RUNTIME_CAMERA_ID_MISMATCH",
            RawDngAuthorityContract.evaluate(safe.copy(runtimeCameraIdMatches = false)).reason
        )
    }

    @Test fun wrongFrameTimestampIsRejected() {
        assertEquals(
            "FRAME_TIMESTAMP_MISMATCH",
            RawDngAuthorityContract.evaluate(safe.copy(frameTimestampMatches = false)).reason
        )
    }
}
