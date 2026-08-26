package com.bncam.ui.screens.capture

import android.hardware.HardwareBuffer
import android.os.SystemClock
import android.util.Log
import com.bncam.core.debug.RawPreviewFirstActivationTrace
import com.bncam.core.engine.ImageUtils
import com.bncam.core.runtime.RawPreviewResolutionPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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
    val analysisNv21: ByteBuffer?,
    val analysisNv21Width: Int,
    val analysisNv21Height: Int,
    val width: Int,
    val height: Int,
    val source: ViewfinderEffectiveSource,
    val pipelineGeneration: Int,
    val sensorTimestampNs: Long,
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
    private val releaseSlot: (Long) -> Unit
) : Closeable {
    private val closed = AtomicBoolean(false)

    /** Frame was never submitted to GL, so the Vulkan-complete slot is immediately reusable. */
    override fun close() {
        if (closed.compareAndSet(false, true)) releaseSlot(GL_NOT_USED)
    }

    /** Called only after GLES sampled a GPU-resident HardwareBuffer in a submitted draw. */
    fun closeAfterGlFence(fenceHandle: Long) {
        if (closed.compareAndSet(false, true)) releaseSlot(fenceHandle)
    }

    fun closeAfterGlInteropFailure() {
        if (closed.compareAndSet(false, true)) releaseSlot(GL_INTEROP_FAILED)
    }

    private companion object {
        const val GL_NOT_USED = -1L
        const val GL_INTEROP_FAILED = -2L
    }
}

/**
 * One-pending-frame RAW viewfinder renderer. The input handle is an independently retained native
 * AHardwareBuffer reference; neither this class nor native code owns the Image/ring-buffer handle.
 */
class RawPreviewRenderer(
    private val onFrame: (RawPreviewFrame) -> Unit
) : Closeable {
    private data class Request(
        val retainedHardwareBuffer: Long,
        val sensorTimestampNs: Long,
        val config: RawPreviewRenderConfig,
        val configRevision: Long,
        val useRuntimeCrop: Boolean,
        val sourceWidth: Int,
        val sourceHeight: Int
    )

    private val executor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "BnCamRawPreview").apply { priority = Thread.NORM_PRIORITY - 1 }
    }.apply {
        removeOnCancelPolicy = true
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
    }
    // Cold Adreno pipeline compilation must not occupy the one-and-only frame drain worker.
    private val backendWarmupExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "BnCamRawPreviewWarmup").apply { priority = Thread.NORM_PRIORITY }
    }
    private val pendingRequest = AtomicReference<Request?>(null)
    private val drainScheduled = AtomicBoolean(false)
    private val configRevision = AtomicLong(0L)
    private val backendPrepareScheduled = AtomicBoolean(false)
    private val backendPrepared = AtomicBoolean(false)
    private val liveWhiteBalanceOverride = AtomicReference<FloatArray?>(null)
    private val latestOfferedSensorTimestampNs = AtomicLong(Long.MIN_VALUE)
    private class OutputSlot(val id: Int) {
        private var cpuRgba: ByteBuffer? = null
        private var analysisNv21Buffer: ByteBuffer? = null
        var hardwareBuffer: HardwareBuffer? = null
        var hardwareBufferWidth: Int = 0
        var hardwareBufferHeight: Int = 0
        var hardwareBufferUsage: Long = 0L
        var pendingGlFenceHandle: Long = 0L
        var quarantined: Boolean = false

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
            if (quarantined || usage == 0L) return null
            val current = hardwareBuffer
            if (current != null && hardwareBufferWidth == width && hardwareBufferHeight == height &&
                hardwareBufferUsage == usage
            ) {
                return current
            }
            releaseGpuBuffer()
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
    }

    private val allOutputSlots = List(OUTPUT_SLOT_COUNT) { OutputSlot(it) }
    private val availableOutputSlots = ConcurrentLinkedQueue<OutputSlot>()
    private val pendingGpuOutputSlots = ConcurrentLinkedQueue<OutputSlot>()
    private var gpuFallbackScratchRgba: ByteBuffer? = null

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
    @Volatile private var gpuResidentOutputDisabled = false
    @Volatile private var mlAnalysisRequested = false
    private var gpuOutputFailures = 0L

    init {
        allOutputSlots.forEach(availableOutputSlots::offer)
    }

    private fun ensureGpuFallbackScratchRgba(): ByteBuffer {
        return gpuFallbackScratchRgba ?: ByteBuffer.allocateDirect(MAX_OUTPUT_BYTES)
            .order(ByteOrder.nativeOrder())
            .also { gpuFallbackScratchRgba = it }
    }

    private fun RawPreviewRenderConfig.isSafeForNativePreview(): Boolean {
        if (whiteLevel <= 1) return false
        if (blackLevels.size < 4 || blackLevels.take(4).any { !it.isFinite() }) return false
        if (wbGains.size < 4 || wbGains.take(4).any { !it.isFinite() || it !in 0.25f..6.0f }) return false
        if (colorMatrix.size != 9 || colorMatrix.any { !it.isFinite() || kotlin.math.abs(it) > 16f }) return false
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
            configRevision.incrementAndGet()
            latestOfferedSensorTimestampNs.set(Long.MIN_VALUE)
            pendingRequest.getAndSet(null)?.let(::releaseRequest)
            if (config == null || config.source == ViewfinderEffectiveSource.YUV) {
                gpuFallbackScratchRgba = null
                allOutputSlots.forEach(OutputSlot::releaseCpuBufferReferences)
            }
            renderCostEmaMs = 0.0f
            glUploadCostEmaMs = 0.0f
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
        useRuntimeCrop: Boolean = true
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
        val retained = ImageUtils.retainRawPreviewHardwareBuffer(buffer)
        if (retained == 0L) {
            retainFailures++
            logDiagnostics()
            return
        }
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
            sourceWidth = buffer.width,
            sourceHeight = buffer.height
        )
        val replaced = pendingRequest.getAndSet(request)
        replaced?.let { stale ->
            droppedBusy++
            releaseRequest(stale)
        }
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
        val request = pendingRequest.getAndSet(null) ?: return
        reclaimCompletedGpuOutputSlots()
        val slot = availableOutputSlots.poll()
        if (slot == null) {
            droppedBusy++
            releaseRequest(request)
            logDiagnostics()
            if (pendingRequest.get() != null) scheduleDrain()
            return
        }

        var delivered = false
        try {
            if (!isCurrent(request)) return
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
            val gpuInteropReady = !gpuResidentOutputDisabled &&
                RawPreviewInteropCapabilities.latest?.readyForAhbEglImageInterop == true
            val outputHardwareBuffer = if (gpuInteropReady) {
                val usage = RawPreviewInteropCapabilities.latest?.vulkanOutputAhbUsage ?: 0L
                slot.ensureGpuBuffer(expectedOutput.first, expectedOutput.second, usage).also { buffer ->
                    if (buffer == null && usage != 0L) {
                        gpuOutputFailures++
                        gpuResidentOutputDisabled = true
                        RawPreviewInteropCapabilities.recordActivePath(
                            "CPU_VISIBLE_RGBA_TO_GLES_AHB_ALLOCATION_UNSUPPORTED"
                        )
                        Log.w(
                            TAG,
                            "RAW_PREVIEW_GPU_OUTPUT_DISABLED reason=ahb_allocation_unsupported " +
                                "usage=0x${usage.toString(16)} size=${expectedOutput.first}x${expectedOutput.second}"
                        )
                    }
                }
            } else {
                null
            }

            val analysisBuffer = if (mlAnalysisRequested) {
                slot.ensureAnalysisNv21Buffer().apply { clear() }
            } else {
                null
            }
            val outputRgba = if (outputHardwareBuffer != null) {
                // JNI keeps a valid CPU fallback target, but the normal AHB->EGLImage path does not
                // reserve one full RGBA host buffer per GPU output slot. Execution is serialized.
                ensureGpuFallbackScratchRgba().apply { clear() }
            } else {
                slot.ensureCpuRgbaBuffer().apply { clear() }
            }

            val effectiveWbGains = liveWhiteBalanceOverride.get()?.copyOf()
                ?: request.config.wbGains
            RawPreviewFirstActivationTrace.nativeRenderStarted(
                source = request.config.source.name,
                generation = request.config.pipelineGeneration,
                sensorTimestampNs = request.sensorTimestampNs
            )
            val result = ImageUtils.renderRawPreview(
                retainedHardwareBuffer = request.retainedHardwareBuffer,
                sourceFormat = request.config.source.imageFormat,
                cfaPattern = localCfaPattern,
                requestedDemosaicMode = request.config.demosaicMode,
                blackLevels = localBlackLevels,
                whiteLevel = request.config.whiteLevel,
                wbGains = effectiveWbGains,
                colorMatrix = request.config.colorMatrix,
                exposureGain = request.config.exposureGain,
                captureSensitivityIso = request.config.captureSensitivityIso,
                captureExposureTimeNs = request.config.captureExposureTimeNs,
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
                frameSlotIndex = slot.id,
                maxWidth = targetMaxWidth,
                maxHeight = targetMaxHeight
            )
            if (result == null || result.size < 5 || result[0] <= 0 || result[1] <= 0 || !isCurrent(request)) {
                Log.w(
                    TAG,
                    "RENDER_FAILED resultIsNull=${result == null} resultSize=${result?.size} " +
                    "w=${result?.getOrNull(0)} h=${result?.getOrNull(1)} isCurrent=${isCurrent(request)} " +
                    "activeConfig=${activeConfig?.pipelineGeneration}/${activeConfig?.source} " +
                    "reqGen=${request.config.pipelineGeneration}/${request.config.source}"
                )
                renderFailures++
                logDiagnostics()
                return
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
                gpuResidentOutputDisabled = true
                // The shared scratch target may be overwritten by the next render before GLES can
                // upload it. Drop this one transition frame; the next frame receives a slot-owned
                // CPU buffer with the exact same resolution/quality contract.
                gpuFallbackScratchRgba = null
                RawPreviewInteropCapabilities.recordActivePath("CPU_VISIBLE_RGBA_TO_GLES_OUTPUT_IMPORT_FAILED")
                Log.w(
                    TAG,
                    "RAW_PREVIEW_GPU_OUTPUT_DISABLED reason=native_output_import_failed " +
                        "transitionFrameDropped=true failures=$gpuOutputFailures"
                )
                return
            } else if (gpuResidentOutputUsed) {
                RawPreviewInteropCapabilities.recordActivePath("VULKAN_AHB_RGBA_TO_EGLIMAGE_GLES")
            }
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
                analysisNv21 = if (analysisByteCount > 0) analysisBuffer else null,
                analysisNv21Width = analysisWidth,
                analysisNv21Height = analysisHeight,
                width = result[0],
                height = result[1],
                source = request.config.source,
                pipelineGeneration = request.config.pipelineGeneration,
                sensorTimestampNs = request.sensorTimestampNs,
                rotationDegrees = request.config.rotationDegrees,
                cfaCellDecimation = result[4],
                renderTimeMs = currentRenderTimeMs,
                vulkanStagesUsed = result[3] != 0,
                directHostInputUsed = result.getOrElse(19) { 0 } != 0,
                directHardwareBufferInputUsed = result.getOrElse(20) { 0 } != 0,
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
                releaseSlot = { glFenceHandle -> releaseOutputSlot(slot, glFenceHandle) }
            )
            onFrame(frame)
            delivered = true
            logDiagnostics(frame)
            return
        } finally {
            releaseRequest(request)
            // A delivered frame owns the slot until FocusPeakingView uploads it. Undelivered paths
            // return it here; delivered paths returned above after onFrame.
            if (!delivered) {
                slot.clearCpuRgbaBuffer()
                availableOutputSlots.offer(slot)
            }
            if (pendingRequest.get() != null) scheduleDrain()
        }
    }

    private fun releaseOutputSlot(slot: OutputSlot, glFenceHandle: Long) {
        slot.clearCpuRgbaBuffer()
        when {
            glFenceHandle == -2L -> {
                gpuOutputFailures++
                gpuResidentOutputDisabled = true
                gpuFallbackScratchRgba = null
                slot.releaseGpuBuffer()
                availableOutputSlots.offer(slot)
                RawPreviewInteropCapabilities.recordActivePath("CPU_VISIBLE_RGBA_TO_GLES_GL_INTEROP_FAILED")
                Log.e(TAG, "RAW_PREVIEW_GPU_OUTPUT_DISABLED slot=${slot.id} reason=eglimage_bind_failed")
            }
            glFenceHandle < 0L -> availableOutputSlots.offer(slot)
            glFenceHandle > 0L -> {
                slot.pendingGlFenceHandle = glFenceHandle
                pendingGpuOutputSlots.offer(slot)
            }
            else -> {
                // A GPU-resident frame was sampled by GLES but no fence could be created. Never
                // recycle that backing store blindly; quarantine it and use the CPU-visible slots
                // that remain instead of risking GL/Vulkan overlap.
                slot.quarantined = true
                gpuResidentOutputDisabled = true
                gpuFallbackScratchRgba = null
                RawPreviewInteropCapabilities.recordActivePath("CPU_VISIBLE_RGBA_TO_GLES_GL_FENCE_FAILED")
                Log.e(TAG, "RAW_PREVIEW_GPU_OUTPUT_QUARANTINED slot=${slot.id} reason=gl_fence_unavailable")
            }
        }
        if (pendingRequest.get() != null) scheduleDrain()
    }

    private fun reclaimCompletedGpuOutputSlots() {
        val count = pendingGpuOutputSlots.size
        repeat(count) {
            val slot = pendingGpuOutputSlots.poll() ?: return@repeat
            val handle = slot.pendingGlFenceHandle
            val state = if (handle > 0L) ImageUtils.pollRawPreviewGlFence(handle) else -1
            when (state) {
                1 -> {
                    slot.pendingGlFenceHandle = 0L
                    if (!slot.quarantined) availableOutputSlots.offer(slot)
                }
                0 -> pendingGpuOutputSlots.offer(slot)
                else -> {
                    slot.pendingGlFenceHandle = 0L
                    slot.quarantined = true
                    gpuResidentOutputDisabled = true
                    RawPreviewInteropCapabilities.recordActivePath("CPU_VISIBLE_RGBA_TO_GLES_GL_FENCE_FAILED")
                    Log.e(TAG, "RAW_PREVIEW_GPU_OUTPUT_QUARANTINED slot=${slot.id} reason=gl_fence_poll_failed")
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
        return RawPreviewResolutionPolicy.outputDimensions(
            cropWidth = cropWidth,
            cropHeight = cropHeight
        )
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
                "commonHighlightScalePixels=${frame?.commonHighlightScalePixels ?: -1} " +
                "directHostInput=${frame?.directHostInputUsed ?: false} " +
                "directAhbInput=${frame?.directHardwareBufferInputUsed ?: false} " +
                "inputAhbFormat=${frame?.inputAhbFormat ?: 0} " +
                "inputAhbUsage=0x${(frame?.inputAhbUsage ?: 0L).toString(16)} " +
                "inputInteropStatus=${frame?.inputInteropStatus ?: 0} " +
                "gpuResidentOutput=${frame?.gpuResidentOutputUsed ?: false} " +
                "gpuOutputFailures=$gpuOutputFailures " +
                "resolutionPolicy=FIXED_QUALITY max=${PREVIEW_MAX_WIDTH}x${PREVIEW_MAX_HEIGHT} " +
                "renderEmaMs=$renderCostEmaMs glUploadEmaMs=$glUploadCostEmaMs " +
                "RAW_PREVIEW_DROPPED_BUSY=$droppedBusy rendered=$rendered " +
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
        pendingRequest.getAndSet(null)?.let(::releaseRequest)
        executor.shutdownNow()
        backendWarmupExecutor.shutdownNow()
        gpuFallbackScratchRgba = null
        allOutputSlots.forEach { slot ->
            slot.releaseCpuBufferReferences()
            if (slot.pendingGlFenceHandle > 0L) {
                ImageUtils.destroyRawPreviewGlFence(slot.pendingGlFenceHandle)
                slot.pendingGlFenceHandle = 0L
            }
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
        const val OUTPUT_SLOT_COUNT = 3
        const val MAX_OUTPUT_BYTES = PREVIEW_MAX_WIDTH * PREVIEW_MAX_HEIGHT * RGBA_BYTES_PER_PIXEL
        const val MAX_ANALYSIS_NV21_BYTES = ((PREVIEW_MAX_WIDTH / 4) * (PREVIEW_MAX_HEIGHT / 4) * 3) / 2
        const val DIAGNOSTIC_INTERVAL_MS = 2_000L
        const val CONFIG_DIAGNOSTIC_INTERVAL_MS = 5_000L
    }
}
