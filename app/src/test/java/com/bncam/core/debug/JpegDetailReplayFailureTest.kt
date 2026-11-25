package com.bncam.core.debug

import java.io.IOException
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class JpegDetailReplayFailureTest {
    @Test
    fun unavailableReplayLetsCaptureContinue() {
        val reported = mutableListOf<Throwable>()
        val unavailableNativeMethod = UnsatisfiedLinkError("comparison JNI")
        val unwritableComparison = IOException("comparison output")

        assertNull(runOptionalDetailRefinementReplay(reported::add) { throw unavailableNativeMethod })
        assertNull(runOptionalDetailRefinementReplay(reported::add) { throw unwritableComparison })
        assertEquals(listOf<Throwable>(unavailableNativeMethod, unwritableComparison), reported)
        assertEquals("baseline.jpg", runOptionalDetailRefinementReplay(reported::add) { "baseline.jpg" })
    }

    @Test
    fun cancellationStillPropagates() {
        assertFailsWith<CancellationException> {
            runOptionalDetailRefinementReplay({ error("Cancellation is not a diagnostic failure") }) {
                throw CancellationException()
            }
        }
    }
}
