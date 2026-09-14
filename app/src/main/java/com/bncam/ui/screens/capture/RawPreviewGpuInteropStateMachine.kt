package com.bncam.ui.screens.capture

internal enum class RawPreviewGpuInteropMode {
    PROBING,
    GPU_ACTIVE,
    CPU_FALLBACK_TRANSIENT,
    CPU_FALLBACK_PERMANENT,
    CLOSED
}

internal data class RawPreviewGpuInteropSnapshot(
    val mode: RawPreviewGpuInteropMode,
    val reason: String,
    val pipelineGeneration: Int,
    val eglGeneration: Long,
    val failureCount: Long,
    val probeAttemptCount: Long
)

/**
 * Generation-scoped policy for the Vulkan-AHB -> EGLImage/GLES presentation path.
 *
 * A transient failure is sticky for the exact (pipeline, EGL) generation pair so a broken path is
 * never retried every frame. A newer pipeline or EGL generation earns exactly one controlled probe.
 * Permanent capability failures remain on CPU fallback for the lifetime of this renderer.
 * Stale completions from older generations are ignored and can therefore never downgrade a newer
 * healthy path.
 */
internal class RawPreviewGpuInteropStateMachine {
    private var mode = RawPreviewGpuInteropMode.PROBING
    private var reason = "initial_probe"
    private var pipelineGeneration = -1
    private var eglGeneration = 0L
    private var failureCount = 0L
    private var probeAttemptCount = 0L
    private var lastProbePipelineGeneration = Int.MIN_VALUE
    private var lastProbeEglGeneration = Long.MIN_VALUE

    @Synchronized
    fun onPipelineGeneration(generation: Int): Boolean {
        if (mode == RawPreviewGpuInteropMode.CLOSED || generation < 0) return false
        if (generation == pipelineGeneration) return false
        pipelineGeneration = generation
        return reopenTransientFallback("pipeline_generation_changed")
    }

    @Synchronized
    fun onEglGeneration(generation: Long): Boolean {
        if (mode == RawPreviewGpuInteropMode.CLOSED || generation <= 0L) return false
        if (generation == eglGeneration) return false
        eglGeneration = generation
        return reopenTransientFallback("egl_generation_changed")
    }

    /** Returns true when this frame may attempt the GPU path. */
    @Synchronized
    fun beginGpuAttempt(pipelineGeneration: Int, eglGeneration: Long): Boolean {
        if (!matchesCurrent(pipelineGeneration, eglGeneration)) return false
        return when (mode) {
            RawPreviewGpuInteropMode.GPU_ACTIVE -> true
            RawPreviewGpuInteropMode.PROBING -> {
                if (lastProbePipelineGeneration == pipelineGeneration &&
                    lastProbeEglGeneration == eglGeneration
                ) {
                    false
                } else {
                    lastProbePipelineGeneration = pipelineGeneration
                    lastProbeEglGeneration = eglGeneration
                    probeAttemptCount += 1L
                    true
                }
            }
            RawPreviewGpuInteropMode.CPU_FALLBACK_TRANSIENT,
            RawPreviewGpuInteropMode.CPU_FALLBACK_PERMANENT,
            RawPreviewGpuInteropMode.CLOSED -> false
        }
    }

    @Synchronized
    fun markGpuActive(pipelineGeneration: Int, eglGeneration: Long): Boolean {
        if (!matchesCurrent(pipelineGeneration, eglGeneration)) return false
        if (mode == RawPreviewGpuInteropMode.CLOSED ||
            mode == RawPreviewGpuInteropMode.CPU_FALLBACK_PERMANENT
        ) return false
        mode = RawPreviewGpuInteropMode.GPU_ACTIVE
        reason = "none"
        return true
    }

    @Synchronized
    fun markTransientFailure(
        reason: String,
        pipelineGeneration: Int,
        eglGeneration: Long
    ): Boolean {
        if (!matchesCurrent(pipelineGeneration, eglGeneration)) return false
        if (mode == RawPreviewGpuInteropMode.CLOSED ||
            mode == RawPreviewGpuInteropMode.CPU_FALLBACK_PERMANENT
        ) return false
        mode = RawPreviewGpuInteropMode.CPU_FALLBACK_TRANSIENT
        this.reason = reason.ifBlank { "transient_failure" }
        failureCount += 1L
        return true
    }

    @Synchronized
    fun markPermanentFailure(
        reason: String,
        pipelineGeneration: Int,
        eglGeneration: Long
    ): Boolean {
        if (!matchesCurrent(pipelineGeneration, eglGeneration)) return false
        if (mode == RawPreviewGpuInteropMode.CLOSED) return false
        mode = RawPreviewGpuInteropMode.CPU_FALLBACK_PERMANENT
        this.reason = reason.ifBlank { "permanent_failure" }
        failureCount += 1L
        return true
    }

    @Synchronized
    fun snapshot(): RawPreviewGpuInteropSnapshot = RawPreviewGpuInteropSnapshot(
        mode = mode,
        reason = reason,
        pipelineGeneration = pipelineGeneration,
        eglGeneration = eglGeneration,
        failureCount = failureCount,
        probeAttemptCount = probeAttemptCount
    )

    @Synchronized
    fun close() {
        mode = RawPreviewGpuInteropMode.CLOSED
        reason = "renderer_closed"
    }

    private fun matchesCurrent(pipelineGeneration: Int, eglGeneration: Long): Boolean =
        this.pipelineGeneration == pipelineGeneration && this.eglGeneration == eglGeneration

    /**
     * New generation boundaries only reopen transient fallback or an unfinished initial probe.
     * GPU_ACTIVE stays active; permanent capability failure remains intentionally sticky.
     */
    private fun reopenTransientFallback(boundaryReason: String): Boolean {
        return when (mode) {
            RawPreviewGpuInteropMode.CPU_FALLBACK_TRANSIENT,
            RawPreviewGpuInteropMode.PROBING -> {
                mode = RawPreviewGpuInteropMode.PROBING
                reason = boundaryReason
                // A different generation key is sufficient to permit exactly one new attempt.
                true
            }
            RawPreviewGpuInteropMode.GPU_ACTIVE,
            RawPreviewGpuInteropMode.CPU_FALLBACK_PERMANENT,
            RawPreviewGpuInteropMode.CLOSED -> false
        }
    }
}

/** Renderer-facing decision returned by [RawPreviewGpuInteropController]. */
internal enum class RawPreviewGpuUseDecision {
    GPU_ACTIVE,
    PROBE_ALLOWED,
    CPU_FALLBACK
}

internal data class RawPreviewGpuInteropControllerSnapshot(
    val mode: RawPreviewGpuInteropMode,
    val boundary: String,
    val reason: String,
    val probeAttempted: Boolean,
    val failureCount: Long
)

/**
 * Small renderer-facing controller layered on the Phase-2 interop policy.
 *
 * Delta 0200+ moved RawPreviewRenderer to this API, but the original 0198 package only shipped
 * [RawPreviewGpuInteropStateMachine]. Keep the same generation-scoped invariants here:
 * - one controlled probe per (pipelineGeneration, eglGeneration) boundary;
 * - a transient failure is sticky for that boundary;
 * - a new boundary may probe once again;
 * - a boundary-permanent failure stays on CPU until the boundary changes;
 * - stale completions cannot downgrade the current boundary.
 */
internal class RawPreviewGpuInteropController {
    private data class Boundary(
        val pipelineGeneration: Int,
        val eglGeneration: Int
    ) {
        override fun toString(): String = "$pipelineGeneration/$eglGeneration"
    }

    private var boundary = Boundary(Int.MIN_VALUE, Int.MIN_VALUE)
    private var mode = RawPreviewGpuInteropMode.PROBING
    private var reason = "initial_probe"
    private var probeAttempted = false
    private var failureCount = 0L
    private var capabilityBlocked = false
    private var boundaryPermanentFailure = false

    @Synchronized
    fun decision(
        pipelineGeneration: Int,
        eglGeneration: Int,
        capabilitiesReady: Boolean,
        capabilityReason: String
    ): RawPreviewGpuUseDecision {
        if (pipelineGeneration < 0 || eglGeneration < 0) {
            adoptBoundaryIfNeeded(pipelineGeneration, eglGeneration)
            capabilityBlocked = true
            mode = RawPreviewGpuInteropMode.CPU_FALLBACK_TRANSIENT
            reason = capabilityReason.ifBlank { "interop_boundary_unavailable" }
            return RawPreviewGpuUseDecision.CPU_FALLBACK
        }

        val boundaryChanged = adoptBoundaryIfNeeded(pipelineGeneration, eglGeneration)

        if (!capabilitiesReady) {
            capabilityBlocked = true
            mode = RawPreviewGpuInteropMode.CPU_FALLBACK_TRANSIENT
            reason = capabilityReason.ifBlank { "interop_capability_blocked" }
            return RawPreviewGpuUseDecision.CPU_FALLBACK
        }

        // A capability-only block may clear when a fresh capability snapshot becomes ready. This
        // is not a per-frame failure retry: no GPU probe was consumed while capabilities were absent.
        if (capabilityBlocked && !boundaryPermanentFailure) {
            capabilityBlocked = false
            if (!probeAttempted) {
                mode = RawPreviewGpuInteropMode.PROBING
                reason = if (boundaryChanged) "boundary_changed" else "capabilities_ready"
            }
        }

        return when (mode) {
            RawPreviewGpuInteropMode.GPU_ACTIVE -> RawPreviewGpuUseDecision.GPU_ACTIVE
            RawPreviewGpuInteropMode.PROBING ->
                if (!probeAttempted) RawPreviewGpuUseDecision.PROBE_ALLOWED
                else RawPreviewGpuUseDecision.CPU_FALLBACK
            RawPreviewGpuInteropMode.CPU_FALLBACK_TRANSIENT,
            RawPreviewGpuInteropMode.CPU_FALLBACK_PERMANENT,
            RawPreviewGpuInteropMode.CLOSED -> RawPreviewGpuUseDecision.CPU_FALLBACK
        }
    }

    @Synchronized
    fun beginProbe(pipelineGeneration: Int, eglGeneration: Int): Boolean {
        if (!matchesBoundary(pipelineGeneration, eglGeneration)) return false
        if (mode != RawPreviewGpuInteropMode.PROBING || probeAttempted || capabilityBlocked) return false
        probeAttempted = true
        reason = "probe_in_flight"
        return true
    }

    @Synchronized
    fun markGpuActive(pipelineGeneration: Int, eglGeneration: Int): Boolean {
        if (!matchesBoundary(pipelineGeneration, eglGeneration)) return false
        if (mode == RawPreviewGpuInteropMode.CLOSED || boundaryPermanentFailure) return false
        mode = RawPreviewGpuInteropMode.GPU_ACTIVE
        reason = "none"
        capabilityBlocked = false
        probeAttempted = true
        return true
    }

    @Synchronized
    fun markFailure(
        pipelineGeneration: Int,
        eglGeneration: Int,
        reason: String,
        permanentForBoundary: Boolean
    ): Boolean {
        if (!matchesBoundary(pipelineGeneration, eglGeneration)) return false
        if (mode == RawPreviewGpuInteropMode.CLOSED) return false

        val normalizedReason = reason.ifBlank { "gpu_interop_failure" }
        val nextMode = if (permanentForBoundary) {
            RawPreviewGpuInteropMode.CPU_FALLBACK_PERMANENT
        } else {
            RawPreviewGpuInteropMode.CPU_FALLBACK_TRANSIENT
        }
        val changed = mode != nextMode || this.reason != normalizedReason ||
            boundaryPermanentFailure != permanentForBoundary

        mode = nextMode
        this.reason = normalizedReason
        probeAttempted = true
        capabilityBlocked = false
        boundaryPermanentFailure = permanentForBoundary
        failureCount += 1L
        return changed
    }

    @Synchronized
    fun snapshot(): RawPreviewGpuInteropControllerSnapshot = RawPreviewGpuInteropControllerSnapshot(
        mode = mode,
        boundary = boundary.toString(),
        reason = reason,
        probeAttempted = probeAttempted,
        failureCount = failureCount
    )

    @Synchronized
    fun close() {
        mode = RawPreviewGpuInteropMode.CLOSED
        reason = "renderer_closed"
        probeAttempted = true
    }

    private fun matchesBoundary(pipelineGeneration: Int, eglGeneration: Int): Boolean =
        boundary.pipelineGeneration == pipelineGeneration && boundary.eglGeneration == eglGeneration

    /** Returns true when a new generation pair was installed. */
    private fun adoptBoundaryIfNeeded(pipelineGeneration: Int, eglGeneration: Int): Boolean {
        val next = Boundary(pipelineGeneration, eglGeneration)
        if (next == boundary) return false
        boundary = next
        mode = RawPreviewGpuInteropMode.PROBING
        reason = "boundary_changed"
        probeAttempted = false
        capabilityBlocked = false
        boundaryPermanentFailure = false
        return true
    }
}
