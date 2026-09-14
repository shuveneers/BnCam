package com.bncam.ui.screens.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawPreviewGpuInteropStateMachineTest {
    @Test
    fun transientFailure_newEglGeneration_allowsExactlyOneReprobe() {
        val state = RawPreviewGpuInteropStateMachine()
        state.onPipelineGeneration(7)
        state.onEglGeneration(1)
        assertTrue(state.beginGpuAttempt(7, 1))
        assertTrue(state.markTransientFailure("fence_failed", 7, 1))
        assertFalse(state.beginGpuAttempt(7, 1))

        assertTrue(state.onEglGeneration(2))
        assertTrue(state.beginGpuAttempt(7, 2))
        assertFalse(state.beginGpuAttempt(7, 2))
    }

    @Test
    fun transientFailure_sameGeneration_neverRetriesPerFrame() {
        val state = RawPreviewGpuInteropStateMachine()
        state.onPipelineGeneration(11)
        state.onEglGeneration(3)
        assertTrue(state.beginGpuAttempt(11, 3))
        assertTrue(state.markTransientFailure("egl_bind_failed", 11, 3))
        repeat(100) { assertFalse(state.beginGpuAttempt(11, 3)) }
        assertEquals(RawPreviewGpuInteropMode.CPU_FALLBACK_TRANSIENT, state.snapshot().mode)
    }

    @Test
    fun newPipelineGeneration_reopensTransientFallbackOnce() {
        val state = RawPreviewGpuInteropStateMachine()
        state.onPipelineGeneration(4)
        state.onEglGeneration(8)
        assertTrue(state.beginGpuAttempt(4, 8))
        state.markTransientFailure("native_import_failed", 4, 8)

        assertTrue(state.onPipelineGeneration(5))
        assertTrue(state.beginGpuAttempt(5, 8))
        assertFalse(state.beginGpuAttempt(5, 8))
    }

    @Test
    fun staleGenerationFailure_cannotDowngradeNewHealthyState() {
        val state = RawPreviewGpuInteropStateMachine()
        state.onPipelineGeneration(20)
        state.onEglGeneration(2)
        assertTrue(state.beginGpuAttempt(20, 2))
        assertTrue(state.markGpuActive(20, 2))

        state.onPipelineGeneration(21)
        assertFalse(state.markTransientFailure("late_old_fence", 20, 2))
        assertEquals(RawPreviewGpuInteropMode.GPU_ACTIVE, state.snapshot().mode)
        assertEquals(21, state.snapshot().pipelineGeneration)
    }

    @Test
    fun staleEglFailure_cannotDowngradeNewContext() {
        val state = RawPreviewGpuInteropStateMachine()
        state.onPipelineGeneration(2)
        state.onEglGeneration(10)
        assertTrue(state.beginGpuAttempt(2, 10))
        state.markTransientFailure("old_context_failure", 2, 10)

        assertTrue(state.onEglGeneration(11))
        assertTrue(state.beginGpuAttempt(2, 11))
        assertTrue(state.markGpuActive(2, 11))
        assertFalse(state.markTransientFailure("late_old_context", 2, 10))
        assertEquals(RawPreviewGpuInteropMode.GPU_ACTIVE, state.snapshot().mode)
    }

    @Test
    fun permanentFailure_doesNotReprobeOnGenerationChanges() {
        val state = RawPreviewGpuInteropStateMachine()
        state.onPipelineGeneration(1)
        state.onEglGeneration(1)
        assertTrue(state.beginGpuAttempt(1, 1))
        assertTrue(state.markPermanentFailure("vulkan_capability_missing", 1, 1))
        assertFalse(state.onEglGeneration(2))
        assertFalse(state.onPipelineGeneration(2))
        assertFalse(state.beginGpuAttempt(2, 2))
        assertEquals(RawPreviewGpuInteropMode.CPU_FALLBACK_PERMANENT, state.snapshot().mode)
    }
}
