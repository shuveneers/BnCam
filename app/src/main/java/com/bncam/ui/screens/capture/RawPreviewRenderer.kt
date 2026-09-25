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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
    val toneCurve: FloatArray,
    val gammaCurve: FloatArray,
    val sectionCurve: FloatArray,
    val rotationDegrees: Int,
    val isBootstrap: Boolean = false,
    val calibrationSensorId: String = "",
    val staticBlackLevels: FloatArray? = null,
    val staticWhiteLevel: Int = 0,
    val lensShadingAlreadyApplied: Boolean? = null,
    val lensMapActiveRect: IntArray? = null
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
    val sceneMidtone: Float,
    val sceneMidtoneTarget: Float,
    val gtmShoulderStart: Float,
    val gtmShoulderStrength: Float,
    val gtmBlackAnchor: Float,
    val gtmLowerMidLift: Float,
    val gtmContrastStrength: Float,
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
 * One pending RAW frame is replaced by each newer offer while submitted GPU slots retain their
 * own frames. The capture warm ring has independent ownership and is never pruned here.
 */
private object RawPreviewRetiredGpuBackings {
    private const val MAX_RETAINED = 24
    private data class Retired(
        val buffer: HardwareBuffer,
        var fence: Long,
        val backend: RawPreviewFenceBackend
    )
    private val retained = ArrayList<Retired>()
    private val poller = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "BnCamRawPreviewGlRetirement").apply { isDaemon = true }
    }.apply {
        removeOnCancelPolicy = true
        scheduleWithFixedDelay({ poll() }, 10L, 10L, TimeUnit.MILLISECONDS)
    }

    fun acceptingNewBackings(): Boolean = synchronized(retained) { retained.size < MAX_RETAINED }

    fun retain(buffer: HardwareBuffer, fence: Long, backend: RawPreviewFenceBackend) {
        synchronized(retained) { retained.add(Retired(buffer, fence, backend)) }
    }

    private fun poll() {
        val snapshot = synchronized(retained) { retained.filter { it.fence > 0L }.toList() }
        snapshot.forEach { item ->
            when (runCatching { item.backend.pollFence(item.fence) }.getOrDefault(-1)) {
                1 -> {
                    synchronized(retained) { retained.remove(item) }
                    ImageUtils.releaseRawPreviewEglImage(item.buffer)
                    runCatching { item.buffer.close() }
                }
                0 -> Unit
                else -> item.fence = 0L // No completion proof: retain backing for process lifetime.
            }
        }
    }
}

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
        val sourceHeight: Int,
        val offeredElapsedNs: Long
    ) {
        var resolvedColors: ResolvedColors? = null
        var resolvedCalibration: ResolvedCalibration? = null
        val released = AtomicBoolean(false)
    }

    private data class ResolvedCalibration(
        val black: FloatArray, val white: Int, val blackAuthority: String,
        val whiteAuthority: String, val lensMap: FloatArray?, val lensColumns: Int,
        val lensRows: Int, val activeRect: IntArray?, val lensAuthority: String
    )

    private data class ResolvedColors(
        val renderWb: FloatArray,
        val renderMatrix: FloatArray,
        val camera2PriorWb: FloatArray,
        val camera2PriorMatrix: FloatArray,
        val exactPair: ExactFrameColorPair?
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
    private var pendingRequest: Request? = null
    private data class NativeGeometry(
        val width: Int, val height: Int, val rowStride: Int, val pixelStride: Int,
        val cropLeft: Int, val cropTop: Int, val cropWidth: Int, val cropHeight: Int
    )
    private data class PendingGpu(
        val request: Request,
        val slot: OutputSlot,
        val geometry: NativeGeometry,
        val localCfaPattern: Int,
        val localBlackLevels: FloatArray,
        val expectedOutput: Pair<Int, Int>,
        val outputHardwareBuffer: HardwareBuffer?,
        val outputRgba: ByteBuffer,
        val analysisNv21: ByteBuffer?,
        val interopEglGeneration: Int
    )
    private val pendingGpuRequests = ConcurrentLinkedQueue<PendingGpu>()
    private val drainScheduled = AtomicBoolean(false)
    private val configRevision = AtomicLong(0L)
    private val backendPrepareScheduled = AtomicBoolean(false)
    private val backendPrepared = AtomicBoolean(false)
    private val backendPreparedAtNs = AtomicLong(0L)
    private data class ExactFrameColorPair(
        val wbGains: FloatArray,
        val colorMatrix: FloatArray
    )

    private val liveWhiteBalanceOverride = AtomicReference<FloatArray?>(null)
    private val liveWhiteBalanceColorPair = AtomicReference<ExactFrameColorPair?>(null)
    // System-AWB RAW preview must consume WB + CCM from the same sensor timestamp. A WB-only
    // convergence override can otherwise pair one frame's gains with another frame's matrix.
    private val exactFrameColorPairs = ConcurrentHashMap<RawPreviewFrameMetadataCache.Key, ExactFrameColorPair>()
    private val frameMetadata = RawPreviewFrameMetadataCache()
    private val autoWhiteBalanceColorPair = AtomicReference<ExactFrameColorPair?>(null)
    private val latestOfferedSensorTimestampNs = AtomicLong(Long.MIN_VALUE)
    private val lastRendererOfferElapsedNs = AtomicLong(0L)
    private val lastRendererPublishElapsedNs = AtomicLong(0L)
    private class OutputSlot(val id: Int) {
        private var cpuRgba: ByteBuffer? = null
        private var gpuFallbackRgba: ByteBuffer? = null
        private var analysisNv21Buffer: ByteBuffer? = null
        var hardwareBuffer: HardwareBuffer? = null
        var hardwareBufferWidth: Int = 0
        var hardwareBufferHeight: Int = 0
        var hardwareBufferUsage: Long = 0L
        var pendingGlFenceHandle: Long = 0L
        var pendingSource: ViewfinderEffectiveSource? = null
        var pendingGeneration: Int = -1
        var pendingSensorTimestampNs: Long = 0L
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

        fun ensureGpuFallbackRgbaBuffer(): ByteBuffer {
            return gpuFallbackRgba ?: ByteBuffer.allocateDirect(MAX_OUTPUT_BYTES)
                .order(ByteOrder.nativeOrder())
                .also { gpuFallbackRgba = it }
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
            gpuFallbackRgba = null
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
            if (quarantineBudgetExhausted || !RawPreviewRetiredGpuBackings.acceptingNewBackings()) return null
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

        fun retireForClose(backend: RawPreviewFenceBackend, safeToReleaseCurrent: Boolean) {
            hardwareBuffer?.let { buffer ->
                if (safeToReleaseCurrent && pendingGlFenceHandle <= 0L) {
                    ImageUtils.releaseRawPreviewEglImage(buffer)
                    runCatching { buffer.close() }
                } else {
                    RawPreviewRetiredGpuBackings.retain(buffer, pendingGlFenceHandle, backend)
                }
            }
            hardwareBuffer = null
            pendingGlFenceHandle = 0L
            quarantinedGpuBuffers.forEach { buffer ->
                RawPreviewRetiredGpuBackings.retain(buffer, 0L, backend)
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
        if (source != null && generation != null) {
            RawPreviewCadenceDiagnostics.dropped(source, generation, timestampNs, reason)
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
        // A cold Adreno pipeline compile can outlast the normal publication watchdog. Switching
        // to the legacy path during compilation forces a second large shader compile and can
        // exhaust the driver's memory before the first GPU-resident frame is submitted.
        if (backendPrepareScheduled.get()) return false
        val preparedAtNs = backendPreparedAtNs.get()
        if (preparedAtNs != 0L && lastRendererPublishElapsedNs.get() == 0L &&
            SystemClock.elapsedRealtimeNanos() - preparedAtNs < 15_000_000_000L) return false
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
        val (stale, accepted) = synchronized(pendingRequestLock) {
            if (closed || !isCurrent(request)) {
                null to false
            } else {
                pendingRequest.also { pendingRequest = request } to true
            }
        }
        if (!accepted) {
            retireQueuedRequest(request, if (closed) RawPreviewDropReason.PREVIEW_DROP_RENDERER_CLOSING
                else RawPreviewDropReason.STALE_GENERATION, "offer_after_route_change")
            return
        }
        stale?.let { dropped ->
            droppedBusy++
            retireQueuedRequest(dropped, RawPreviewDropReason.PREVIEW_DROP_REPLACED_BY_NEWER,
                "preview_replaced_by_newer")
        }
    }

    private fun retireQueuedRequest(request: Request, reason: RawPreviewDropReason, detail: String) {
        recordDrop(reason, request)
        exactFrameColorPairs.remove(metadataKey(request))
        frameMetadata.discard(metadataKey(request))
        releaseRequest(request)
        RawPreviewFrameLifecycleRegistry.released(request.config.pipelineGeneration,
            request.sensorTimestampNs, SystemClock.elapsedRealtimeNanos(), detail)
    }

    private fun pollPendingRequest(): Request? = synchronized(pendingRequestLock) {
        pendingRequest.also { pendingRequest = null }
    }

    private fun hasPendingRequest(): Boolean = synchronized(pendingRequestLock) {
        pendingRequest != null
    }

    private fun pendingRequestCount(): Int = synchronized(pendingRequestLock) {
        if (pendingRequest == null) 0 else 1
    }

    private fun clearPendingRequests() {
        val stale = synchronized(pendingRequestLock) {
            pendingRequest.also { pendingRequest = null }
        }
        stale?.let { request ->
            val closing = closed
            retireQueuedRequest(request, if (closing) RawPreviewDropReason.PREVIEW_DROP_RENDERER_CLOSING
                else RawPreviewDropReason.STALE_GENERATION,
                if (closing) "renderer_closing" else "generation_stale")
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
                    if (prepared) {
                        backendPreparedAtNs.set(SystemClock.elapsedRealtimeNanos())
                        backendPrepared.set(true)
                    }
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
        generation: Int,
        sensorId: String,
        source: ViewfinderEffectiveSource,
        gains: FloatArray,
        colorMatrix: FloatArray
    ) {
        if (sensorTimestampNs <= 0L || sensorId.isBlank() || gains.size < 4 || colorMatrix.size < 9 ||
            gains.take(4).any { !it.isFinite() || it !in 0.25f..6.0f } ||
            !RawColorTransformEngine.validateSensorToLinearSrgbMatrix(colorMatrix.copyOf(9)).valid
        ) {
            return
        }
        val key = RawPreviewFrameMetadataCache.Key(generation, sensorId, source, sensorTimestampNs)
        exactFrameColorPairs[key] = ExactFrameColorPair(
            wbGains = gains.copyOf(4),
            colorMatrix = colorMatrix.copyOf(9)
        )
        if (exactFrameColorPairs.size > 24) {
            val oldest = exactFrameColorPairs.keys.minByOrNull { it.timestampNs }
            if (oldest != null && oldest != key) exactFrameColorPairs.remove(oldest)
        }
    }

    fun recordRawFrameMetadata(
        generation: Int, sensorId: String, source: ViewfinderEffectiveSource,
        sensorTimestampNs: Long, dynamicBlack: FloatArray?, dynamicWhite: Int?,
        lensMap: FloatArray?, lensColumns: Int, lensRows: Int
    ) {
        if (closed) return
        frameMetadata.record(RawPreviewFrameMetadataCache.Key(generation, sensorId, source, sensorTimestampNs),
            RawPreviewFrameMetadataCache.Entry(dynamicBlack, dynamicWhite, lensMap, lensColumns, lensRows),
            SystemClock.elapsedRealtimeNanos())
        if (hasPendingRequest()) scheduleDrain()
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
            frameMetadata.clear()
            autoWhiteBalanceColorPair.set(null)
            configRevision.incrementAndGet()
            latestOfferedSensorTimestampNs.set(Long.MIN_VALUE)
            clearPendingRequests()
            if (config == null || config.source == ViewfinderEffectiveSource.YUV) {
                allOutputSlots.forEach { slot ->
                    if (outputSlotLedger.state(slot.id) == RawPreviewOutputSlotState.AVAILABLE) {
                        slot.releaseCpuBufferReferences()
                    }
                }
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
        while (true) {
            val previous = latestOfferedSensorTimestampNs.get()
            if (sensorTimestampNs <= previous) {
                RawPreviewCadenceDiagnostics.duplicateOfferRejected(
                    config.source,
                    pipelineGeneration,
                    sensorTimestampNs
                )
                return
            }
            if (latestOfferedSensorTimestampNs.compareAndSet(previous, sensorTimestampNs)) break
        }
        RawPreviewCadenceDiagnostics.offered(config.source, pipelineGeneration, sensorTimestampNs)
        lastRendererOfferElapsedNs.set(SystemClock.elapsedRealtimeNanos())
        val retained = ImageUtils.retainRawPreviewHardwareBuffer(buffer)
        if (retained == 0L) {
            retainFailures++
            dropCounters.incrementAndGet(RawPreviewDropReason.RETAIN_FAILED.ordinal)
            RawPreviewCadenceDiagnostics.dropped(
                config.source, pipelineGeneration, sensorTimestampNs, RawPreviewDropReason.RETAIN_FAILED
            )
            logDiagnostics()
            return
        }
        RawPreviewFrameLifecycleRegistry.acquired(
            source = config.source.name,
            generation = pipelineGeneration,
            timestampNs = sensorTimestampNs,
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
            sourceHeight = buffer.height,
            offeredElapsedNs = SystemClock.elapsedRealtimeNanos()
        )
        enqueuePendingRequest(request)
        scheduleDrain()
    }

    private fun scheduleDrain(delayMs: Long = 0L) {
        if (closed || !drainScheduled.compareAndSet(false, true)) return
        val accepted = runCatching {
            executor.schedule({
                drainScheduled.set(false)
                drainLatest()
            }, delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
        }.isSuccess
        if (!accepted) drainScheduled.set(false)
    }

    private fun drainLatest() {
        if (closed) return
        reclaimCompletedGpuOutputSlots()
        // Finish the sole interop probe before considering another fresh frame. A second frame
        // would otherwise see PROBING as CPU fallback and eagerly compile the legacy pipeline.
        val probing = gpuInteropController.snapshot().mode == RawPreviewGpuInteropMode.PROBING
        val pendingFirst = if (probing) pendingGpuRequests.poll() else null
        val fresh = if (pendingFirst == null && availableOutputSlots.isNotEmpty() &&
            !(probing && gpuInteropController.snapshot().probeAttempted)) pollPendingRequest() else null
        val pending = pendingFirst ?: if (fresh == null) pendingGpuRequests.poll() else null
        val request = fresh ?: pending?.request ?: return
        val polling = pending != null
        if (!polling && isCurrent(request) && request.config.calibrationSensorId.isNotBlank() &&
            !frameMetadata.hasExact(metadataKey(request), SystemClock.elapsedRealtimeNanos()) &&
            SystemClock.elapsedRealtimeNanos() - request.offeredElapsedNs < METADATA_PAIR_GRACE_NS
        ) {
            requeueWaitingForMetadata(request)
            return
        }
        val slot = pending?.slot ?: acquireOutputSlot()
        if (slot == null) {
            droppedBusy++
            recordDrop(RawPreviewDropReason.NO_OUTPUT_SLOT, request)
            logOutputSlotInvariantIfBroken("no_output_slot")
            releaseRequest(request)
            RawPreviewFrameLifecycleRegistry.released(
                request.config.pipelineGeneration,
                request.sensorTimestampNs,
                SystemClock.elapsedRealtimeNanos(),
                "no_output_slot"
            )
            logDiagnostics()
            if (hasPendingRequest()) scheduleDrain()
            return
        }

        var delivered = false
        var gpuPending = false
        try {
            if (!polling && !isCurrent(request)) {
                recordDrop(RawPreviewDropReason.STALE_GENERATION, request)
                return
            }
            RawPreviewCadenceDiagnostics.processingStarted(
                request.config.source,
                request.config.pipelineGeneration,
                request.sensorTimestampNs
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
            val localCfaPattern = pending?.localCfaPattern ?: (request.config.cfaPattern xor phaseMask)
            val calibration = request.resolvedCalibration ?: resolveCalibration(request).also {
                request.resolvedCalibration = it
                if (received % 60L == 1L) Log.i(TAG,
                    "RAW_PREVIEW_CALIBRATION generation=${request.config.pipelineGeneration} " +
                        "sensor=${request.config.calibrationSensorId} timestampNs=${request.sensorTimestampNs} " +
                        "BLACK_LEVEL_AUTHORITY=${it.blackAuthority} WHITE_LEVEL_AUTHORITY=${it.whiteAuthority} " +
                        "LENS_SHADING_AUTHORITY=${it.lensAuthority}")
            }
            val localBlackLevels = pending?.localBlackLevels ?: if (phaseMask == 0 || calibration.black.size < 4) {
                calibration.black
            } else {
                FloatArray(4) { localSite ->
                    val localX = localSite and 1
                    val localY = (localSite shr 1) and 1
                    val sourceSite = ((localY xor phaseY) shl 1) or (localX xor phaseX)
                    calibration.black[sourceSite]
                }
            }

            val expectedOutput = pending?.expectedOutput ?: computePreviewOutputDimensions(
                request = request,
                geometry = requestGeometry
            )
            val nativeGeometry = pending?.geometry ?: NativeGeometry(
                width = requestGeometry?.bufferWidth ?: request.sourceWidth,
                height = requestGeometry?.bufferHeight ?: request.sourceHeight,
                rowStride = requestGeometry?.rowStride?.takeIf { it > 0 }
                    ?: if (request.config.source == ViewfinderEffectiveSource.RAW10) request.sourceWidth * 5 / 4 else request.sourceWidth * 2,
                pixelStride = requestGeometry?.pixelStride ?: 0,
                cropLeft = requestGeometry?.cropLeft ?: 0,
                cropTop = requestGeometry?.cropTop ?: 0,
                cropWidth = requestGeometry?.cropWidth ?: 0,
                cropHeight = requestGeometry?.cropHeight ?: 0
            )
            val interopSnapshot = RawPreviewInteropCapabilities.latest
            val interopEglGeneration = pending?.interopEglGeneration ?: (interopSnapshot?.eglGeneration ?: -1)
            val gpuDecision = if (polling) null else gpuInteropController.decision(
                pipelineGeneration = request.config.pipelineGeneration,
                eglGeneration = interopEglGeneration,
                capabilitiesReady = interopSnapshot?.readyForAhbEglImageInterop == true,
                capabilityReason = currentInteropCapabilityReason(interopSnapshot)
            )
            val usage = interopSnapshot?.vulkanOutputAhbUsage ?: 0L
            var outputHardwareBuffer: HardwareBuffer? = pending?.outputHardwareBuffer
            if (!polling && gpuDecision != RawPreviewGpuUseDecision.CPU_FALLBACK) {
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

            val analysisBuffer = if (polling) pending?.analysisNv21 else if (mlAnalysisRequested) {
                slot.ensureAnalysisNv21Buffer().apply { clear() }
            } else {
                null
            }
            val outputRgba = if (polling) {
                pending!!.outputRgba.apply { clear() }
            } else if (outputHardwareBuffer != null) {
                // JNI retains a CPU fallback target for this slot until its Vulkan fence completes.
                // Separate slot storage permits other RAW submissions while this frame is in flight.
                slot.ensureGpuFallbackRgbaBuffer().apply { clear() }
            } else {
                slot.ensureCpuRgbaBuffer().apply { clear() }
            }

            val colors = request.resolvedColors ?: run {
                val liveColorPair = liveWhiteBalanceColorPair.get()
                val liveWb = liveColorPair?.wbGains?.copyOf() ?: liveWhiteBalanceOverride.get()?.copyOf()
                // Keep the exact Camera2 prior associated with this submitted sensor frame.
                val exactFramePair = if (liveWb == null) {
                    exactFrameColorPairs.remove(metadataKey(request))
                } else {
                    null
                }
                val autoPair = if (liveWb == null) autoWhiteBalanceColorPair.get() else null
                ResolvedColors(
                    renderWb = (liveWb ?: autoPair?.wbGains ?: exactFramePair?.wbGains ?: request.config.wbGains).copyOf(),
                    renderMatrix = (liveColorPair?.colorMatrix ?: autoPair?.colorMatrix ?: exactFramePair?.colorMatrix ?: request.config.colorMatrix).copyOf(),
                    camera2PriorWb = (exactFramePair?.wbGains ?: request.config.wbGains).copyOf(),
                    camera2PriorMatrix = (exactFramePair?.colorMatrix ?: request.config.colorMatrix).copyOf(),
                    exactPair = exactFramePair
                ).also { request.resolvedColors = it }
            }
            val exactFramePair = colors.exactPair
            val effectiveWbGains = colors.renderWb
            val effectiveColorMatrix = colors.renderMatrix
            val camera2PriorWbGains = colors.camera2PriorWb
            val camera2PriorColorMatrix = colors.camera2PriorMatrix
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
                whiteLevel = calibration.white,
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
                toneCurve = request.config.toneCurve,
                gammaCurve = request.config.gammaCurve,
                sectionCurve = request.config.sectionCurve,
                rotationDegrees = request.config.rotationDegrees,
                sourceWidth = nativeGeometry.width,
                sourceHeight = nativeGeometry.height,
                sourceRowStrideBytes = nativeGeometry.rowStride,
                sourcePixelStrideBytes = nativeGeometry.pixelStride,
                sourceCropLeft = nativeGeometry.cropLeft,
                sourceCropTop = nativeGeometry.cropTop,
                sourceCropWidth = nativeGeometry.cropWidth,
                sourceCropHeight = nativeGeometry.cropHeight,
                outputHardwareBuffer = outputHardwareBuffer,
                outputRgba = outputRgba,
                analysisNv21 = analysisBuffer,
                frameSlotIndex = if (polling) -slot.id - 1 else slot.id,
                    maxWidth = targetMaxWidth,
                    maxHeight = targetMaxHeight,
                    sensorTimestampNs = request.sensorTimestampNs,
                    pipelineGeneration = request.config.pipelineGeneration,
                    lensShadingMap = calibration.lensMap,
                    lensShadingColumns = calibration.lensColumns,
                    lensShadingRows = calibration.lensRows,
                    lensShadingActiveRect = calibration.activeRect
                )
            } finally {
                RawPreviewTrace.end(renderTrace)
            }
            if (result?.size == 1 && result[0] == -2) {
                gpuPending = true
                pendingGpuRequests.offer(pending ?: PendingGpu(request, slot, nativeGeometry,
                    localCfaPattern, localBlackLevels.copyOf(), expectedOutput,
                    outputHardwareBuffer, outputRgba, analysisBuffer, interopEglGeneration))
                return
            }
            if (polling && (result == null || result.size < 5)) {
                // An unresolved device/fence error cannot make a GPU-owned output backing free.
                gpuPending = true
                pendingGpuRequests.offer(pending!!)
                return
            }
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
                return
            }
            if (result.getOrElse(20) { 0 } != 0) {
                RawPreviewFrameLifecycleRegistry.gpuImported(
                    request.config.pipelineGeneration,
                    request.sensorTimestampNs,
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
                // The shared scratch target may be overwritten by the next render before GLES can
                // upload it. Drop this one transition frame; the next frame receives a slot-owned
                // CPU buffer with the exact same resolution/quality contract.
                recordDrop(RawPreviewDropReason.GPU_OUTPUT_IMPORT_FAILED, request)
                RawPreviewInteropCapabilities.recordActivePath("CPU_VISIBLE_RGBA_TO_GLES_OUTPUT_IMPORT_FAILED")
                Log.w(
                    TAG,
                    "RAW_PREVIEW_GPU_OUTPUT_FALLBACK reason=native_output_import_failed " +
                        "transitionFrameDropped=true failures=$gpuOutputFailures ${outputSlotHealth()}"
                )
                return
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
                gtmBlackAnchor = result.getOrElse(569) { 6_500 } / 1_000_000.0f,
                gtmLowerMidLift = result.getOrElse(570) { 0 } / 1_000_000.0f,
                gtmContrastStrength = result.getOrElse(571) { 100_000 } / 1_000_000.0f,
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
                physicalAwbDataReady = result.getOrElse(612) { 0 } != 0 && exactFramePair != null,
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
            delivered = true
            logDiagnostics(frame)
            return
        } finally {
            if (!gpuPending || closed) releaseRequest(request)
            // A delivered frame owns the slot until FocusPeakingView uploads it. Undelivered paths
            // return it here; delivered paths returned above after onFrame.
            if (!delivered && !gpuPending) {
                slot.clearCpuRgbaBuffer()
                RawPreviewFrameLifecycleRegistry.released(
                    request.config.pipelineGeneration,
                    request.sensorTimestampNs,
                    SystemClock.elapsedRealtimeNanos(),
                    "renderer_undelivered"
                )
                returnOutputSlot(slot, "undelivered")
            }
            if (hasPendingRequest() || pendingGpuRequests.isNotEmpty()) {
                scheduleDrain(if (hasPendingRequest() && availableOutputSlots.isNotEmpty()) 0L else 2L)
            }
        }
    }

    private fun releaseOutputSlot(
        slot: OutputSlot,
        glFenceHandle: Long,
        source: ViewfinderEffectiveSource,
        generation: Int,
        sensorTimestampNs: Long,
        eglGeneration: Int
    ) {
        slot.clearCpuRgbaBuffer()
        when {
            glFenceHandle == RawPreviewFrame.GL_INTEROP_FAILED_HANDLE -> {
                gpuOutputFailures++
                recordDropForFrame(source, generation, sensorTimestampNs, RawPreviewDropReason.GL_INTEROP_FAILED)
                gpuInteropController.markFailure(
                    generation, eglGeneration, "eglimage_bind_failed", permanentForBoundary = false
                )
                // EGLImage import/bind never became a valid sampled GL owner, so this backing may
                // be retired immediately. The enclosing CPU-capable slot remains reusable.
                slot.releaseGpuBuffer()
                returnOutputSlot(slot, "gl_interop_failed")
                RawPreviewFrameLifecycleRegistry.released(
                    generation, sensorTimestampNs, SystemClock.elapsedRealtimeNanos(), "gl_interop_failed"
                )
                RawPreviewInteropCapabilities.recordActivePath("CPU_VISIBLE_RGBA_TO_GLES_GL_INTEROP_FAILED")
                Log.e(TAG, "RAW_PREVIEW_GPU_OUTPUT_DISABLED slot=${slot.id} reason=eglimage_bind_failed ${outputSlotHealth()}")
            }
            glFenceHandle < 0L -> {
                returnOutputSlot(slot, "gl_not_used")
                RawPreviewFrameLifecycleRegistry.released(
                    generation, sensorTimestampNs, SystemClock.elapsedRealtimeNanos(), "gl_not_used_cpu_copy"
                )
            }
            glFenceHandle > 0L -> {
                slot.pendingGlFenceHandle = glFenceHandle
                slot.pendingSource = source
                slot.pendingGeneration = generation
                slot.pendingSensorTimestampNs = sensorTimestampNs
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
                        generation, sensorTimestampNs, SystemClock.elapsedRealtimeNanos(), "pending_fence_state_mismatch"
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
                recordDropForFrame(source, generation, sensorTimestampNs, RawPreviewDropReason.GL_FENCE_CREATE_FAILED)
                returnOutputSlot(slot, "gl_fence_create_failed_gpu_backing_quarantined")
                RawPreviewFrameLifecycleRegistry.released(
                    generation, sensorTimestampNs, SystemClock.elapsedRealtimeNanos(), "gl_fence_create_failed"
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
        reason: RawPreviewDropReason
    ) {
        dropCounters.incrementAndGet(reason.ordinal)
        RawPreviewCadenceDiagnostics.dropped(source, generation, sensorTimestampNs, reason)
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
                    slot.pendingGlFenceHandle = 0L
                    slot.pendingSource = null
                    slot.pendingGeneration = -1
                    slot.pendingSensorTimestampNs = 0L
                    slot.pendingEglGeneration = -1
                    returnOutputSlot(slot, "gl_fence_signaled")
                    RawPreviewFrameLifecycleRegistry.released(
                        completedGeneration,
                        completedTimestampNs,
                        SystemClock.elapsedRealtimeNanos(),
                        "gl_fence_signaled"
                    )
                }
                0 -> pendingGpuOutputSlots.offer(slot)
                else -> {
                    gpuOutputFailures++
                    val failedGeneration = slot.pendingGeneration
                    val failedTimestampNs = slot.pendingSensorTimestampNs
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
                            RawPreviewDropReason.GL_FENCE_POLL_FAILED
                        )
                    }
                    slot.pendingSource = null
                    slot.pendingGeneration = -1
                    slot.pendingSensorTimestampNs = 0L
                    // Do not destroy or recycle the suspect AHB here: completion is unknown.
                    // The CPU store is independent and is immediately returned to service.
                    returnOutputSlot(slot, "gl_fence_poll_failed_gpu_backing_quarantined")
                    RawPreviewFrameLifecycleRegistry.released(
                        failedGeneration,
                        failedTimestampNs,
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
        val captureDetailNeutral = config.profileDetailAmount == 0f
        return buildString {
            append("cfa=connected")
            append(",blackWhite=").append(if (calibrationConnected) "connected" else "invalid")
            append(",demosaic=connected")
            append(",wbCcm=").append(if (calibrationConnected) "connected" else "invalid")
            append(",exposure=connected")
            append(",profileTone=connected")
            append(",profileColor=connected")
            append(",curves=").append(if (curvesConnected) "connected" else "missing")
            append(",captureDetail=").append(if (captureDetailNeutral) "intentionally_preview_neutral" else "active")
            append(",rawDenoise=removed")
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
        if (closed) return false
        val current = activeConfig
        return request.configRevision == configRevision.get() &&
            current != null &&
            current.pipelineGeneration == request.config.pipelineGeneration &&
            current.source == request.config.source
    }

    private fun releaseRequest(request: Request) {
        if (!request.released.compareAndSet(false, true)) return
        RawPreviewFrameLifecycleRegistry.inputReleased(
            request.config.pipelineGeneration,
            request.sensorTimestampNs,
            SystemClock.elapsedRealtimeNanos()
        )
        ImageUtils.releaseRawPreviewHardwareBuffer(request.retainedHardwareBuffer)
    }

    private fun logDiagnostics(frame: RawPreviewFrame? = null) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastDiagnosticsMs < DIAGNOSTIC_INTERVAL_MS) return
        val intervalMs = (now - lastDiagnosticsMs).coerceAtLeast(1L)
        val intervalFrames = (rendered - lastDiagnosticsRendered).coerceAtLeast(0L)
        val previewFps = intervalFrames * 1000.0f / intervalMs
        lastDiagnosticsMs = now
        lastDiagnosticsRendered = rendered
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
                "gtmTarget=${frame?.sceneMidtoneTarget ?: -1f} " +
                "gtmShoulder=${frame?.gtmShoulderStart ?: -1f}/${frame?.gtmShoulderStrength ?: -1f} " +
                "gtmBlack=${frame?.gtmBlackAnchor ?: -1f} gtmLowerMidLift=${frame?.gtmLowerMidLift ?: -1f} " +
                "gtmContrast=${frame?.gtmContrastStrength ?: -1f} drPressure=${frame?.gtmDynamicRangePressure ?: -1f} " +
                "ltmStrength=${frame?.ltmStrength ?: -1f} ltmLiftEv=${frame?.ltmMaxLiftEv ?: -1f} " +
                "ltmCompressEv=${frame?.ltmMaxCompressEv ?: -1f} " +
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
                "RAW_PREVIEW_DROPPED_BUSY=$droppedBusy pendingQueue=${pendingRequestCount()}/$MAX_PENDING_REQUESTS rendered=$rendered " +
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
        val idle = withTimeoutOrNull(timeoutMs) {
            while (true) {
                val idleBarrier = CompletableDeferred<Unit>()
                val queued = runCatching {
                    executor.execute { idleBarrier.complete(Unit) }
                }.isSuccess
                if (!queued) break
                idleBarrier.await()
                if (pendingGpuRequests.isEmpty()) return@withTimeoutOrNull true
                scheduleDrain()
                delay(4L)
            }
            false
        } ?: false
        if (!idle) {
            Log.w(TAG, "Timed out waiting for RAW preview worker to become idle")
        }
        return idle
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        configRevision.incrementAndGet()
        clearPendingRequests()
        frameMetadata.clear()
        exactFrameColorPairs.clear()
        // A native submit may still hold a slot after pauseAndAwaitIdle times out. Let the
        // single worker finish before touching its output backing or polling queue.
        executor.shutdown()
        backendWarmupExecutor.shutdownNow()
        if (executor.isTerminated) {
            retireClosedSlots()
        } else {
            Thread({
                while (!executor.awaitTermination(1L, TimeUnit.SECONDS)) Unit
                retireClosedSlots()
            }, "BnCamRawPreviewCloseRetirement").apply { isDaemon = true }.start()
        }
    }

    private fun metadataKey(request: Request) = RawPreviewFrameMetadataCache.Key(
        request.config.pipelineGeneration, request.config.calibrationSensorId,
        request.config.source, request.sensorTimestampNs
    )

    private fun requeueWaitingForMetadata(request: Request) {
        val newer = synchronized(pendingRequestLock) {
            val current = pendingRequest
            if (!closed && current == null) pendingRequest = request
            current
        }
        if (newer != null || closed) {
            retireQueuedRequest(request, if (closed) RawPreviewDropReason.PREVIEW_DROP_RENDERER_CLOSING
                else RawPreviewDropReason.PREVIEW_DROP_REPLACED_BY_NEWER, "metadata_wait_replaced")
        } else scheduleDrain(2L)
    }

    private fun resolveCalibration(request: Request): ResolvedCalibration {
        val config = request.config
        val match = frameMetadata.match(metadataKey(request), SystemClock.elapsedRealtimeNanos())
        val exactBlack = match.exact?.black?.takeIf { it.size >= 4 && it.take(4).all { value -> value.isFinite() && value >= 0f } }
        val rawBlack = exactBlack ?: match.trustedBlack ?: config.staticBlackLevels ?: config.blackLevels
        val blackAuthority = when {
            exactBlack != null -> "EXACT_DYNAMIC"
            match.trustedBlack != null -> "LAST_TRUSTED"
            config.staticBlackLevels != null -> "STATIC"
            else -> "UNAVAILABLE"
        }
        val exactWhite = match.exact?.white?.takeIf { it > 0 }
        val rawWhite = exactWhite ?: config.staticWhiteLevel.takeIf { it > 0 }
        val whiteAuthority = when {
            exactWhite != null -> "EXACT_DYNAMIC"
            config.staticWhiteLevel > 0 -> "STATIC"
            config.source == ViewfinderEffectiveSource.RAW10 -> "RAW10_STRUCTURAL"
            else -> "UNAVAILABLE"
        }
        val sourceWhite = when (config.source) {
            ViewfinderEffectiveSource.RAW10 -> {
                if (rawWhite != null && config.staticWhiteLevel > 1023)
                    (rawWhite * 1023f / config.staticWhiteLevel).toInt().coerceIn(1, 1023)
                else rawWhite?.coerceIn(1, 1023) ?: 1023
            }
            else -> rawWhite ?: config.whiteLevel
        }.coerceIn(1, 65535)
        val scale = if (rawBlack !== config.blackLevels &&
            config.source == ViewfinderEffectiveSource.RAW10 && config.staticWhiteLevel > 1023)
            1023f / config.staticWhiteLevel else 1f
        val black = FloatArray(4) { index ->
            ((rawBlack.getOrNull(index) ?: 0f) * scale).coerceIn(0f, sourceWhite.coerceAtLeast(2) - 1f)
        }
        val exactMap = match.exact?.takeIf { entry ->
            entry.lensMap != null && entry.lensColumns in 1..128 && entry.lensRows in 1..128 &&
                entry.lensMap.size == entry.lensColumns * entry.lensRows * 4
        }
        val applyMap = config.lensShadingAlreadyApplied == false && exactMap != null
        val lensAuthority = when {
            config.lensShadingAlreadyApplied == true -> "LENS_SHADING_RAW_ALREADY_APPLIED"
            applyMap -> "LENS_SHADING_EXACT_MAP_APPLIED"
            config.lensShadingAlreadyApplied == null -> "LENS_SHADING_METADATA_UNAVAILABLE"
            else -> "LENS_SHADING_NEUTRAL_FALLBACK"
        }
        return ResolvedCalibration(black, sourceWhite, blackAuthority, whiteAuthority,
            if (applyMap) exactMap?.lensMap?.copyOf() else null,
            if (applyMap) exactMap?.lensColumns ?: 0 else 0,
            if (applyMap) exactMap?.lensRows ?: 0 else 0,
            config.lensMapActiveRect?.copyOf(), lensAuthority)
    }

    private fun retireClosedSlots() {
        while (true) {
            val pending = pendingGpuRequests.poll() ?: break
            releaseRequest(pending.request)
        }
        allOutputSlots.forEach { slot ->
            val safeToReleaseCurrent = outputSlotLedger.state(slot.id) == RawPreviewOutputSlotState.AVAILABLE
            slot.releaseCpuBufferReferences()
            slot.retireForClose(fenceBackend, safeToReleaseCurrent)
            outputSlotLedger.close(slot.id)
        }
        availableOutputSlots.clear()
        pendingGpuOutputSlots.clear()
    }

    private companion object {
        const val TAG = "BnCamRawPreview"
        const val PREVIEW_MAX_WIDTH = RawPreviewResolutionPolicy.QUALITY_MAX_WIDTH
        const val PREVIEW_MAX_HEIGHT = RawPreviewResolutionPolicy.QUALITY_MAX_HEIGHT
        const val RGBA_BYTES_PER_PIXEL = 4
        const val OUTPUT_SLOT_COUNT = 3
        const val MAX_QUARANTINED_GPU_BACKINGS_PER_SLOT = 2
        const val MAX_PENDING_REQUESTS = 1
        const val METADATA_PAIR_GRACE_NS = 20_000_000L
        const val MAX_OUTPUT_BYTES = PREVIEW_MAX_WIDTH * PREVIEW_MAX_HEIGHT * RGBA_BYTES_PER_PIXEL
        const val MAX_ANALYSIS_NV21_BYTES = ((PREVIEW_MAX_WIDTH / 4) * (PREVIEW_MAX_HEIGHT / 4) * 3) / 2
        const val DIAGNOSTIC_INTERVAL_MS = 2_000L
        const val CONFIG_DIAGNOSTIC_INTERVAL_MS = 5_000L
    }
}
