package com.bncam.ui.screens.capture

import android.hardware.HardwareBuffer
import android.os.SystemClock
import android.util.Log
import com.bncam.core.debug.RawPreviewFirstActivationTrace
import com.bncam.core.engine.ImageUtils
import com.bncam.core.quality.RawColorTransformEngine
import com.bncam.core.runtime.RawPreviewResolutionPolicy
import com.bncam.core.runtime.RawPreviewFastPathKind
import com.bncam.core.runtime.RawPreviewFastPathPolicy
import com.bncam.core.runtime.RawPreviewProducerKind
import com.bncam.core.runtime.RawPreviewProducerTimestampGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReference

data class RawPreviewRenderConfig(
    val source: ViewfinderEffectiveSource,
    val pipelineGeneration: Int,
    val profileId: String,
    val cfaPattern: Int,
    val demosaicMode: Int,
    val blackLevels: FloatArray,
    val whiteLevel: Int,
    val wbGains: FloatArray,
    val colorMatrix: FloatArray,
    val exposureGain: Float,
    val captureSensitivityIso: Int,
    val captureExposureTimeNs: Long,
    // Canonical green-channel physical S/O noise model for preview exposure authority.
    // Values are already in BnCam normalized RAW units: [greenS, greenO, confidence].
    val physicalGreenNoiseSo: FloatArray,
    val focusDetailPriority: Float,
    val profileToneExposure: Float,
    val profileToneHighlights: Float,
    val profileToneShadows: Float,
    val profileToneWhites: Float,
    val profileToneBlacks: Float,
    val profileToneContrast: Float,
    val profileLocalToneBias: Float,
    val profileSaturation: Float,
    val profileContrast: Float,
    val profileVibrance: Float,
    val profilePop: Float,
    val profileColorRecovery: Float,
    val profileDetailAmount: Float,
    val profileDetailRadius: Float,
    val profileDetailDetail: Float,
    val profileDetailMasking: Float,
    val profileNrLuminance: Float,
    val profileNrLuminanceDetail: Float,
    val profileNrLuminanceContrast: Float,
    val profileNrColor: Float,
    val profileNrColorDetail: Float,
    val profileNrColorSmoothness: Float,
    val toneCurve: FloatArray,
    val gammaCurve: FloatArray,
    val sectionCurve: FloatArray,
    val rotationDegrees: Int,
    val isBootstrap: Boolean = false
)

class RawPreviewFrame internal constructor(
    val rgba: ByteBuffer,
    val hardwareBuffer: HardwareBuffer?,
    val gpuResidentOutputUsed: Boolean,
    val interopEglGeneration: Int,
    val analysisNv21: ByteBuffer?,
    val analysisNv21Width: Int,
    val analysisNv21Height: Int,
    val width: Int,
    val height: Int,
    val source: ViewfinderEffectiveSource,
    val pipelineGeneration: Int,
    val sensorTimestampNs: Long,
    val producerKind: RawPreviewProducerKind,
    val captureSensitivityIso: Int,
    val captureExposureTimeNs: Long,
    val physicalGreenNoiseSo: FloatArray,
    val analysisReadbackUsed: Boolean,
    val rotationDegrees: Int,
    val cfaCellDecimation: Int,
    val renderTimeMs: Float,
    val vulkanStagesUsed: Boolean,
    val directHostInputUsed: Boolean,
    val directHardwareBufferInputUsed: Boolean,
    val inputAhbFormat: Int,
    val inputAhbUsage: Long,
    val inputInteropStatus: Int,
    val normalizedRawMin: Float,
    val normalizedRawMax: Float,
    val outputRgbMin: Float,
    val outputRgbMax: Float,
    val outputRgbMean: Float,
    val outputAlpha: Int,
    val rawUnpackTimeMs: Float,
    val demosaicTimeMs: Float,
    val colorTimeMs: Float,
    val tonePackTimeMs: Float,
    val targetExposureGain: Float,
    val appliedExposureGain: Float,
    val previewRequestedEv: Float,
    val previewHighlightLimitedEv: Float,
    val previewAppliedEv: Float,
    val previewSceneKey: Float,
    val previewHighlightHeadroomEv: Float,
    val previewSceneRangeEv: Float,
    val sceneMidtone: Float,
    val sceneMidtoneTarget: Float,
    val gtmShoulderStart: Float,
    val gtmShoulderStrength: Float,
    val gtmHighlightPressure: Float,
    val gtmP95CompressionEv: Float,
    val gtmP99CompressionEv: Float,
    val gtmDynamicRangePressure: Float,
    val ltmStrength: Float,
    val ltmMaxLiftEv: Float,
    val ltmMaxCompressEv: Float,
    val commonHighlightScalePixels: Int,
    val linearLumaHistogram256: IntArray,
    val displayLumaHistogram16: IntArray,
    val displayLumaHistogram64: IntArray,
    val displayRHistogram64: IntArray,
    val displayGHistogram64: IntArray,
    val displayBHistogram64: IntArray,
    val rawNearClipSampleCount: Int,
    val rawSampleCount: Int,
    val rawTrueSaturatedSampleCount: Int,
    val rawRClipSampleCount: Int,
    val rawGClipSampleCount: Int,
    val rawBClipSampleCount: Int,
    val highlightReconstructionActivated: Boolean,
    val highlightReconstructedSampleCount: Int,
    val postWbClipSampleCount: Int,
    val postCcmClipSampleCount: Int,
    val displayRClipSampleCount: Int,
    val displayGClipSampleCount: Int,
    val displayBClipSampleCount: Int,
    val displayShadowSampleCount: Int,
    val displayHighlightSampleCount: Int,
    val displaySampleCount: Int,
    val displayHighlightX: Float,
    val displayHighlightY: Float,
    val renderWbGains: FloatArray,
    val renderColorMatrix: FloatArray,
    val camera2PriorWbGains: FloatArray,
    val camera2PriorColorMatrix: FloatArray,
    val physicalAwbPriorRgb: FloatArray,
    val physicalAwbDataRgb: FloatArray,
    val physicalAwbFinalRgb: FloatArray,
    val physicalAwbConfidence: Float,
    val physicalAwbDataAuthority: Float,
    val physicalAwbNeutralSupport: Float,
    val physicalAwbMixedLightScore: Float,
    val physicalAwbPriorDisagreement: Float,
    val physicalAwbValidTileCount: Int,
    val physicalAwbAcceptedSampleCount: Int,
    val physicalAwbDataReady: Boolean,
    val spatialExposureTileCount: Int,
    val spatialExposureSceneP10: Float,
    val spatialExposureSceneP25: Float,
    val spatialExposureSceneP50: Float,
    val spatialExposureSceneP75: Float,
    val spatialExposureSceneP90: Float,
    val spatialExposureSceneP95: Float,
    val spatialExposureSceneP99: Float,
    val spatialExposureMeasuredDrEv: Float,
    val spatialExposureLowerNeutralEv: Float,
    val spatialExposureUpperNeutralEv: Float,
    val spatialExposureAuthority: Float,
    private val createGlFence: () -> Long,
    private val releaseSlot: (Long) -> Unit
) : Closeable {
    private val closed = AtomicBoolean(false)

    /** Frame was never submitted to GL, so the Vulkan-complete slot is immediately reusable. */
    override fun close() {
        if (closed.compareAndSet(false, true)) releaseSlot(GL_NOT_USED_HANDLE)
    }

    /** Called only after GLES sampled a GPU-resident HardwareBuffer in a submitted draw. */
    fun closeAfterGlSampled() {
        if (closed.compareAndSet(false, true)) releaseSlot(createGlFence())
    }

    fun closeAfterGlInteropFailure() {
        if (closed.compareAndSet(false, true)) releaseSlot(GL_INTEROP_FAILED_HANDLE)
    }

    internal companion object {
        const val GL_NOT_USED_HANDLE = -1L
        const val GL_INTEROP_FAILED_HANDLE = -2L
    }
}

/**
 * Bounded-jitter RAW viewfinder renderer. The input handle is an independently retained native
 * AHardwareBuffer reference; neither this class nor native code owns the Image/ring-buffer handle.
 * Two pending frames absorb Camera2 delivery jitter while overflow still drops the oldest queued
 * request, so backlog can never grow without bound or silently trade cadence for latency.
 */
class RawPreviewRenderer(
    private val fenceBackend: RawPreviewFenceBackend = PlatformRawPreviewFenceBackend,
    private val onFrame: (RawPreviewFrame) -> Unit
) : Closeable {
    private data class Request(
        val retainedHardwareBuffer: Long,
        val sensorTimestampNs: Long,
        val config: RawPreviewRenderConfig,
        val configRevision: Long,
        val useRuntimeCrop: Boolean,
        val producerKind: RawPreviewProducerKind,
        val sourceWidth: Int,
        val sourceHeight: Int
    )

    private data class CompletionContext(
        val request: Request,
        val slot: OutputSlot,
        val outputHardwareBuffer: HardwareBuffer?,
        val outputRgba: ByteBuffer,
        val analysisBuffer: ByteBuffer?,
        val interopEglGeneration: Int,
        val effectiveWbGains: FloatArray,
        val effectiveColorMatrix: FloatArray,
        val camera2PriorWbGains: FloatArray,
        val camera2PriorColorMatrix: FloatArray,
        val hasExactFrameColorPair: Boolean
    )

    private data class PendingVulkanFrame(
        val context: CompletionContext,
        val submissionId: Long,
        val previewWidth: Int,
        val previewHeight: Int,
        val cfaCellDecimation: Int
    )

    private val executor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "BnCamRawPreview").apply { priority = Thread.NORM_PRIORITY }
    }.apply {
        removeOnCancelPolicy = true
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
    }
    // Cold Adreno pipeline compilation must not occupy the one-and-only frame drain worker.
    private val backendWarmupExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "BnCamRawPreviewWarmup").apply { priority = Thread.NORM_PRIORITY }
    }
    private val pendingRequestLock = Any()
    private val pendingRequests = ArrayDeque<Request>(MAX_PENDING_REQUESTS)
    private val pendingVulkanFrames = ConcurrentHashMap<Int, PendingVulkanFrame>()
    private val drainScheduled = AtomicBoolean(false)
    private var lastAnalysisSubmitElapsedNs = 0L
    private var vulkanSubmittedFrames = 0L
    private var vulkanCompletedFrames = 0L
    private var analysisReadbackFrames = 0L
    private val configRevision = AtomicLong(0L)
    private val backendPrepareScheduled = AtomicBoolean(false)
    private val backendPrepared = AtomicBoolean(false)
    private data class ExactFrameColorPair(
        val wbGains: FloatArray,
        val colorMatrix: FloatArray
    )

    private val liveWhiteBalanceOverride = AtomicReference<FloatArray?>(null)
    private val liveWhiteBalanceColorPair = AtomicReference<ExactFrameColorPair?>(null)
    // System-AWB RAW preview must consume WB + CCM from the same sensor timestamp. A WB-only
    // convergence override can otherwise pair one frame's gains with another frame's matrix.
    private val exactFrameColorPairs = ConcurrentHashMap<Long, ExactFrameColorPair>()
    private val autoWhiteBalanceColorPair = AtomicReference<ExactFrameColorPair?>(null)
    // PRIMARY_BUFFER and RAW_PREVIEW_SUPPORT can legitimately carry the same SENSOR_TIMESTAMP.
    // Keep monotonic duplicate suppression per producer so one stream can never poison the other
    // stream's fallback path with a newer timestamp.
    private val producerTimestampGate = RawPreviewProducerTimestampGate()
    private val lastRendererOfferElapsedNs = AtomicLong(0L)
    private val lastRendererPublishElapsedNs = AtomicLong(0L)
    private class OutputSlot(val id: Int) {
        private var cpuRgba: ByteBuffer? = null
        private var analysisNv21Buffer: ByteBuffer? = null
        var hardwareBuffer: HardwareBuffer? = null
        var hardwareBufferWidth: Int = 0
        var hardwareBufferHeight: Int = 0
        var hardwareBufferUsage: Long = 0L
        var pendingGlFenceHandle: Long = 0L
        var pendingSource: ViewfinderEffectiveSource? = null
        var pendingGeneration: Int = -1
        var pendingSensorTimestampNs: Long = 0L
        var pendingProducerKind: RawPreviewProducerKind = RawPreviewProducerKind.CANONICAL_RING
        var pendingEglGeneration: Int = -1
        private val quarantinedGpuBuffers = ArrayList<HardwareBuffer>(MAX_QUARANTINED_GPU_BACKINGS_PER_SLOT)
        val quarantined: Boolean get() = quarantinedGpuBuffers.isNotEmpty()
        val quarantinedGpuBackingCount: Int get() = quarantinedGpuBuffers.size
        val quarantineBudgetExhausted: Boolean
            get() = quarantinedGpuBuffers.size >= MAX_QUARANTINED_GPU_BACKINGS_PER_SLOT

        fun ensureCpuRgbaBuffer(): ByteBuffer {
            return cpuRgba ?: ByteBuffer.allocateDirect(MAX_OUTPUT_BYTES)
                .order(ByteOrder.nativeOrder())
                .also { cpuRgba = it }
        }

        fun ensureAnalysisNv21Buffer(): ByteBuffer {
            return analysisNv21Buffer ?: ByteBuffer.allocateDirect(MAX_ANALYSIS_NV21_BYTES)
                .order(ByteOrder.nativeOrder())
                .also { analysisNv21Buffer = it }
        }

        fun clearCpuRgbaBuffer() {
            cpuRgba?.clear()
        }

        fun releaseCpuBufferReferences() {
            cpuRgba = null
            analysisNv21Buffer = null
        }

        fun ensureGpuBuffer(width: Int, height: Int, usage: Long): HardwareBuffer? {
            if (usage == 0L) return null
            val current = hardwareBuffer
            if (current != null && hardwareBufferWidth == width && hardwareBufferHeight == height &&
                hardwareBufferUsage == usage
            ) {
                return current
            }
            releaseGpuBuffer()
            if (quarantineBudgetExhausted) return null
            val supported = runCatching {
                HardwareBuffer.isSupported(width, height, HardwareBuffer.RGBA_8888, 1, usage)
            }.getOrDefault(false)
            if (!supported) return null
            val allocated = runCatching {
                HardwareBuffer.create(width, height, HardwareBuffer.RGBA_8888, 1, usage)
            }.getOrNull() ?: return null
            hardwareBuffer = allocated
            hardwareBufferWidth = width
            hardwareBufferHeight = height
            hardwareBufferUsage = usage
            return allocated
        }

        fun quarantineCurrentGpuBuffer(): Boolean {
            val buffer = hardwareBuffer ?: return false
            if (quarantineBudgetExhausted) return false
            quarantinedGpuBuffers.add(buffer)
            hardwareBuffer = null
            hardwareBufferWidth = 0
            hardwareBufferHeight = 0
            hardwareBufferUsage = 0L
            return true
        }

        fun releaseGpuBuffer() {
            hardwareBuffer?.let { buffer ->
                ImageUtils.releaseRawPreviewEglImage(buffer)
                runCatching { buffer.close() }
            }
            hardwareBuffer = null
            hardwareBufferWidth = 0
            hardwareBufferHeight = 0
            hardwareBufferUsage = 0L
        }

        fun releaseAllGpuBuffers() {
            releaseGpuBuffer()
            quarantinedGpuBuffers.forEach { buffer ->
                ImageUtils.releaseRawPreviewEglImage(buffer)
                runCatching { buffer.close() }
            }
            quarantinedGpuBuffers.clear()
        }
    }

    private val allOutputSlots = List(OUTPUT_SLOT_COUNT) { OutputSlot(it) }
    private val availableOutputSlots = ConcurrentLinkedQueue<OutputSlot>()
    private val pendingGpuOutputSlots = ConcurrentLinkedQueue<OutputSlot>()
    private val outputSlotLedger = RawPreviewOutputSlotLedger(OUTPUT_SLOT_COUNT)
    private val dropCounters = AtomicLongArray(RawPreviewDropReason.values().size)
    @Volatile private var activeConfig: RawPreviewRenderConfig? = null
    @Volatile private var closed = false
    private var droppedBusy = 0L
    private var rendered = 0L
    private var retainFailures = 0L
    private var renderFailures = 0L
    private var lastDiagnosticsMs = 0L
    private var received = 0L
    private var lastReceivedDiagnosticsMs = 0L
    private var lastDiagnosticsRendered = 0L
    private var lastConfigDiagnosticsMs = 0L
    private var renderCostEmaMs = 0.0f
    @Volatile private var glUploadCostEmaMs = 0.0f
    private val gpuInteropController = RawPreviewGpuInteropController()
    @Volatile private var mlAnalysisRequested = false
    private var gpuOutputFailures = 0L
    private var directAhbGpuFastFrames = 0L
    private var hostInputGpuResidentFrames = 0L
    private var gpuResidentCompatibilityFrames = 0L
    private var cpuVisibleFallbackFrames = 0L
    private var compactAnalysisFrames = 0L
    @Volatile private var lastFastPathKind: RawPreviewFastPathKind? = null

    init {
        allOutputSlots.forEach(availableOutputSlots::offer)
        outputSlotLedger.assertInternalInvariant()
    }

    private fun recordDrop(reason: RawPreviewDropReason, request: Request? = null) {
        dropCounters.incrementAndGet(reason.ordinal)
        val source = request?.config?.source ?: activeConfig?.source
        val generation = request?.config?.pipelineGeneration ?: activeConfig?.pipelineGeneration
        val timestampNs = request?.sensorTimestampNs ?: 0L
        val producerKind = request?.producerKind ?: RawPreviewProducerKind.CANONICAL_RING
        if (source != null && generation != null) {
            RawPreviewCadenceDiagnostics.dropped(
                source, generation, timestampNs, producerKind, reason
            )
        }
    }

    private fun acquireOutputSlot(): OutputSlot? {
        while (true) {
            val slot = availableOutputSlots.poll() ?: return null
            if (outputSlotLedger.tryAcquire(slot.id)) return slot
            Log.e(
                TAG,
                "RAW_PREVIEW_SLOT_QUEUE_STATE_MISMATCH action=acquire slot=${slot.id} " +
                    "ledger=${outputSlotLedger.state(slot.id)} ${outputSlotHealth()}"
            )
        }
    }

    private fun returnOutputSlot(slot: OutputSlot, reason: String) {
        if (closed) return
        if (outputSlotLedger.releaseToAvailable(slot.id)) {
            availableOutputSlots.offer(slot)
        } else {
            Log.e(
                TAG,
                "RAW_PREVIEW_SLOT_QUEUE_STATE_MISMATCH action=return slot=${slot.id} reason=$reason " +
                    "ledger=${outputSlotLedger.state(slot.id)} ${outputSlotHealth()}"
            )
        }
    }

    private fun markOutputSlotPendingFence(slot: OutputSlot): Boolean {
        val ok = outputSlotLedger.markPendingFence(slot.id)
        if (!ok) {
            Log.e(
                TAG,
                "RAW_PREVIEW_SLOT_QUEUE_STATE_MISMATCH action=pending_fence slot=${slot.id} " +
                    "ledger=${outputSlotLedger.state(slot.id)} ${outputSlotHealth()}"
            )
        }
        return ok
    }

    private fun currentInteropCapabilityReason(snapshot: RawPreviewInteropCapabilitySnapshot?): String {
        if (snapshot == null) return "interop_capabilities_unavailable"
        return if (snapshot.blockers.isEmpty()) "capabilities_ready"
        else "capability_blocked:${snapshot.blockers.joinToString("+")}"
    }

    private fun outputSlotHealth(): String {
        val snapshot = outputSlotLedger.snapshot()
        val quarantinedSlots = allOutputSlots.count { it.quarantined }
        val quarantinedBackings = allOutputSlots.sumOf { it.quarantinedGpuBackingCount }
        val gpuState = gpuInteropController.snapshot()
        val cpuFallbackAvailable = allOutputSlots.count { slot ->
            outputSlotLedger.state(slot.id) == RawPreviewOutputSlotState.AVAILABLE &&
                gpuState.mode != RawPreviewGpuInteropMode.GPU_ACTIVE
        }
        return "source=${activeConfig?.source?.name ?: "YUV"} " +
            "generation=${activeConfig?.pipelineGeneration ?: -1} " +
            "available=${snapshot.available} inFlight=${snapshot.inFlight} " +
            "pendingFence=${snapshot.pendingFence} closed=${snapshot.closed} " +
            "cpuFallbackAvailable=$cpuFallbackAvailable " +
            "availableQueue=${availableOutputSlots.size} pendingFenceQueue=${pendingGpuOutputSlots.size} " +
            "pendingVulkan=${pendingVulkanFrames.size} submittedVulkan=$vulkanSubmittedFrames " +
            "completedVulkan=$vulkanCompletedFrames " +
            "gpuQuarantinedSlots=$quarantinedSlots gpuQuarantinedBackings=$quarantinedBackings " +
            "gpuMode=${gpuState.mode} gpuBoundary=${gpuState.boundary} " +
            "gpuFallbackReason=${gpuState.reason} gpuProbeAttempted=${gpuState.probeAttempted} " +
            "gpuOutputFailures=$gpuOutputFailures " +
            "lastOfferElapsedNs=${lastRendererOfferElapsedNs.get()} " +
            "lastPublishElapsedNs=${lastRendererPublishElapsedNs.get()}"
    }

    fun healthSummary(): String = outputSlotHealth()

    /**
     * Read-only compact runtime evidence for per-shot diagnostics. This does not probe, recover,
     * reconfigure or otherwise influence preview authority.
     */
    fun runtimeDiagnosticsSummary(): String =
        "fastPath=${lastFastPathKind ?: "UNPROVEN"};" +
            "directAhbFrames=$directAhbGpuFastFrames;" +
            "hostInputFrames=$hostInputGpuResidentFrames;" +
            "gpuCompatFrames=$gpuResidentCompatibilityFrames;" +
            "cpuFallbackFrames=$cpuVisibleFallbackFrames;" +
            "compactNv21Requested=$mlAnalysisRequested;compactNv21Frames=$compactAnalysisFrames;" +
            "slotHealth={${outputSlotHealth()}};" +
            "dropReasons={${dropCountsSummary()}};" +
            "frameLifecycle={${RawPreviewFrameLifecycleRegistry.latest()?.summary() ?: "unavailable"}};" +
            "activeLifecycleFrames=${RawPreviewFrameLifecycleRegistry.activeCount()};" +
            "retainFailures=$retainFailures;renderFailures=$renderFailures;" +
            "generation=${activeConfig?.pipelineGeneration ?: -1}"

    /**
     * Stage-aware watchdog recovery. This only retires GPU presentation authority for the active
     * camera/EGL boundary; it never restarts Camera2 and never recycles an AHB whose GL completion
     * is unknown. Available CPU-capable slots continue servicing newest-frame-wins requests.
     */
    fun forceCpuFallbackForHealth(reason: String): Boolean {
        val config = activeConfig ?: return false
        if (config.source == ViewfinderEffectiveSource.YUV || config.pipelineGeneration < 0) return false
        val eglGeneration = RawPreviewInteropCapabilities.latest?.eglGeneration ?: -1
        val capabilitySnapshot = RawPreviewInteropCapabilities.latest
        val capabilityReady = capabilitySnapshot?.readyForAhbEglImageInterop == true
        gpuInteropController.decision(
            pipelineGeneration = config.pipelineGeneration,
            eglGeneration = eglGeneration,
            capabilitiesReady = capabilityReady,
            capabilityReason = currentInteropCapabilityReason(capabilitySnapshot)
        )
        val changed = gpuInteropController.markFailure(
            pipelineGeneration = config.pipelineGeneration,
            eglGeneration = eglGeneration,
            reason = "health_watchdog:$reason",
            permanentForBoundary = false
        )
        if (changed) {
            RawPreviewInteropCapabilities.recordActivePath("CPU_VISIBLE_RGBA_TO_GLES_HEALTH_RECOVERY")
            RawPreviewHealthMonitor.updateOutputSlotHealth(config.pipelineGeneration, outputSlotHealth())
            if (hasPendingRequest()) scheduleDrain()
            Log.w(TAG, "RAW_PREVIEW_HEALTH_CPU_FALLBACK reason=$reason ${outputSlotHealth()}")
        }
        return changed
    }

    private fun logOutputSlotInvariantIfBroken(reason: String) {
        val snapshot = outputSlotLedger.snapshot()
        outputSlotLedger.assertInternalInvariant()
        val queueMismatch = availableOutputSlots.size != snapshot.available ||
            pendingGpuOutputSlots.size != snapshot.pendingFence
        val impossibleStarvation = snapshot.available == 0 &&
            snapshot.pendingFence == 0 &&
            snapshot.inFlight == 0 &&
            snapshot.closed == 0
        if (queueMismatch || impossibleStarvation) {
            Log.e(
                TAG,
                "RAW_PREVIEW_SLOT_STARVATION reason=$reason queueMismatch=$queueMismatch " +
                    "impossibleStarvation=$impossibleStarvation ${outputSlotHealth()}"
            )
        }
    }

    private fun dropCountsSummary(): String = RawPreviewDropReason.values().joinToString(separator = ",") { reason ->
        "${reason.name}=${dropCounters.get(reason.ordinal)}"
    }

    private fun enqueuePendingRequest(request: Request) {
        var stale: Request? = null
        synchronized(pendingRequestLock) {
            if (pendingRequests.size >= MAX_PENDING_REQUESTS) {
                stale = pendingRequests.removeFirst()
            }
            pendingRequests.addLast(request)
        }
        stale?.let { dropped ->
            droppedBusy++
            recordDrop(RawPreviewDropReason.INPUT_QUEUE_OVERFLOW, dropped)
            // Camera2 metadata is shared evidence for every producer carrying this sensor timestamp.
            // Do not consume/delete it when only one producer request is dropped. The bounded
            // timestamp cache owns eviction so canonical fallback and support can both resolve the
            // exact WB+CCM pair.
            releaseRequest(dropped)
            RawPreviewFrameLifecycleRegistry.released(
                dropped.config.pipelineGeneration,
                dropped.sensorTimestampNs,
                dropped.producerKind,
                SystemClock.elapsedRealtimeNanos(),
                "input_queue_overflow"
            )
        }
    }

    private fun pollPendingRequest(): Request? = synchronized(pendingRequestLock) {
        pendingRequests.pollFirst()
    }

    private fun hasPendingRequest(): Boolean = synchronized(pendingRequestLock) {
        pendingRequests.isNotEmpty()
    }

    private fun pendingRequestCount(): Int = synchronized(pendingRequestLock) {
        pendingRequests.size
    }

    private fun clearPendingRequests() {
        val stale = ArrayList<Request>(MAX_PENDING_REQUESTS)
        synchronized(pendingRequestLock) {
            while (pendingRequests.isNotEmpty()) stale.add(pendingRequests.removeFirst())
        }
        stale.forEach { request ->
            // Keep exact Camera2 color evidence available to a sibling producer with the same
            // SENSOR_TIMESTAMP; bounded cache eviction owns lifetime.
            releaseRequest(request)
            RawPreviewFrameLifecycleRegistry.released(
                request.config.pipelineGeneration,
                request.sensorTimestampNs,
                request.producerKind,
                SystemClock.elapsedRealtimeNanos(),
                "pending_queue_cleared"
            )
        }
    }

    private fun RawPreviewRenderConfig.isSafeForNativePreview(): Boolean {
        if (whiteLevel <= 1) return false
        if (blackLevels.size < 4 || blackLevels.take(4).any { !it.isFinite() }) return false
        if (wbGains.size < 4 || wbGains.take(4).any { !it.isFinite() || it !in 0.25f..6.0f }) return false
        if (colorMatrix.size != 9 || !RawColorTransformEngine.validateSensorToLinearSrgbMatrix(colorMatrix).valid) return false
        if (!exposureGain.isFinite() || exposureGain !in 0.05f..32f) return false
        return true
    }

    val currentConfig: RawPreviewRenderConfig?
        get() = activeConfig

    fun setMlAnalysisRequested(requested: Boolean) {
        mlAnalysisRequested = requested
    }

    /**
     * Prepare the process-resident RAW-preview Vulkan backend before Selected buffer is requested.
     * Pipeline compilation runs on its own low-priority worker so it can never strand the renderer.
     */
    fun prepareBackendAsync(
        reason: String,
        onCompleted: ((Boolean) -> Unit)? = null
    ) {
        if (closed || backendPrepared.get()) {
            if (!closed && backendPrepared.get()) onCompleted?.invoke(true)
            return
        }
        if (!backendPrepareScheduled.compareAndSet(false, true)) return
        val accepted = runCatching {
            backendWarmupExecutor.execute {
                RawPreviewFirstActivationTrace.backendPrepareStarted(reason)
                val startedNs = SystemClock.elapsedRealtimeNanos()
                var prepared = false
                try {
                    prepared = com.bncam.core.vulkan.VulkanRuntimeOwner.prepareRawPreviewBackend()
                    if (prepared) backendPrepared.set(true)
                } finally {
                    backendPrepareScheduled.set(false)
                    RawPreviewFirstActivationTrace.backendPrepareCompleted(reason, prepared)
                    val elapsedMs = (SystemClock.elapsedRealtimeNanos() - startedNs) / 1_000_000.0
                    Log.i(
                        TAG,
                        "RAW_PREVIEW_BACKEND_PREPARE reason=$reason prepared=$prepared elapsedMs=${"%.3f".format(elapsedMs)}"
                    )
                    if (!closed) onCompleted?.invoke(prepared)
                }
            }
        }.isSuccess
        if (!accepted) backendPrepareScheduled.set(false)
    }

    /**
     * Lightweight live colour update. The renderer samples this atomic target for every new RAW
     * frame, so WB slider/AWB convergence does not require rebuilding the preview calibration or
     * touching Camera2 session state.
     */
    fun updateWhiteBalanceGains(gains: FloatArray?) {
        // Gain-only is retained only as a compatibility/clear boundary. Live manual WB uses
        // updateWhiteBalanceColorPair so the sensor WB diagonal and its post-WB CCM change atomically.
        liveWhiteBalanceColorPair.set(null)
        if (gains == null) {
            liveWhiteBalanceOverride.set(null)
            return
        }
        if (gains.size < 4 || gains.take(4).any { !it.isFinite() || it !in 0.25f..6.0f }) {
            Log.w(TAG, "RAW_PREVIEW_LIVE_WB_REJECTED gains=${gains.joinToString(prefix = "[", postfix = "]")}")
            return
        }
        liveWhiteBalanceOverride.set(gains.copyOf(4))
    }

    fun updateWhiteBalanceColorPair(gains: FloatArray?, colorMatrix: FloatArray?) {
        if (gains == null || colorMatrix == null) {
            liveWhiteBalanceColorPair.set(null)
            liveWhiteBalanceOverride.set(null)
            return
        }
        if (gains.size < 4 || gains.take(4).any { !it.isFinite() || it !in 0.25f..6.0f } ||
            colorMatrix.size < 9 ||
            !RawColorTransformEngine.validateSensorToLinearSrgbMatrix(colorMatrix.copyOf(9)).valid
        ) {
            Log.w(TAG, "RAW_PREVIEW_LIVE_COLOR_PAIR_REJECTED")
            return
        }
        val pair = ExactFrameColorPair(gains.copyOf(4), colorMatrix.copyOf(9))
        liveWhiteBalanceOverride.set(pair.wbGains.copyOf())
        liveWhiteBalanceColorPair.set(pair)
    }

    fun updateExactFrameCamera2ColorPair(
        sensorTimestampNs: Long,
        gains: FloatArray,
        colorMatrix: FloatArray
    ) {
        if (sensorTimestampNs <= 0L || gains.size < 4 || colorMatrix.size < 9 ||
            gains.take(4).any { !it.isFinite() || it !in 0.25f..6.0f } ||
            !RawColorTransformEngine.validateSensorToLinearSrgbMatrix(colorMatrix.copyOf(9)).valid
        ) {
            return
        }
        exactFrameColorPairs[sensorTimestampNs] = ExactFrameColorPair(
            wbGains = gains.copyOf(4),
            colorMatrix = colorMatrix.copyOf(9)
        )
        if (exactFrameColorPairs.size > 24) {
            val oldest = exactFrameColorPairs.keys.minOrNull()
            if (oldest != null && oldest != sensorTimestampNs) exactFrameColorPairs.remove(oldest)
        }
    }

    fun clearExactFrameCamera2ColorPairs() {
        exactFrameColorPairs.clear()
    }

    /** System-Auto temporal render pair. Exact Camera2 timestamp pairs remain stored separately
     * so the physical estimator keeps an independent metadata prior. */
    fun updateAutoWhiteBalanceColorPair(gains: FloatArray?, colorMatrix: FloatArray?) {
        if (gains == null || colorMatrix == null) {
            autoWhiteBalanceColorPair.set(null)
            return
        }
        if (gains.size < 4 || colorMatrix.size < 9 ||
            gains.take(4).any { !it.isFinite() || it !in 0.25f..6.0f } ||
            !RawColorTransformEngine.validateSensorToLinearSrgbMatrix(colorMatrix.copyOf(9)).valid
        ) return
        autoWhiteBalanceColorPair.set(
            ExactFrameColorPair(gains.copyOf(4), colorMatrix.copyOf(9))
        )
    }

    fun clearAutoWhiteBalanceColorPair() {
        autoWhiteBalanceColorPair.set(null)
    }

    fun configure(config: RawPreviewRenderConfig?) {
        if (config != null && !config.isSafeForNativePreview()) {
            Log.e(
                TAG,
                "RAW_PREVIEW_CONFIG_REJECTED profile=${config.profileId} generation=${config.pipelineGeneration} " +
                    "source=${config.source} white=${config.whiteLevel} " +
                    "wb=${config.wbGains.joinToString(prefix = "[", postfix = "]")} " +
                    "matrixMax=${config.colorMatrix.maxOfOrNull { kotlin.math.abs(it) } ?: Float.NaN}; " +
                    "retainingPreviousSafeConfig=${activeConfig != null}"
            )
            return
        }
        val previous = activeConfig
        val routeChanged = config == null || previous == null ||
            previous.pipelineGeneration != config.pipelineGeneration ||
            previous.source != config.source || previous.profileId != config.profileId
        activeConfig = config
        if (routeChanged) {
            liveWhiteBalanceOverride.set(null)
            exactFrameColorPairs.clear()
            autoWhiteBalanceColorPair.set(null)
            configRevision.incrementAndGet()
            producerTimestampGate.reset()
            clearPendingRequests()
            if (config == null || config.source == ViewfinderEffectiveSource.YUV) {
                allOutputSlots.forEach(OutputSlot::releaseCpuBufferReferences)
            }
            renderCostEmaMs = 0.0f
            glUploadCostEmaMs = 0.0f
            directAhbGpuFastFrames = 0L
            hostInputGpuResidentFrames = 0L
            gpuResidentCompatibilityFrames = 0L
            cpuVisibleFallbackFrames = 0L
            compactAnalysisFrames = 0L
            lastFastPathKind = null
        }
        val now = SystemClock.elapsedRealtime()
        if (routeChanged || now - lastConfigDiagnosticsMs >= CONFIG_DIAGNOSTIC_INTERVAL_MS) {
            lastConfigDiagnosticsMs = now
            Log.i(
                TAG,
                "RAW_PREVIEW_GENERATION=${config?.pipelineGeneration ?: -1} " +
                    "source=${config?.source?.name ?: "YUV"} profile=${config?.profileId ?: "none"} " +
                    "cfa=${config?.cfaPattern ?: -1} white=${config?.whiteLevel ?: -1} " +
                    "black=${config?.blackLevels?.joinToString(prefix = "[", postfix = "]") ?: "[]"} " +
                    "wb=${config?.wbGains?.joinToString(prefix = "[", postfix = "]") ?: "[]"} " +
                    "iso=${config?.captureSensitivityIso ?: -1} exposureNs=${config?.captureExposureTimeNs ?: -1L} " +
                    "focusDetailPriority=${config?.focusDetailPriority ?: 0f} " +
                    "rotation=${config?.rotationDegrees ?: -1} routeChanged=$routeChanged"
            )
        }
    }

    /** Must finish native retain before the scoped FrameRingBuffer borrow returns. */
    fun offerBorrowedHardwareBuffer(
        buffer: HardwareBuffer,
        sensorTimestampNs: Long,
        pipelineGeneration: Int,
        useRuntimeCrop: Boolean = true,
        producerKind: RawPreviewProducerKind = RawPreviewProducerKind.CANONICAL_RING
    ) {
        if (closed) return
        val config = activeConfig ?: return
        if (config.pipelineGeneration != pipelineGeneration || config.source == ViewfinderEffectiveSource.YUV) {
            return
        }
        if (!producerTimestampGate.accept(producerKind, sensorTimestampNs)) {
            RawPreviewCadenceDiagnostics.duplicateOfferRejected(
                config.source,
                pipelineGeneration,
                sensorTimestampNs,
                producerKind
            )
            return
        }
        RawPreviewCadenceDiagnostics.offered(
            config.source, pipelineGeneration, sensorTimestampNs, producerKind
        )
        lastRendererOfferElapsedNs.set(SystemClock.elapsedRealtimeNanos())
        val retained = ImageUtils.retainRawPreviewHardwareBuffer(buffer)
        if (retained == 0L) {
            retainFailures++
            dropCounters.incrementAndGet(RawPreviewDropReason.RETAIN_FAILED.ordinal)
            RawPreviewCadenceDiagnostics.dropped(
                config.source, pipelineGeneration, sensorTimestampNs, producerKind,
                RawPreviewDropReason.RETAIN_FAILED
            )
            logDiagnostics()
            return
        }
        RawPreviewFrameLifecycleRegistry.acquired(
            source = config.source.name,
            generation = pipelineGeneration,
            timestampNs = sensorTimestampNs,
            producerKind = producerKind,
            nowNs = SystemClock.elapsedRealtimeNanos()
        )
        received++
        RawPreviewFirstActivationTrace.rendererOffer(
            source = config.source.name,
            generation = pipelineGeneration,
            sensorTimestampNs = sensorTimestampNs
        )
        val receivedNow = SystemClock.elapsedRealtime()
        if (received == 1L || receivedNow - lastReceivedDiagnosticsMs >= DIAGNOSTIC_INTERVAL_MS) {
            lastReceivedDiagnosticsMs = receivedNow
            Log.i(
                TAG,
                "RAW_PREVIEW_RAW_RECEIVED source=${config.source} " +
                    "generation=$pipelineGeneration timestampNs=$sensorTimestampNs received=$received"
            )
        }
        val request = Request(
            retainedHardwareBuffer = retained,
            sensorTimestampNs = sensorTimestampNs,
            config = config,
            configRevision = configRevision.get(),
            useRuntimeCrop = useRuntimeCrop,
            producerKind = producerKind,
            sourceWidth = buffer.width,
            sourceHeight = buffer.height
        )
        enqueuePendingRequest(request)
        scheduleDrain()
    }

    private fun scheduleDrain(delayMs: Long = 0L) {
        if (closed || !drainScheduled.compareAndSet(false, true)) return
        executor.schedule({
            drainScheduled.set(false)
            drainLatest()
        }, delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
    }

    private fun drainLatest() {
        if (closed) return
        pollVulkanCompletions()
        val request = pollPendingRequest()
        if (request == null) {
            if (pendingVulkanFrames.isNotEmpty()) scheduleDrain(COMPLETION_POLL_INTERVAL_MS)
            return
        }
        reclaimCompletedGpuOutputSlots()
        val slot = acquireOutputSlot()
        if (slot == null) {
            droppedBusy++
            recordDrop(RawPreviewDropReason.NO_OUTPUT_SLOT, request)
            logOutputSlotInvariantIfBroken("no_output_slot")
            releaseRequest(request)
            RawPreviewFrameLifecycleRegistry.released(
                request.config.pipelineGeneration,
                request.sensorTimestampNs,
                request.producerKind,
                SystemClock.elapsedRealtimeNanos(),
                "no_output_slot"
            )
            logDiagnostics()
            if (hasPendingRequest()) scheduleDrain()
            else if (pendingVulkanFrames.isNotEmpty()) scheduleDrain(COMPLETION_POLL_INTERVAL_MS)
            return
        }

        var delivered = false
        var ownershipTransferredToGpu = false
        try {
            if (!isCurrent(request)) {
                recordDrop(RawPreviewDropReason.STALE_GENERATION, request)
                return
            }
            RawPreviewCadenceDiagnostics.processingStarted(
                request.config.source,
                request.config.pipelineGeneration,
                request.sensorTimestampNs,
                request.producerKind
            )
            val runtimeProfile = com.bncam.core.runtime.RawPipelineRuntimeOwner.getProfile()
            val activeGeometry = runtimeProfile?.geometry
            val runtimeGeometryMatchesRequest =
                request.useRuntimeCrop &&
                    runtimeProfile != null &&
                    activeGeometry != null &&
                    runtimeProfile.sessionGeneration == request.config.pipelineGeneration &&
                    runtimeProfile.format == request.config.source.imageFormat &&
                    activeGeometry.bufferWidth == request.sourceWidth &&
                    activeGeometry.bufferHeight == request.sourceHeight
            val requestGeometry = if (runtimeGeometryMatchesRequest) activeGeometry else null
            if (request.useRuntimeCrop && activeGeometry != null && requestGeometry == null) {
                Log.w(
                    TAG,
                    "RAW_PREVIEW_RUNTIME_GEOMETRY_SKIPPED source=${request.config.source} " +
                        "requestGeneration=${request.config.pipelineGeneration} " +
                        "runtimeGeneration=${runtimeProfile?.sessionGeneration} " +
                        "requestSize=${request.sourceWidth}x${request.sourceHeight} " +
                        "runtimeSize=${activeGeometry.bufferWidth}x${activeGeometry.bufferHeight} " +
                        "runtimeFormat=${runtimeProfile?.format}"
                )
            }
            // RAW viewfinder resolution is a product quality contract, not a load-shedding knob.
            // Keep the established highest quality tier fixed. If rendering cannot keep cadence,
            // the existing newest-frame-wins queue drops stale work instead of changing geometry.
            val targetMaxWidth = PREVIEW_MAX_WIDTH
            val targetMaxHeight = PREVIEW_MAX_HEIGHT
            val phaseX = requestGeometry?.cfaPhaseX?.and(1) ?: 0
            val phaseY = requestGeometry?.cfaPhaseY?.and(1) ?: 0
            val phaseMask = phaseX or (phaseY shl 1)
            // Camera2's CFA enum is anchored to sensor coordinates. Once the RAW preview starts
            // at an active-array crop origin, shift both the CFA identity and per-site black levels
            // to the local preview mosaic origin instead of assuming buffer (0,0) is sensor (0,0).
            val localCfaPattern = request.config.cfaPattern xor phaseMask
            val localBlackLevels = if (phaseMask == 0 || request.config.blackLevels.size < 4) {
                request.config.blackLevels
            } else {
                FloatArray(4) { localSite ->
                    val localX = localSite and 1
                    val localY = (localSite shr 1) and 1
                    val sourceSite = ((localY xor phaseY) shl 1) or (localX xor phaseX)
                    request.config.blackLevels[sourceSite]
                }
            }

            val expectedOutput = computePreviewOutputDimensions(
                request = request,
                geometry = requestGeometry
            )
            val interopSnapshot = RawPreviewInteropCapabilities.latest
            val interopEglGeneration = interopSnapshot?.eglGeneration ?: -1
            val gpuDecision = gpuInteropController.decision(
                pipelineGeneration = request.config.pipelineGeneration,
                eglGeneration = interopEglGeneration,
                capabilitiesReady = interopSnapshot?.readyForAhbEglImageInterop == true,
                capabilityReason = currentInteropCapabilityReason(interopSnapshot)
            )
            val usage = interopSnapshot?.vulkanOutputAhbUsage ?: 0L
            var outputHardwareBuffer: HardwareBuffer? = null
            if (gpuDecision != RawPreviewGpuUseDecision.CPU_FALLBACK) {
                outputHardwareBuffer = slot.ensureGpuBuffer(expectedOutput.first, expectedOutput.second, usage)
                if (outputHardwareBuffer != null && gpuDecision == RawPreviewGpuUseDecision.PROBE_ALLOWED) {
                    if (!gpuInteropController.beginProbe(request.config.pipelineGeneration, interopEglGeneration)) {
                        outputHardwareBuffer = null
                    }
                } else if (outputHardwareBuffer == null) {
                    val everySlotOutOfSafeBackingBudget = allOutputSlots.all { it.quarantineBudgetExhausted }
                    if (usage == 0L || everySlotOutOfSafeBackingBudget) {
                        gpuOutputFailures++
                        gpuInteropController.markFailure(
                            request.config.pipelineGeneration,
                            interopEglGeneration,
                            if (everySlotOutOfSafeBackingBudget) "gpu_backing_quarantine_budget_exhausted"
                            else "ahb_allocation_unsupported",
                            permanentForBoundary = true
                        )
                        RawPreviewInteropCapabilities.recordActivePath(
                            if (everySlotOutOfSafeBackingBudget)
                                "CPU_VISIBLE_RGBA_TO_GLES_GPU_BACKING_QUARANTINE_BUDGET"
                            else "CPU_VISIBLE_RGBA_TO_GLES_AHB_ALLOCATION_UNSUPPORTED"
                        )
                    }
                }
            }

            val analysisNowNs = SystemClock.elapsedRealtimeNanos()
            val analysisReadbackRequested =
                lastAnalysisSubmitElapsedNs == 0L ||
                    analysisNowNs - lastAnalysisSubmitElapsedNs >= ANALYSIS_SIDECAR_INTERVAL_NS
            val analysisBuffer = if (mlAnalysisRequested && analysisReadbackRequested) {
                slot.ensureAnalysisNv21Buffer().apply { clear() }
            } else {
                null
            }
            // Phase 11A: each in-flight Vulkan slot owns its fallback RGBA storage. A shared
            // scratch buffer is unsafe once submissions overlap, even if the normal path is AHB.
            val outputRgba = slot.ensureCpuRgbaBuffer().apply { clear() }

            val liveColorPair = liveWhiteBalanceColorPair.get()
            val liveWb = liveColorPair?.wbGains?.copyOf() ?: liveWhiteBalanceOverride.get()?.copyOf()
            // Exact Camera2 metadata is consumed for every System-Auto frame even when a temporal
            // render pair is active. This prevents the filtered output from feeding back as its own
            // PhysicalAwbEstimator prior. Live manual WB intentionally clears these pairs.
            val exactFramePair = if (liveWb == null) {
                // PRIMARY_BUFFER and RAW_PREVIEW_SUPPORT may carry the same exact sensor frame.
                // This pair is immutable metadata evidence, not a single-consumer token.
                exactFrameColorPairs[request.sensorTimestampNs]
            } else {
                null
            }
            val autoPair = if (liveWb == null) autoWhiteBalanceColorPair.get() else null
            val effectiveWbGains = liveWb ?: autoPair?.wbGains ?: exactFramePair?.wbGains ?: request.config.wbGains
            val effectiveColorMatrix = liveColorPair?.colorMatrix ?: autoPair?.colorMatrix ?: exactFramePair?.colorMatrix ?: request.config.colorMatrix
            val camera2PriorWbGains = exactFramePair?.wbGains ?: request.config.wbGains
            val camera2PriorColorMatrix = exactFramePair?.colorMatrix ?: request.config.colorMatrix
            RawPreviewFirstActivationTrace.nativeRenderStarted(
                source = request.config.source.name,
                generation = request.config.pipelineGeneration,
                sensorTimestampNs = request.sensorTimestampNs
            )
            val renderTrace = RawPreviewTrace.beginRender()
            val result = try {
                ImageUtils.renderRawPreview(
                retainedHardwareBuffer = request.retainedHardwareBuffer,
                sourceFormat = request.config.source.imageFormat,
                cfaPattern = localCfaPattern,
                requestedDemosaicMode = request.config.demosaicMode,
                blackLevels = localBlackLevels,
                whiteLevel = request.config.whiteLevel,
                wbGains = effectiveWbGains,
                camera2PriorWbGains = camera2PriorWbGains,
                colorMatrix = effectiveColorMatrix,
                exposureGain = request.config.exposureGain,
                captureSensitivityIso = request.config.captureSensitivityIso,
                captureExposureTimeNs = request.config.captureExposureTimeNs,
                physicalGreenNoiseSo = request.config.physicalGreenNoiseSo,
                focusDetailPriority = request.config.focusDetailPriority,
                profileToneExposure = request.config.profileToneExposure,
                profileToneHighlights = request.config.profileToneHighlights,
                profileToneShadows = request.config.profileToneShadows,
                profileToneWhites = request.config.profileToneWhites,
                profileToneBlacks = request.config.profileToneBlacks,
                profileToneContrast = request.config.profileToneContrast,
                profileLocalToneBias = request.config.profileLocalToneBias,
                profileSaturation = request.config.profileSaturation,
                profileContrast = request.config.profileContrast,
                profileVibrance = request.config.profileVibrance,
                profilePop = request.config.profilePop,
                profileColorRecovery = request.config.profileColorRecovery,
                profileDetailAmount = request.config.profileDetailAmount,
                profileDetailRadius = request.config.profileDetailRadius,
                profileDetailDetail = request.config.profileDetailDetail,
                profileDetailMasking = request.config.profileDetailMasking,
                profileNrLuminance = request.config.profileNrLuminance,
                profileNrLuminanceDetail = request.config.profileNrLuminanceDetail,
                profileNrLuminanceContrast = request.config.profileNrLuminanceContrast,
                profileNrColor = request.config.profileNrColor,
                profileNrColorDetail = request.config.profileNrColorDetail,
                profileNrColorSmoothness = request.config.profileNrColorSmoothness,
                toneCurve = request.config.toneCurve,
                gammaCurve = request.config.gammaCurve,
                sectionCurve = request.config.sectionCurve,
                rotationDegrees = request.config.rotationDegrees,
                sourceWidth = requestGeometry?.bufferWidth ?: request.sourceWidth,
                sourceHeight = requestGeometry?.bufferHeight ?: request.sourceHeight,
                sourceRowStrideBytes = requestGeometry?.rowStride?.takeIf { it > 0 }
                    ?: if (request.config.source == ViewfinderEffectiveSource.RAW10) (request.sourceWidth * 5 / 4) else (request.sourceWidth * 2),
                sourcePixelStrideBytes = requestGeometry?.pixelStride ?: 0,
                sourceCropLeft = requestGeometry?.cropLeft ?: 0,
                sourceCropTop = requestGeometry?.cropTop ?: 0,
                sourceCropWidth = requestGeometry?.cropWidth ?: 0,
                sourceCropHeight = requestGeometry?.cropHeight ?: 0,
                outputHardwareBuffer = outputHardwareBuffer,
                outputRgba = outputRgba,
                analysisNv21 = analysisBuffer,
                frameSlotIndex = P0_NATIVE_VULKAN_SLOT_ID,
                    maxWidth = targetMaxWidth,
                    maxHeight = targetMaxHeight,
                    analysisReadbackRequested = analysisReadbackRequested
                )
            } finally {
                RawPreviewTrace.end(renderTrace)
            }
            val completionContext = CompletionContext(
                request = request,
                slot = slot,
                outputHardwareBuffer = outputHardwareBuffer,
                outputRgba = outputRgba,
                analysisBuffer = analysisBuffer,
                interopEglGeneration = interopEglGeneration,
                effectiveWbGains = effectiveWbGains.copyOf(),
                effectiveColorMatrix = effectiveColorMatrix.copyOf(),
                camera2PriorWbGains = camera2PriorWbGains.copyOf(),
                camera2PriorColorMatrix = camera2PriorColorMatrix.copyOf(),
                hasExactFrameColorPair = exactFramePair != null
            )
            if (result != null && result.getOrNull(0) == RAW_PREVIEW_ASYNC_SUBMITTED_MAGIC) {
                val submissionId = decodeAsyncSubmissionId(result)
                if (submissionId <= 0L || result.getOrElse(1) { -1 } != P0_NATIVE_VULKAN_SLOT_ID) {
                    renderFailures++
                    recordDrop(RawPreviewDropReason.NATIVE_RENDER_FAILED, request)
                    return
                }

                // P0 stability recovery:
                // Phase 11A detached Vulkan completion from this already-dedicated preview worker
                // and allowed several Camera/HardwareBuffer/Vulkan/EGL ownership transactions to
                // overlap. The regression appears on real hardware as an immediate frozen/black
                // RAW viewfinder and can coincide with a Single RAW capture process crash.
                //
                // Keep the new Vulkan shaders and GPU-resident output, but temporarily make the
                // worker own one complete submit -> fence -> publish transaction. The UI thread is
                // never blocked: BnCamRawPreview is a private worker. This also provides a clean
                // bisect boundary before re-introducing multiple GPU submissions in flight.
                vulkanSubmittedFrames++
                if (analysisReadbackRequested) lastAnalysisSubmitElapsedNs = analysisNowNs
                val completed = awaitSubmittedVulkanCompletionOnWorker(
                    slotId = P0_NATIVE_VULKAN_SLOT_ID,
                    submissionId = submissionId,
                    previewWidth = result.getOrElse(4) { expectedOutput.first },
                    previewHeight = result.getOrElse(5) { expectedOutput.second },
                    cfaCellDecimation = result.getOrElse(6) { 1 }.coerceAtLeast(1),
                    camera2PriorWbGains = completionContext.camera2PriorWbGains
                )
                if (completed == null) {
                    renderFailures++
                    recordDrop(RawPreviewDropReason.NATIVE_RENDER_FAILED, request)
                    Log.e(
                        TAG,
                        "RAW_PREVIEW_P0_COMPLETION_FAILED slot=${slot.id} submissionId=$submissionId " +
                            "generation=${request.config.pipelineGeneration} timestamp=${request.sensorTimestampNs}"
                    )
                    return
                }
                vulkanCompletedFrames++
                delivered = publishCompletedFrame(completionContext, completed)
                return
            }
            delivered = publishCompletedFrame(completionContext, result)
            return
        } finally {
            if (!ownershipTransferredToGpu) {
                releaseRequest(request)
                // A delivered frame owns the slot until FocusPeakingView uploads it. Undelivered
                // paths return it here. Async submissions transfer both request and slot ownership
                // to pendingVulkanFrames until the non-blocking fence probe completes.
                if (!delivered) {
                    slot.clearCpuRgbaBuffer()
                    RawPreviewFrameLifecycleRegistry.released(
                        request.config.pipelineGeneration,
                        request.sensorTimestampNs,
                        request.producerKind,
                        SystemClock.elapsedRealtimeNanos(),
                        "renderer_undelivered"
                    )
                    returnOutputSlot(slot, "undelivered")
                }
            }
            if (hasPendingRequest()) scheduleDrain()
            else if (pendingVulkanFrames.isNotEmpty()) scheduleDrain(COMPLETION_POLL_INTERVAL_MS)
        }
    }

    private fun publishCompletedFrame(
        ctx: CompletionContext,
        result: IntArray?
    ): Boolean {
        val request = ctx.request
        val slot = ctx.slot
        val outputHardwareBuffer = ctx.outputHardwareBuffer
        val outputRgba = ctx.outputRgba
        val analysisBuffer = ctx.analysisBuffer
        val interopEglGeneration = ctx.interopEglGeneration
        val effectiveWbGains = ctx.effectiveWbGains
        val effectiveColorMatrix = ctx.effectiveColorMatrix
        val camera2PriorWbGains = ctx.camera2PriorWbGains
        val camera2PriorColorMatrix = ctx.camera2PriorColorMatrix
        val hasExactFrameColorPair = ctx.hasExactFrameColorPair
        if (result?.getOrElse(ASYNC_ANALYSIS_READBACK_INDEX) { 0 } == 1) analysisReadbackFrames++
            if (result == null || result.size < 5 || result[0] <= 0 || result[1] <= 0 || !isCurrent(request)) {
                val current = isCurrent(request)
                Log.w(
                    TAG,
                    "RENDER_FAILED resultIsNull=${result == null} resultSize=${result?.size} " +
                    "w=${result?.getOrNull(0)} h=${result?.getOrNull(1)} isCurrent=$current " +
                    "activeConfig=${activeConfig?.pipelineGeneration}/${activeConfig?.source} " +
                    "reqGen=${request.config.pipelineGeneration}/${request.config.source}"
                )
                renderFailures++
                recordDrop(
                    if (current) RawPreviewDropReason.NATIVE_RENDER_FAILED
                    else RawPreviewDropReason.STALE_GENERATION,
                    request
                )
                logDiagnostics()
                return false
            }
            if (result.getOrElse(20) { 0 } != 0) {
                RawPreviewFrameLifecycleRegistry.gpuImported(
                    request.config.pipelineGeneration,
                    request.sensorTimestampNs,
                    request.producerKind,
                    SystemClock.elapsedRealtimeNanos()
                )
            }
            RawPreviewFirstActivationTrace.nativeRenderCompleted(
                source = request.config.source.name,
                generation = request.config.pipelineGeneration,
                sensorTimestampNs = request.sensorTimestampNs,
                breakdown = RawPreviewFirstActivationTrace.NativeBreakdown(
                    backendMutexWaitUs = result.getOrElse(576) { 0 },
                    backendInitializationPerformed = result.getOrElse(577) { 0 } != 0,
                    backendInitializationUs = result.getOrElse(578) { 0 },
                    spirvLookupUs = result.getOrElse(579) { 0 },
                    descriptorLayoutUs = result.getOrElse(580) { 0 },
                    pipelineLayoutUs = result.getOrElse(581) { 0 },
                    shaderModuleUs = result.getOrElse(582) { 0 },
                    pipelineCacheMutexWaitUs = result.getOrElse(583) { 0 },
                    pipelineCachePresent = result.getOrElse(584) { 0 } != 0,
                    computePipelineUs = result.getOrElse(585) { 0 },
                    imageSpirvLookupUs = result.getOrElse(586) { 0 },
                    imageShaderModuleUs = result.getOrElse(587) { 0 },
                    imageComputePipelineUs = result.getOrElse(588) { 0 },
                    descriptorCommandResourcesUs = result.getOrElse(589) { 0 },
                    inputAhbProbeUs = result.getOrElse(590) { 0 },
                    outputAhbImportUs = result.getOrElse(591) { 0 },
                    commandRecordUs = result.getOrElse(592) { 0 },
                    queueMutexWaitUs = result.getOrElse(593) { 0 },
                    queueSubmitCallUs = result.getOrElse(594) { 0 },
                    fenceWaitUs = result.getOrElse(595) { 0 },
                    nativeRenderTotalUs = result[2]
                )
            )
            val gpuResidentOutputUsed = result.getOrElse(21) { 0 } != 0
            if (outputHardwareBuffer != null && !gpuResidentOutputUsed) {
                gpuOutputFailures++
                gpuInteropController.markFailure(
                    request.config.pipelineGeneration,
                    interopEglGeneration,
                    "native_output_import_failed",
                    permanentForBoundary = false
                )
                // Phase 11A keeps a CPU RGBA buffer per in-flight output slot. When AHB import is
                // unavailable this exact completed frame can therefore fall back to CPU-visible
                // presentation without being discarded or racing the next Vulkan submission.
                RawPreviewInteropCapabilities.recordActivePath("CPU_VISIBLE_RGBA_TO_GLES_OUTPUT_IMPORT_FAILED")
                Log.w(
                    TAG,
                    "RAW_PREVIEW_GPU_OUTPUT_FALLBACK reason=native_output_import_failed " +
                        "transitionFrameDropped=false failures=$gpuOutputFailures ${outputSlotHealth()}"
                )
            } else if (gpuResidentOutputUsed) {
                gpuInteropController.markGpuActive(request.config.pipelineGeneration, interopEglGeneration)
                RawPreviewInteropCapabilities.recordActivePath("VULKAN_AHB_RGBA_TO_EGLIMAGE_GLES")
            }
            val directHostInputUsed = result.getOrElse(19) { 0 } != 0
            val directHardwareBufferInputUsed = result.getOrElse(20) { 0 } != 0
            val fastPathKind = RawPreviewFastPathPolicy.classify(
                gpuResidentOutputUsed = gpuResidentOutputUsed,
                directHardwareBufferInputUsed = directHardwareBufferInputUsed,
                directHostInputUsed = directHostInputUsed
            )
            lastFastPathKind = fastPathKind
            when (fastPathKind) {
                RawPreviewFastPathKind.DIRECT_AHB_GPU_RESIDENT -> directAhbGpuFastFrames++
                RawPreviewFastPathKind.HOST_INPUT_GPU_RESIDENT -> hostInputGpuResidentFrames++
                RawPreviewFastPathKind.GPU_RESIDENT_COMPATIBILITY -> gpuResidentCompatibilityFrames++
                RawPreviewFastPathKind.CPU_VISIBLE_RGBA_FALLBACK -> cpuVisibleFallbackFrames++
            }
            if (analysisBuffer != null) compactAnalysisFrames++

            val currentRenderTimeMs = result[2] / 1000.0f
            recordRenderCost(currentRenderTimeMs)
            val inputPackingMs = result.getOrElse(11) { 0 } / 1000.0f
            val gpuKernelMs = result.getOrElse(12) { 0 } / 1000.0f
            val gpuSyncOverheadMs = result.getOrElse(13) { 0 } / 1000.0f
            val gpuHostReadbackMs = result.getOrElse(14) { 0 } / 1000.0f
            RawPreviewCadenceDiagnostics.processingCompleted(
                request.config.source,
                request.config.pipelineGeneration,
                request.sensorTimestampNs,
                request.producerKind,
                inputPackingMs = inputPackingMs,
                gpuKernelMs = gpuKernelMs,
                gpuSyncOverheadMs = gpuSyncOverheadMs,
                gpuHostReadbackMs = gpuHostReadbackMs
            )
            val byteCount = result[0] * result[1] * RGBA_BYTES_PER_PIXEL
            outputRgba.position(0)
            outputRgba.limit(byteCount.coerceAtMost(outputRgba.capacity()))
            val analysisWidth = result.getOrElse(43) { 0 }.coerceAtLeast(0)
            val analysisHeight = result.getOrElse(44) { 0 }.coerceAtLeast(0)
            val analysisByteCount = if (analysisBuffer != null) {
                (analysisWidth.toLong() * analysisHeight.toLong() * 3L / 2L)
                    .coerceAtMost(analysisBuffer.capacity().toLong()).toInt()
            } else {
                0
            }
            analysisBuffer?.position(0)
            analysisBuffer?.limit(analysisByteCount)
            rendered++
            val frame = RawPreviewFrame(
                rgba = outputRgba,
                hardwareBuffer = if (gpuResidentOutputUsed) slot.hardwareBuffer else null,
                gpuResidentOutputUsed = gpuResidentOutputUsed,
                interopEglGeneration = interopEglGeneration,
                analysisNv21 = if (analysisByteCount > 0) analysisBuffer else null,
                analysisNv21Width = analysisWidth,
                analysisNv21Height = analysisHeight,
                width = result[0],
                height = result[1],
                source = request.config.source,
                pipelineGeneration = request.config.pipelineGeneration,
                sensorTimestampNs = request.sensorTimestampNs,
                producerKind = request.producerKind,
                captureSensitivityIso = request.config.captureSensitivityIso,
                captureExposureTimeNs = request.config.captureExposureTimeNs,
                physicalGreenNoiseSo = request.config.physicalGreenNoiseSo.copyOf(3),
                analysisReadbackUsed = result.getOrElse(ASYNC_ANALYSIS_READBACK_INDEX) { 0 } == 1,
                rotationDegrees = request.config.rotationDegrees,
                cfaCellDecimation = result[4],
                renderTimeMs = currentRenderTimeMs,
                vulkanStagesUsed = result[3] != 0,
                directHostInputUsed = directHostInputUsed,
                directHardwareBufferInputUsed = directHardwareBufferInputUsed,
                inputAhbFormat = result.getOrElse(45) { 0 },
                inputAhbUsage = (result.getOrElse(46) { 0 }.toLong() and 0xffffffffL) or
                    ((result.getOrElse(47) { 0 }.toLong() and 0xffffffffL) shl 32),
                inputInteropStatus = result.getOrElse(48) { 0 },
                normalizedRawMin = result.getOrElse(5) { 0 } / 1_000_000.0f,
                normalizedRawMax = result.getOrElse(6) { 0 } / 1_000_000.0f,
                outputRgbMin = result.getOrElse(7) { 0 } / 1_000_000.0f,
                outputRgbMax = result.getOrElse(8) { 0 } / 1_000_000.0f,
                outputRgbMean = result.getOrElse(9) { 0 } / 1_000_000.0f,
                outputAlpha = result.getOrElse(10) { 255 },
                rawUnpackTimeMs = inputPackingMs,
                demosaicTimeMs = gpuKernelMs,
                colorTimeMs = gpuSyncOverheadMs,
                tonePackTimeMs = gpuHostReadbackMs,
                targetExposureGain = result.getOrElse(15) { 1_000_000 } / 1_000_000.0f,
                appliedExposureGain = result.getOrElse(16) { 1_000_000 } / 1_000_000.0f,
                sceneMidtone = result.getOrElse(17) { 0 } / 1_000_000.0f,
                sceneMidtoneTarget = result.getOrElse(566) { 125_000 } / 1_000_000.0f,
                gtmShoulderStart = result.getOrElse(567) { 720_000 } / 1_000_000.0f,
                gtmShoulderStrength = result.getOrElse(568) { 900_000 } / 1_000_000.0f,
                gtmHighlightPressure = result.getOrElse(569) { 0 } / 1_000_000.0f,
                gtmP95CompressionEv = result.getOrElse(570) { 0 } / 1_000_000.0f,
                gtmP99CompressionEv = result.getOrElse(571) { 0 } / 1_000_000.0f,
                gtmDynamicRangePressure = result.getOrElse(572) { 0 } / 1_000_000.0f,
                ltmStrength = result.getOrElse(573) { 50_000 } / 1_000_000.0f,
                ltmMaxLiftEv = result.getOrElse(574) { 180_000 } / 1_000_000.0f,
                ltmMaxCompressEv = result.getOrElse(575) { 80_000 } / 1_000_000.0f,
                commonHighlightScalePixels = result.getOrElse(18) { 0 },
                linearLumaHistogram256 = IntArray(256) { index -> result.getOrElse(49 + index) { 0 } },
                displayLumaHistogram16 = IntArray(16) { index -> result.getOrElse(22 + index) { 0 } },
                displayLumaHistogram64 = IntArray(64) { index -> result.getOrElse(305 + index) { 0 } },
                displayRHistogram64 = IntArray(64) { index -> result.getOrElse(369 + index) { 0 } },
                displayGHistogram64 = IntArray(64) { index -> result.getOrElse(433 + index) { 0 } },
                displayBHistogram64 = IntArray(64) { index -> result.getOrElse(497 + index) { 0 } },
                rawNearClipSampleCount = result.getOrElse(561) { 0 },
                rawSampleCount = result.getOrElse(562) { 0 },
                rawTrueSaturatedSampleCount = result.getOrElse(SENSOR_EXPOSURE_DIAGNOSTICS_START_INDEX) { 0 },
                rawRClipSampleCount = result.getOrElse(HIGHLIGHT_RECON_DIAGNOSTICS_START_INDEX + 0) { 0 },
                rawGClipSampleCount = result.getOrElse(HIGHLIGHT_RECON_DIAGNOSTICS_START_INDEX + 1) { 0 },
                rawBClipSampleCount = result.getOrElse(HIGHLIGHT_RECON_DIAGNOSTICS_START_INDEX + 2) { 0 },
                highlightReconstructionActivated =
                    result.getOrElse(HIGHLIGHT_RECON_DIAGNOSTICS_START_INDEX + 3) { 0 } > 0,
                highlightReconstructedSampleCount =
                    result.getOrElse(HIGHLIGHT_RECON_DIAGNOSTICS_START_INDEX + 3) { 0 },
                postWbClipSampleCount = result.getOrElse(HIGHLIGHT_RECON_DIAGNOSTICS_START_INDEX + 4) { 0 },
                postCcmClipSampleCount = result.getOrElse(HIGHLIGHT_RECON_DIAGNOSTICS_START_INDEX + 5) { 0 },
                previewRequestedEv = result.getOrElse(PREVIEW_SCENE_EXPOSURE_DIAGNOSTICS_START_INDEX + 0) { 0 } / 1_000_000.0f,
                previewHighlightLimitedEv = result.getOrElse(PREVIEW_SCENE_EXPOSURE_DIAGNOSTICS_START_INDEX + 1) { 0 } / 1_000_000.0f,
                previewAppliedEv = result.getOrElse(PREVIEW_SCENE_EXPOSURE_DIAGNOSTICS_START_INDEX + 2) { 0 } / 1_000_000.0f,
                previewSceneKey = result.getOrElse(PREVIEW_SCENE_EXPOSURE_DIAGNOSTICS_START_INDEX + 3) { 148_000 } / 1_000_000.0f,
                previewHighlightHeadroomEv = result.getOrElse(PREVIEW_SCENE_EXPOSURE_DIAGNOSTICS_START_INDEX + 4) { 0 } / 1_000_000.0f,
                previewSceneRangeEv = result.getOrElse(PREVIEW_SCENE_EXPOSURE_DIAGNOSTICS_START_INDEX + 5) { 0 } / 1_000_000.0f,
                displayRClipSampleCount = result.getOrElse(563) { 0 },
                displayGClipSampleCount = result.getOrElse(564) { 0 },
                displayBClipSampleCount = result.getOrElse(565) { 0 },
                displayShadowSampleCount = result.getOrElse(38) { 0 },
                displayHighlightSampleCount = result.getOrElse(39) { 0 },
                displaySampleCount = result.getOrElse(40) { 0 },
                displayHighlightX = result.getOrElse(41) { -1 } / 1_000_000.0f,
                displayHighlightY = result.getOrElse(42) { -1 } / 1_000_000.0f,
                renderWbGains = effectiveWbGains.copyOf(4),
                renderColorMatrix = effectiveColorMatrix.copyOf(9),
                camera2PriorWbGains = camera2PriorWbGains.copyOf(4),
                camera2PriorColorMatrix = camera2PriorColorMatrix.copyOf(9),
                physicalAwbPriorRgb = FloatArray(3) { index ->
                    result.getOrElse(596 + index) { 1_000_000 } / 1_000_000.0f
                },
                physicalAwbDataRgb = FloatArray(3) { index ->
                    result.getOrElse(599 + index) { 1_000_000 } / 1_000_000.0f
                },
                physicalAwbFinalRgb = FloatArray(3) { index ->
                    result.getOrElse(602 + index) { 1_000_000 } / 1_000_000.0f
                },
                physicalAwbConfidence = result.getOrElse(605) { 0 } / 1_000_000.0f,
                physicalAwbDataAuthority = result.getOrElse(606) { 0 } / 1_000_000.0f,
                physicalAwbNeutralSupport = result.getOrElse(607) { 0 } / 1_000_000.0f,
                physicalAwbMixedLightScore = result.getOrElse(608) { 0 } / 1_000_000.0f,
                physicalAwbPriorDisagreement = result.getOrElse(609) { 0 } / 1_000_000.0f,
                physicalAwbValidTileCount = result.getOrElse(610) { 0 },
                physicalAwbAcceptedSampleCount = result.getOrElse(611) { 0 },
                // Physical scene evidence may update the temporal owner only when the RAW frame
                // carried an exact Camera2 WB+CCM prior from the same sensor timestamp. Missing
                // metadata never turns the current temporal render pair into its own estimator prior.
                physicalAwbDataReady = result.getOrElse(612) { 0 } != 0 && hasExactFrameColorPair,
                spatialExposureTileCount = result.getOrElse(613) { 0 },
                spatialExposureSceneP10 = result.getOrElse(614) { 0 } / 1_000_000.0f,
                spatialExposureSceneP25 = result.getOrElse(615) { 0 } / 1_000_000.0f,
                spatialExposureSceneP50 = result.getOrElse(616) { 0 } / 1_000_000.0f,
                spatialExposureSceneP75 = result.getOrElse(617) { 0 } / 1_000_000.0f,
                spatialExposureSceneP90 = result.getOrElse(618) { 0 } / 1_000_000.0f,
                spatialExposureSceneP95 = result.getOrElse(619) { 0 } / 1_000_000.0f,
                spatialExposureSceneP99 = result.getOrElse(620) { 0 } / 1_000_000.0f,
                spatialExposureMeasuredDrEv = result.getOrElse(621) { 0 } / 1_000_000.0f,
                spatialExposureLowerNeutralEv = result.getOrElse(622) { 0 } / 1_000_000.0f,
                spatialExposureUpperNeutralEv = result.getOrElse(623) { 0 } / 1_000_000.0f,
                spatialExposureAuthority = result.getOrElse(624) { 0 } / 1_000_000.0f,
                createGlFence = fenceBackend::createFence,
                releaseSlot = { glFenceHandle ->
                    releaseOutputSlot(
                        slot = slot,
                        glFenceHandle = glFenceHandle,
                        source = request.config.source,
                        generation = request.config.pipelineGeneration,
                        sensorTimestampNs = request.sensorTimestampNs,
                        producerKind = request.producerKind,
                        eglGeneration = interopEglGeneration
                    )
                }
            )
            val publicationNs = SystemClock.elapsedRealtimeNanos()
            lastRendererPublishElapsedNs.set(publicationNs)
            RawPreviewCadenceDiagnostics.rendererPublished(
                source = frame.source,
                generation = frame.pipelineGeneration,
                sensorTimestampNs = frame.sensorTimestampNs,
                producerKind = frame.producerKind,
                gpuResidentOutputUsed = frame.gpuResidentOutputUsed,
                interopEglGeneration = frame.interopEglGeneration,
                rgbMin = frame.outputRgbMin,
                rgbMax = frame.outputRgbMax,
                rgbMean = frame.outputRgbMean,
                slotHealth = outputSlotHealth(),
                publicationElapsedNs = publicationNs,
                normalizedRawMax = frame.normalizedRawMax,
                sceneP50 = frame.spatialExposureSceneP50
            )
            onFrame(frame)
            logDiagnostics(frame)
            return true
    }

    private fun decodeAsyncSubmissionId(result: IntArray): Long {
        val low = result.getOrElse(2) { 0 }.toLong() and 0xffffffffL
        val high = result.getOrElse(3) { 0 }.toLong() and 0xffffffffL
        return low or (high shl 32)
    }

    /**
     * P0 device-stability bridge. Vulkan completion is awaited only on the dedicated RAW-preview
     * worker, never on the UI/camera callback thread. A hard deadline converts a bad fence/device
     * state into a dropped preview frame instead of allowing an unbounded black/frozen pipeline.
     */
    private fun awaitSubmittedVulkanCompletionOnWorker(
        slotId: Int,
        submissionId: Long,
        previewWidth: Int,
        previewHeight: Int,
        cfaCellDecimation: Int,
        camera2PriorWbGains: FloatArray
    ): IntArray? {
        val deadlineNs =
            SystemClock.elapsedRealtimeNanos() + P0_VULKAN_COMPLETION_TIMEOUT_MS * 1_000_000L
        while (!closed && SystemClock.elapsedRealtimeNanos() < deadlineNs) {
            val result = ImageUtils.pollRawPreview(
                frameSlotIndex = slotId,
                submissionId = submissionId,
                previewWidth = previewWidth,
                previewHeight = previewHeight,
                cfaCellDecimation = cfaCellDecimation,
                camera2PriorWbGains = camera2PriorWbGains
            ) ?: return null
            if (result.getOrNull(0) != RAW_PREVIEW_ASYNC_PENDING_MAGIC) {
                return result
            }
            try {
                Thread.sleep(P0_VULKAN_COMPLETION_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        Log.e(
            TAG,
            "RAW_PREVIEW_P0_COMPLETION_TIMEOUT slot=$slotId submissionId=$submissionId " +
                "timeoutMs=$P0_VULKAN_COMPLETION_TIMEOUT_MS"
        )
        return null
    }

    private fun pollVulkanCompletions() {
        if (pendingVulkanFrames.isEmpty()) return
        val snapshot = pendingVulkanFrames.entries.toList()
        for ((slotId, pending) in snapshot) {
            if (closed) return
            val result = ImageUtils.pollRawPreview(
                frameSlotIndex = slotId,
                submissionId = pending.submissionId,
                previewWidth = pending.previewWidth,
                previewHeight = pending.previewHeight,
                cfaCellDecimation = pending.cfaCellDecimation,
                camera2PriorWbGains = pending.context.camera2PriorWbGains
            )
            if (result != null && result.getOrNull(0) == RAW_PREVIEW_ASYNC_PENDING_MAGIC) {
                continue
            }
            if (!pendingVulkanFrames.remove(slotId, pending)) continue
            val request = pending.context.request
            var delivered = false
            try {
                if (result == null) {
                    renderFailures++
                    recordDrop(RawPreviewDropReason.NATIVE_RENDER_FAILED, request)
                } else {
                    vulkanCompletedFrames++
                    delivered = publishCompletedFrame(pending.context, result)
                }
            } finally {
                releaseRequest(request)
                if (!delivered) {
                    pending.context.slot.clearCpuRgbaBuffer()
                    RawPreviewFrameLifecycleRegistry.released(
                        request.config.pipelineGeneration,
                        request.sensorTimestampNs,
                        request.producerKind,
                        SystemClock.elapsedRealtimeNanos(),
                        "vulkan_completion_undelivered"
                    )
                    returnOutputSlot(pending.context.slot, "vulkan_completion_undelivered")
                }
            }
        }
    }

    private fun releaseOutputSlot(
        slot: OutputSlot,
        glFenceHandle: Long,
        source: ViewfinderEffectiveSource,
        generation: Int,
        sensorTimestampNs: Long,
        producerKind: RawPreviewProducerKind,
        eglGeneration: Int
    ) {
        slot.clearCpuRgbaBuffer()
        when {
            glFenceHandle == RawPreviewFrame.GL_INTEROP_FAILED_HANDLE -> {
                gpuOutputFailures++
                recordDropForFrame(source, generation, sensorTimestampNs, producerKind, RawPreviewDropReason.GL_INTEROP_FAILED)
                gpuInteropController.markFailure(
                    generation, eglGeneration, "eglimage_bind_failed", permanentForBoundary = false
                )
                // EGLImage import/bind never became a valid sampled GL owner, so this backing may
                // be retired immediately. The enclosing CPU-capable slot remains reusable.
                slot.releaseGpuBuffer()
                returnOutputSlot(slot, "gl_interop_failed")
                RawPreviewFrameLifecycleRegistry.released(
                    generation, sensorTimestampNs, producerKind, SystemClock.elapsedRealtimeNanos(), "gl_interop_failed"
                )
                RawPreviewInteropCapabilities.recordActivePath("CPU_VISIBLE_RGBA_TO_GLES_GL_INTEROP_FAILED")
                Log.e(TAG, "RAW_PREVIEW_GPU_OUTPUT_DISABLED slot=${slot.id} reason=eglimage_bind_failed ${outputSlotHealth()}")
            }
            glFenceHandle < 0L -> {
                returnOutputSlot(slot, "gl_not_used")
                RawPreviewFrameLifecycleRegistry.released(
                    generation, sensorTimestampNs, producerKind, SystemClock.elapsedRealtimeNanos(), "gl_not_used_cpu_copy"
                )
            }
            glFenceHandle > 0L -> {
                slot.pendingGlFenceHandle = glFenceHandle
                slot.pendingSource = source
                slot.pendingGeneration = generation
                slot.pendingSensorTimestampNs = sensorTimestampNs
                slot.pendingProducerKind = producerKind
                slot.pendingEglGeneration = eglGeneration
                if (markOutputSlotPendingFence(slot)) {
                    pendingGpuOutputSlots.offer(slot)
                } else {
                    fenceBackend.destroyFence(glFenceHandle)
                    slot.pendingGlFenceHandle = 0L
                    slot.pendingEglGeneration = -1
                    slot.quarantineCurrentGpuBuffer()
                    gpuInteropController.markFailure(
                        generation, eglGeneration, "pending_fence_state_mismatch", permanentForBoundary = true
                    )
                    returnOutputSlot(slot, "pending_fence_state_mismatch")
                    RawPreviewFrameLifecycleRegistry.released(
                        generation, sensorTimestampNs, producerKind, SystemClock.elapsedRealtimeNanos(), "pending_fence_state_mismatch"
                    )
                }
            }
            else -> {
                gpuOutputFailures++
                // GLES sampled this AHardwareBuffer, but safe completion could not be proven.
                // Quarantine ONLY the GPU backing. The OutputSlot itself owns an independent CPU
                // RGBA store, so returning the slot keeps preview alive on the CPU-visible path.
                // The suspect AHB stays retained until renderer lifecycle teardown.
                slot.quarantineCurrentGpuBuffer()
                gpuInteropController.markFailure(
                    generation, eglGeneration, "gl_fence_create_failed", permanentForBoundary = false
                )
                recordDropForFrame(source, generation, sensorTimestampNs, producerKind, RawPreviewDropReason.GL_FENCE_CREATE_FAILED)
                returnOutputSlot(slot, "gl_fence_create_failed_gpu_backing_quarantined")
                RawPreviewFrameLifecycleRegistry.released(
                    generation, sensorTimestampNs, producerKind, SystemClock.elapsedRealtimeNanos(), "gl_fence_create_failed"
                )
                RawPreviewInteropCapabilities.recordActivePath("CPU_VISIBLE_RGBA_TO_GLES_GL_FENCE_FAILED")
                Log.e(
                    TAG,
                    "RAW_PREVIEW_GPU_OUTPUT_QUARANTINED slot=${slot.id} reason=gl_fence_unavailable " +
                        outputSlotHealth()
                )
            }
        }
        if (hasPendingRequest()) scheduleDrain()
    }

    private fun recordDropForFrame(
        source: ViewfinderEffectiveSource,
        generation: Int,
        sensorTimestampNs: Long,
        producerKind: RawPreviewProducerKind,
        reason: RawPreviewDropReason
    ) {
        dropCounters.incrementAndGet(reason.ordinal)
        RawPreviewCadenceDiagnostics.dropped(
            source, generation, sensorTimestampNs, producerKind, reason
        )
    }

    private fun reclaimCompletedGpuOutputSlots() {
        val count = pendingGpuOutputSlots.size
        repeat(count) {
            val slot = pendingGpuOutputSlots.poll() ?: return@repeat
            val handle = slot.pendingGlFenceHandle
            val state = if (handle > 0L) fenceBackend.pollFence(handle) else -1
            when (state) {
                1 -> {
                    val completedGeneration = slot.pendingGeneration
                    val completedTimestampNs = slot.pendingSensorTimestampNs
                    val completedProducerKind = slot.pendingProducerKind
                    slot.pendingGlFenceHandle = 0L
                    slot.pendingSource = null
                    slot.pendingGeneration = -1
                    slot.pendingSensorTimestampNs = 0L
                    slot.pendingProducerKind = RawPreviewProducerKind.CANONICAL_RING
                    slot.pendingEglGeneration = -1
                    returnOutputSlot(slot, "gl_fence_signaled")
                    RawPreviewFrameLifecycleRegistry.released(
                        completedGeneration,
                        completedTimestampNs,
                        completedProducerKind,
                        SystemClock.elapsedRealtimeNanos(),
                        "gl_fence_signaled"
                    )
                }
                0 -> pendingGpuOutputSlots.offer(slot)
                else -> {
                    gpuOutputFailures++
                    val failedGeneration = slot.pendingGeneration
                    val failedTimestampNs = slot.pendingSensorTimestampNs
                    val failedProducerKind = slot.pendingProducerKind
                    slot.pendingGlFenceHandle = 0L
                    val failedEglGeneration = slot.pendingEglGeneration
                    slot.pendingEglGeneration = -1
                    slot.quarantineCurrentGpuBuffer()
                    gpuInteropController.markFailure(
                        slot.pendingGeneration, failedEglGeneration, "gl_fence_poll_failed",
                        permanentForBoundary = false
                    )
                    slot.pendingSource?.let { source ->
                        recordDropForFrame(
                            source,
                            slot.pendingGeneration,
                            slot.pendingSensorTimestampNs,
                            failedProducerKind,
                            RawPreviewDropReason.GL_FENCE_POLL_FAILED
                        )
                    }
                    slot.pendingSource = null
                    slot.pendingGeneration = -1
                    slot.pendingSensorTimestampNs = 0L
                    slot.pendingProducerKind = RawPreviewProducerKind.CANONICAL_RING
                    // Do not destroy or recycle the suspect AHB here: completion is unknown.
                    // The CPU store is independent and is immediately returned to service.
                    returnOutputSlot(slot, "gl_fence_poll_failed_gpu_backing_quarantined")
                    RawPreviewFrameLifecycleRegistry.released(
                        failedGeneration,
                        failedTimestampNs,
                        failedProducerKind,
                        SystemClock.elapsedRealtimeNanos(),
                        "gl_fence_poll_failed"
                    )
                    RawPreviewInteropCapabilities.recordActivePath("CPU_VISIBLE_RGBA_TO_GLES_GL_FENCE_FAILED")
                    Log.e(
                        TAG,
                        "RAW_PREVIEW_GPU_OUTPUT_QUARANTINED slot=${slot.id} reason=gl_fence_poll_failed " +
                            outputSlotHealth()
                    )
                }
            }
        }
    }

    private fun computePreviewOutputDimensions(
        request: Request,
        geometry: com.bncam.core.runtime.RawStreamGeometry?
    ): Pair<Int, Int> {
        val fullWidth = request.sourceWidth.coerceAtLeast(2)
        val fullHeight = request.sourceHeight.coerceAtLeast(2)
        val cropLeft = if (request.useRuntimeCrop) {
            (geometry?.cropLeft ?: 0).coerceIn(0, (fullWidth - 2).coerceAtLeast(0))
        } else 0
        val cropTop = if (request.useRuntimeCrop) {
            (geometry?.cropTop ?: 0).coerceIn(0, (fullHeight - 2).coerceAtLeast(0))
        } else 0
        val requestedWidth = if (request.useRuntimeCrop) geometry?.cropWidth ?: 0 else 0
        val requestedHeight = if (request.useRuntimeCrop) geometry?.cropHeight ?: 0 else 0
        var cropWidth = if (requestedWidth > 0) {
            requestedWidth.coerceIn(2, fullWidth - cropLeft)
        } else {
            fullWidth - cropLeft
        }
        var cropHeight = if (requestedHeight > 0) {
            requestedHeight.coerceIn(2, fullHeight - cropTop)
        } else {
            fullHeight - cropTop
        }
        cropWidth = (cropWidth and 0xFFFE).coerceAtLeast(2)
        cropHeight = (cropHeight and 0xFFFE).coerceAtLeast(2)
        // BALANCED remains the explicit shipping tier. SHARP exists only as a Phase-8
        // profiling prototype until device cadence/thermal evidence allows promotion.
        return RawPreviewResolutionPolicy.outputDimensions(
            cropWidth = cropWidth,
            cropHeight = cropHeight,
            tier = com.bncam.core.runtime.RawPreviewQualityTier.BALANCED
        )
    }

    private fun rawPreviewSettingsConnectivitySummary(config: RawPreviewRenderConfig?): String {
        if (config == null) return "config=missing"
        val curvesConnected = config.toneCurve.isNotEmpty() && config.gammaCurve.isNotEmpty() &&
            config.sectionCurve.isNotEmpty()
        val calibrationConnected = config.blackLevels.size >= 4 && config.whiteLevel > 1 &&
            config.wbGains.size >= 4 && config.colorMatrix.size >= 9
        val captureDetailNeutral = config.profileDetailAmount == 0f && config.profileNrLuminance == 0f &&
            config.profileNrColor == 0f
        return buildString {
            append("cfa=connected")
            append(",blackWhite=").append(if (calibrationConnected) "connected" else "invalid")
            append(",demosaic=connected")
            append(",wbCcm=").append(if (calibrationConnected) "connected" else "invalid")
            append(",exposure=connected")
            append(",profileTone=connected")
            append(",profileColor=connected")
            append(",curves=").append(if (curvesConnected) "connected" else "missing")
            append(",captureDetailNr=").append(if (captureDetailNeutral) "intentionally_preview_neutral" else "active")
            append(",cropZoom=runtime_geometry")
            append(",source=").append(config.source.name)
            append(",bootstrap=").append(config.isBootstrap)
        }
    }

    fun reportGlUploadCost(uploadTimeMs: Float) {
        if (!uploadTimeMs.isFinite() || uploadTimeMs <= 0f || closed) return
        val previous = glUploadCostEmaMs
        glUploadCostEmaMs = if (previous <= 0f) {
            uploadTimeMs
        } else {
            previous * 0.88f + uploadTimeMs * 0.12f
        }
    }

    private fun recordRenderCost(renderTimeMs: Float) {
        if (!renderTimeMs.isFinite() || renderTimeMs <= 0f) return
        renderCostEmaMs = if (renderCostEmaMs <= 0f) {
            renderTimeMs
        } else {
            renderCostEmaMs * 0.88f + renderTimeMs * 0.12f
        }
    }

    private fun isCurrent(request: Request): Boolean {
        val current = activeConfig
        return request.configRevision == configRevision.get() &&
            current != null &&
            current.pipelineGeneration == request.config.pipelineGeneration &&
            current.source == request.config.source
    }

    private fun releaseRequest(request: Request) {
        RawPreviewFrameLifecycleRegistry.inputReleased(
            request.config.pipelineGeneration,
            request.sensorTimestampNs,
            request.producerKind,
            SystemClock.elapsedRealtimeNanos()
        )
        ImageUtils.releaseRawPreviewHardwareBuffer(request.retainedHardwareBuffer)
    }

    private fun histogramPercentile64(histogram: IntArray, quantile: Float): Float {
        val total = histogram.sum().coerceAtLeast(0)
        if (total <= 0) return -1f
        val target = ((total - 1) * quantile.coerceIn(0f, 1f)).toInt()
        var cumulative = 0
        histogram.forEachIndexed { index, count ->
            cumulative += count.coerceAtLeast(0)
            if (cumulative > target) return (index + 0.5f) / 64.0f
        }
        return 1f
    }

    private fun logDiagnostics(frame: RawPreviewFrame? = null) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastDiagnosticsMs < DIAGNOSTIC_INTERVAL_MS) return
        val intervalMs = (now - lastDiagnosticsMs).coerceAtLeast(1L)
        val intervalFrames = (rendered - lastDiagnosticsRendered).coerceAtLeast(0L)
        val previewFps = intervalFrames * 1000.0f / intervalMs
        lastDiagnosticsMs = now
        lastDiagnosticsRendered = rendered
        val displayP50 = frame?.let { histogramPercentile64(it.displayLumaHistogram64, 0.50f) } ?: -1f
        val displayP95 = frame?.let { histogramPercentile64(it.displayLumaHistogram64, 0.95f) } ?: -1f
        val displayClipping = frame?.let {
            if (it.displaySampleCount > 0) {
                (it.displayRClipSampleCount + it.displayGClipSampleCount + it.displayBClipSampleCount)
                    .toFloat() / (3.0f * it.displaySampleCount.toFloat())
            } else {
                0f
            }
        } ?: -1f
        Log.i(
            TAG,
            "RAW_PREVIEW_FRAME source=${frame?.source ?: activeConfig?.source ?: ViewfinderEffectiveSource.YUV} " +
                "size=${frame?.let { "${it.width}x${it.height}" } ?: "none"} " +
                "boundary=${if (frame != null) "RAW_PROCESSED_RGB_VALID_BUFFER_PUBLISHED" else "WAITING"} " +
                "rawMin=${frame?.normalizedRawMin ?: -1f} rawMax=${frame?.normalizedRawMax ?: -1f} " +
                "rgbMin=${frame?.outputRgbMin ?: -1f} rgbMax=${frame?.outputRgbMax ?: -1f} " +
                "rgbMean=${frame?.outputRgbMean ?: -1f} alpha=${frame?.outputAlpha ?: -1} " +
                "RAW_PREVIEW_RENDER_MS=${frame?.renderTimeMs ?: -1f} " +
                "RAW_PREVIEW_FPS=$previewFps decimation=${frame?.cfaCellDecimation ?: -1} " +
                "unpackMs=${frame?.rawUnpackTimeMs ?: -1f} gpuKernelMs=${frame?.demosaicTimeMs ?: -1f} " +
                "gpuTransferSyncMs=${frame?.colorTimeMs ?: -1f} " +
                "gpuHostHandoffMs=${frame?.tonePackTimeMs ?: -1f} " +
                "sceneMidtone=${frame?.sceneMidtone ?: -1f} targetGain=${frame?.targetExposureGain ?: -1f} " +
                "appliedGain=${frame?.appliedExposureGain ?: -1f} " +
                "previewRequestedEv=${frame?.previewRequestedEv ?: -1f} " +
                "previewHighlightLimitedEv=${frame?.previewHighlightLimitedEv ?: -1f} " +
                "previewAppliedEv=${frame?.previewAppliedEv ?: -1f} " +
                "previewSceneKey=${frame?.previewSceneKey ?: -1f} " +
                "previewHighlightHeadroomEv=${frame?.previewHighlightHeadroomEv ?: -1f} " +
                "previewSceneRangeEv=${frame?.previewSceneRangeEv ?: -1f} " +
                "displayP50=$displayP50 displayP95=$displayP95 displayClipping=$displayClipping " +
                "gtmTarget=${frame?.sceneMidtoneTarget ?: -1f} " +
                "gtmShoulder=${frame?.gtmShoulderStart ?: -1f}/${frame?.gtmShoulderStrength ?: -1f} " +
                "gtmHighlightPressure=${frame?.gtmHighlightPressure ?: -1f} gtmP95CompressionEv=${frame?.gtmP95CompressionEv ?: -1f} " +
                "gtmP99CompressionEv=${frame?.gtmP99CompressionEv ?: -1f} drPressure=${frame?.gtmDynamicRangePressure ?: -1f} " +
                "ltmStrength=${frame?.ltmStrength ?: -1f} ltmLiftEv=${frame?.ltmMaxLiftEv ?: -1f} " +
                "ltmCompressEv=${frame?.ltmMaxCompressEv ?: -1f} " +
                "legacySpatialExposure=RETIRED_PHASE11G " +
                "spatialExposureTiles=${frame?.spatialExposureTileCount ?: 0} " +
                "spatialExposureSceneP10=${frame?.spatialExposureSceneP10 ?: -1f} " +
                "spatialExposureSceneP50=${frame?.spatialExposureSceneP50 ?: -1f} " +
                "spatialExposureSceneP90=${frame?.spatialExposureSceneP90 ?: -1f} " +
                "spatialExposureSceneP99=${frame?.spatialExposureSceneP99 ?: -1f} " +
                "spatialExposureDrEv=${frame?.spatialExposureMeasuredDrEv ?: -1f} " +
                "spatialExposureNeutralEv=${frame?.spatialExposureLowerNeutralEv ?: -1f}/" +
                "${frame?.spatialExposureUpperNeutralEv ?: -1f} " +
                "spatialExposureAuthority=${frame?.spatialExposureAuthority ?: -1f} " +
                "commonHighlightScalePixels=${frame?.commonHighlightScalePixels ?: -1} " +
                "rawClipFraction=${frame?.let { if (it.rawSampleCount > 0) it.rawNearClipSampleCount.toFloat() / it.rawSampleCount else 0f } ?: -1f} " +
                "rawSaturatedFraction=${frame?.let { if (it.rawSampleCount > 0) it.rawTrueSaturatedSampleCount.toFloat() / it.rawSampleCount else 0f } ?: -1f} " +
                "sensorExposureNs=${frame?.captureExposureTimeNs ?: -1L} sensorIso=${frame?.captureSensitivityIso ?: -1} " +
                "rawClipRgb=${frame?.rawRClipSampleCount ?: -1}/${frame?.rawGClipSampleCount ?: -1}/${frame?.rawBClipSampleCount ?: -1} " +
                "highlightReconstructionActivated=${frame?.highlightReconstructionActivated ?: false} " +
                "reconstructedRawSamples=${frame?.highlightReconstructedSampleCount ?: -1} " +
                "postWbClipSamples=${frame?.postWbClipSampleCount ?: -1} " +
                "postCcmClipSamples=${frame?.postCcmClipSampleCount ?: -1} " +
                "directHostInput=${frame?.directHostInputUsed ?: false} " +
                "directAhbInput=${frame?.directHardwareBufferInputUsed ?: false} " +
                "inputAhbFormat=${frame?.inputAhbFormat ?: 0} " +
                "inputAhbUsage=0x${(frame?.inputAhbUsage ?: 0L).toString(16)} " +
                "inputInteropStatus=${frame?.inputInteropStatus ?: 0} " +
                "gpuResidentOutput=${frame?.gpuResidentOutputUsed ?: false} " +
                "gpuOutputFailures=$gpuOutputFailures " +
                "fastPath=${lastFastPathKind ?: "UNPROVEN"} " +
                "fastPathDirectAhbFrames=$directAhbGpuFastFrames " +
                "fastPathHostInputFrames=$hostInputGpuResidentFrames " +
                "fastPathGpuCompatFrames=$gpuResidentCompatibilityFrames " +
                "fastPathCpuFallbackFrames=$cpuVisibleFallbackFrames " +
                "compactNv21Requested=$mlAnalysisRequested compactNv21Frames=$compactAnalysisFrames " +
                "resolutionPolicy=BALANCED_FIXED max=${PREVIEW_MAX_WIDTH}x${PREVIEW_MAX_HEIGHT} " +
                "sharpPrototypeMax=${RawPreviewResolutionPolicy.SHARP_PROTOTYPE_MAX_WIDTH}x" +
                "${RawPreviewResolutionPolicy.SHARP_PROTOTYPE_MAX_HEIGHT} sharpPrototypeActive=false " +
                "settingsConnectivity={${rawPreviewSettingsConnectivitySummary(activeConfig)}} " +
                "renderEmaMs=$renderCostEmaMs glUploadEmaMs=$glUploadCostEmaMs " +
                "RAW_PREVIEW_DROPPED_BUSY=$droppedBusy pendingQueue=${pendingRequestCount()}/$MAX_PENDING_REQUESTS " +
                "vulkanPending=${pendingVulkanFrames.size}/$OUTPUT_SLOT_COUNT " +
                "vulkanSubmitted=$vulkanSubmittedFrames vulkanCompleted=$vulkanCompletedFrames " +
                "analysisReadbackFrames=$analysisReadbackFrames rendered=$rendered " +
                "slotHealth={${outputSlotHealth()}} dropReasons={${dropCountsSummary()}} " +
                "frameLifecycle={${RawPreviewFrameLifecycleRegistry.latest()?.summary() ?: "unavailable"}} " +
                "activeLifecycleFrames=${RawPreviewFrameLifecycleRegistry.activeCount()} " +
                "retainFailures=$retainFailures renderFailures=$renderFailures " +
                "generation=${activeConfig?.pipelineGeneration ?: -1}"
        )
    }

    suspend fun pauseAndAwaitIdle(timeoutMs: Long = 750L): Boolean {
        configure(null)
        if (Thread.currentThread().name == "BnCamRawPreview") return true

        val idleBarrier = CompletableDeferred<Unit>()
        val queued = runCatching {
            executor.execute { idleBarrier.complete(Unit) }
        }.isSuccess
        if (!queued) {
            Log.w(TAG, "RAW preview worker rejected idle barrier; renderer may already be closing")
            return false
        }

        val idle = withTimeoutOrNull(timeoutMs) {
            idleBarrier.await()
            while (pendingVulkanFrames.isNotEmpty()) {
                if (!closed) scheduleDrain(COMPLETION_POLL_INTERVAL_MS)
                delay(COMPLETION_POLL_INTERVAL_MS)
            }
            true
        } ?: false
        if (!idle) {
            Log.w(TAG, "Timed out waiting for RAW preview worker to become idle")
        }
        return idle
    }

    override fun close() {
        if (closed) return
        closed = true
        configRevision.incrementAndGet()
        clearPendingRequests()
        // Native Vulkan owns an independent AHardwareBuffer reference after submission. Release
        // Kotlin input ownership exactly once while teardown later performs the only bounded fence
        // wait that remains in the backend.
        pendingVulkanFrames.values.forEach { pending ->
            releaseRequest(pending.context.request)
            RawPreviewFrameLifecycleRegistry.released(
                pending.context.request.config.pipelineGeneration,
                pending.context.request.sensorTimestampNs,
                pending.context.request.producerKind,
                SystemClock.elapsedRealtimeNanos(),
                "renderer_close_pending_vulkan"
            )
        }
        pendingVulkanFrames.clear()
        executor.shutdownNow()
        backendWarmupExecutor.shutdownNow()
        allOutputSlots.forEach { slot ->
            slot.releaseCpuBufferReferences()
            if (slot.pendingGlFenceHandle > 0L) {
                fenceBackend.destroyFence(slot.pendingGlFenceHandle)
                slot.pendingGlFenceHandle = 0L
            }
            outputSlotLedger.close(slot.id)
            slot.releaseGpuBuffer()
        }
        availableOutputSlots.clear()
        pendingGpuOutputSlots.clear()
    }

    private companion object {
        const val TAG = "BnCamRawPreview"
        const val PREVIEW_MAX_WIDTH = RawPreviewResolutionPolicy.QUALITY_MAX_WIDTH
        const val PREVIEW_MAX_HEIGHT = RawPreviewResolutionPolicy.QUALITY_MAX_HEIGHT
        const val RGBA_BYTES_PER_PIXEL = 4
        const val OUTPUT_SLOT_COUNT = 2
        const val MAX_QUARANTINED_GPU_BACKINGS_PER_SLOT = 2
        const val MAX_PENDING_REQUESTS = 1
        const val MAX_OUTPUT_BYTES = PREVIEW_MAX_WIDTH * PREVIEW_MAX_HEIGHT * RGBA_BYTES_PER_PIXEL
        const val MAX_ANALYSIS_NV21_BYTES = ((PREVIEW_MAX_WIDTH / 4) * (PREVIEW_MAX_HEIGHT / 4) * 3) / 2
        const val DIAGNOSTIC_INTERVAL_MS = 2_000L
        const val CONFIG_DIAGNOSTIC_INTERVAL_MS = 5_000L
        const val COMPLETION_POLL_INTERVAL_MS = 1L
        const val P0_VULKAN_COMPLETION_POLL_MS = 1L
        const val P0_VULKAN_COMPLETION_TIMEOUT_MS = 250L
        const val P0_NATIVE_VULKAN_SLOT_ID = 0
        const val ANALYSIS_SIDECAR_INTERVAL_NS = 100_000_000L // 10 Hz, independent of display cadence.
        const val RAW_PREVIEW_ASYNC_SUBMITTED_MAGIC = 0x42505253
        const val RAW_PREVIEW_ASYNC_PENDING_MAGIC = 0x42505250
        const val ASYNC_ANALYSIS_READBACK_INDEX = 625
        const val HIGHLIGHT_RECON_DIAGNOSTICS_START_INDEX = 626
        const val PREVIEW_SCENE_EXPOSURE_DIAGNOSTICS_START_INDEX = 632
        const val SENSOR_EXPOSURE_DIAGNOSTICS_START_INDEX = 638
    }
}
