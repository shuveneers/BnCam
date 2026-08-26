package com.bncam.core.debug

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Debug-only, process-scoped trace for the YUV -> Selected-buffer RAW first-frame transition.
 *
 * This probe is intentionally observational: it never changes routing, Camera2 ownership, Vulkan
 * lifetime, shader selection, frame selection, or display handoff. A completed trace is kept in
 * memory so the next shot debug export can include it even when logcat is unavailable (HKS).
 */
object RawPreviewFirstActivationTrace {
    data class NativeBreakdown(
        val backendMutexWaitUs: Int,
        val backendInitializationPerformed: Boolean,
        val backendInitializationUs: Int,
        val spirvLookupUs: Int,
        val descriptorLayoutUs: Int,
        val pipelineLayoutUs: Int,
        val shaderModuleUs: Int,
        val pipelineCacheMutexWaitUs: Int,
        val pipelineCachePresent: Boolean,
        val computePipelineUs: Int,
        val imageSpirvLookupUs: Int,
        val imageShaderModuleUs: Int,
        val imageComputePipelineUs: Int,
        val descriptorCommandResourcesUs: Int,
        val inputAhbProbeUs: Int,
        val outputAhbImportUs: Int,
        val commandRecordUs: Int,
        val queueMutexWaitUs: Int,
        val queueSubmitCallUs: Int,
        val fenceWaitUs: Int,
        val nativeRenderTotalUs: Int
    )

    private data class Trace(
        val id: Long,
        val startedNs: Long,
        var source: String,
        var generation: Int,
        var firstSensorTimestampNs: Long = 0L,
        val events: LinkedHashMap<String, Long> = linkedMapOf(),
        val notes: LinkedHashMap<String, String> = linkedMapOf(),
        var native: NativeBreakdown? = null,
        var completed: Boolean = false
    )

    private const val PENDING_SOURCE = "PENDING"
    private val lock = Any()
    private val nextId = AtomicLong(0L)

    @Volatile private var initialized = false
    @Volatile private var enabled = false
    private var active: Trace? = null
    private var latestCompletedReport: String? = null
    private var pendingSelectedBufferRequestNs: Long = 0L
    private var backendPrepareStartedNs: Long = 0L
    private var backendPrepareCompletedNs: Long = 0L
    private var backendPrepareSuccess: Boolean = false
    private var backendPrepareReason: String = "not_requested"

    fun initialize(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        enabled = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        initialized = true
    }

    fun backendPrepareStarted(reason: String) {
        if (!enabled) return
        synchronized(lock) {
            backendPrepareStartedNs = SystemClock.elapsedRealtimeNanos()
            backendPrepareCompletedNs = 0L
            backendPrepareSuccess = false
            backendPrepareReason = reason
        }
    }

    fun backendPrepareCompleted(reason: String, success: Boolean) {
        if (!enabled) return
        synchronized(lock) {
            if (backendPrepareStartedNs == 0L) {
                backendPrepareStartedNs = SystemClock.elapsedRealtimeNanos()
            }
            backendPrepareCompletedNs = SystemClock.elapsedRealtimeNanos()
            backendPrepareSuccess = success
            backendPrepareReason = reason
        }
    }

    fun viewfinderSettingRequested(selectedBuffer: Boolean) {
        if (!enabled) return
        synchronized(lock) {
            pendingSelectedBufferRequestNs = if (selectedBuffer) {
                SystemClock.elapsedRealtimeNanos()
            } else {
                0L
            }
        }
    }

    fun beginSelectedBuffer(
        generation: Int,
        expectedSource: String,
        producerReady: Boolean,
        preexistingWarmSensorTimestampNs: Long?
    ) {
        if (!enabled || expectedSource == "YUV") return
        val managerReceivedNs = SystemClock.elapsedRealtimeNanos()
        synchronized(lock) {
            val requestedNs = pendingSelectedBufferRequestNs
                .takeIf { it > 0L && managerReceivedNs - it in 0L..5_000_000_000L }
                ?: managerReceivedNs
            pendingSelectedBufferRequestNs = 0L
            val trace = Trace(
                id = nextId.incrementAndGet(),
                startedNs = requestedNs,
                source = expectedSource.ifBlank { PENDING_SOURCE },
                generation = generation
            )
            trace.events["01_ui_setting_event"] = requestedNs
            trace.events["01b_manager_setting_received"] = managerReceivedNs
            trace.notes["producer_ready_at_t0"] = producerReady.toString()
            trace.notes["backend_prepare_reason"] = backendPrepareReason
            val prepareStarted = backendPrepareStartedNs
            val prepareCompleted = backendPrepareCompletedNs
            trace.notes["backend_prepare_status"] = when {
                prepareCompleted > 0L && backendPrepareSuccess -> "completed_success"
                prepareCompleted > 0L -> "completed_failed"
                prepareStarted > 0L -> "in_progress"
                else -> "not_requested"
            }
            if (prepareStarted > 0L && prepareCompleted >= prepareStarted) {
                trace.notes["backend_prepare_duration_ms"] = formatMs(prepareCompleted - prepareStarted)
            }
            trace.notes["backend_prepare_completed_before_switch"] =
                (prepareCompleted > 0L && prepareCompleted <= managerReceivedNs && backendPrepareSuccess).toString()
            if (producerReady) trace.events["03_raw_producer_ready"] = managerReceivedNs
            if (preexistingWarmSensorTimestampNs != null && preexistingWarmSensorTimestampNs > 0L) {
                trace.events["04_first_usable_raw_frame"] = managerReceivedNs
                trace.notes["first_raw_frame_origin"] = "preexisting_warm_ring"
                trace.notes["first_available_raw_sensor_timestamp_ns"] = preexistingWarmSensorTimestampNs.toString()
            }
            active = trace
        }
    }

    fun effectiveSourceSelected(source: String, generation: Int) = mark(
        stage = "02_effective_raw_source_selected",
        source = source,
        generation = generation
    )

    fun producerReady(source: String, generation: Int, detail: String) = mark(
        stage = "03_raw_producer_ready",
        source = source,
        generation = generation,
        noteKey = "producer_ready_detail",
        noteValue = detail
    )

    fun rawFrameAvailable(
        source: String,
        generation: Int,
        sensorTimestampNs: Long,
        origin: String
    ) {
        mark(
            stage = "04_first_usable_raw_frame",
            source = source,
            generation = generation,
            noteKey = "first_raw_frame_origin",
            noteValue = origin
        )
        if (!enabled || sensorTimestampNs <= 0L) return
        synchronized(lock) {
            val trace = active ?: return
            if (trace.generation == generation &&
                (trace.source == PENDING_SOURCE || trace.source == source)
            ) {
                trace.notes.putIfAbsent("first_available_raw_sensor_timestamp_ns", sensorTimestampNs.toString())
            }
        }
    }

    fun configurationReady(source: String, generation: Int, bootstrap: Boolean) = mark(
        stage = "05_raw_preview_configuration_ready",
        source = source,
        generation = generation,
        noteKey = if (bootstrap) "config_first_ready_type" else "config_full_ready_seen",
        noteValue = if (bootstrap) "bootstrap" else "true"
    )

    fun rendererOffer(source: String, generation: Int, sensorTimestampNs: Long) = mark(
        stage = "05b_renderer_offer_received",
        source = source,
        generation = generation
    )

    fun nativeRenderStarted(source: String, generation: Int, sensorTimestampNs: Long) = mark(
        stage = "06_native_raw_preview_enter",
        source = source,
        generation = generation,
        sensorTimestampNs = sensorTimestampNs,
        bindSensorTimestamp = true
    )

    fun nativeRenderCompleted(
        source: String,
        generation: Int,
        sensorTimestampNs: Long,
        breakdown: NativeBreakdown
    ) {
        if (!enabled) return
        synchronized(lock) {
            val trace = active ?: return
            if (!matches(trace, source, generation, sensorTimestampNs)) return
            trace.native = breakdown
            trace.events.putIfAbsent("15_rgba_frame_ready", SystemClock.elapsedRealtimeNanos())
        }
    }

    fun kotlinPublication(source: String, generation: Int, sensorTimestampNs: Long) = mark(
        stage = "16_kotlin_ui_publication",
        source = source,
        generation = generation,
        sensorTimestampNs = sensorTimestampNs
    )

    fun displayTransitionCommitted(source: String, generation: Int, sensorTimestampNs: Long) = mark(
        stage = "17a_display_transition_commit",
        source = source,
        generation = generation,
        sensorTimestampNs = sensorTimestampNs
    )

    fun viewAccepted(source: String, generation: Int, sensorTimestampNs: Long) = mark(
        stage = "17b_view_accept",
        source = source,
        generation = generation,
        sensorTimestampNs = sensorTimestampNs
    )

    fun drawSubmitted(source: String, generation: Int, sensorTimestampNs: Long) = mark(
        stage = "17c_draw_submitted",
        source = source,
        generation = generation,
        sensorTimestampNs = sensorTimestampNs
    )

    fun displayPresented(sensorTimestampNs: Long, displayPresentMonotonicNs: Long) {
        if (!enabled || sensorTimestampNs <= 0L || displayPresentMonotonicNs <= 0L) return
        val boottimePresentNs = displayPresentMonotonicNs +
            (SystemClock.elapsedRealtimeNanos() - System.nanoTime())
        synchronized(lock) {
            val trace = active ?: return
            if (trace.firstSensorTimestampNs > 0L && trace.firstSensorTimestampNs != sensorTimestampNs) return
            trace.events.putIfAbsent("17d_first_visible_raw_frame", boottimePresentNs)
            trace.completed = true
            latestCompletedReport = render(trace)
        }
    }

    fun latestReport(): String? = synchronized(lock) {
        latestCompletedReport ?: active?.let(::render)
    }

    private fun mark(
        stage: String,
        source: String,
        generation: Int,
        sensorTimestampNs: Long = 0L,
        noteKey: String? = null,
        noteValue: String? = null,
        bindSensorTimestamp: Boolean = false
    ) {
        if (!enabled || source == "YUV") return
        val now = SystemClock.elapsedRealtimeNanos()
        synchronized(lock) {
            val trace = active ?: return
            if (!matches(trace, source, generation, sensorTimestampNs)) return
            if (trace.source == PENDING_SOURCE) trace.source = source
            if (trace.generation < 0) trace.generation = generation
            if (bindSensorTimestamp && sensorTimestampNs > 0L && trace.firstSensorTimestampNs == 0L) {
                trace.firstSensorTimestampNs = sensorTimestampNs
                trace.notes["first_rendered_raw_sensor_timestamp_ns"] = sensorTimestampNs.toString()
            }
            trace.events.putIfAbsent(stage, now)
            if (noteKey != null && noteValue != null) trace.notes.putIfAbsent(noteKey, noteValue)
        }
    }

    private fun matches(trace: Trace, source: String, generation: Int, sensorTimestampNs: Long): Boolean {
        if (trace.generation >= 0 && generation != trace.generation) return false
        if (trace.source != PENDING_SOURCE && source != trace.source) return false
        if (sensorTimestampNs > 0L && trace.firstSensorTimestampNs > 0L &&
            sensorTimestampNs != trace.firstSensorTimestampNs
        ) return false
        return true
    }

    private fun render(trace: Trace): String = buildString {
        appendLine("activation_id=${trace.id}")
        appendLine("source=${trace.source}")
        appendLine("generation=${trace.generation}")
        appendLine("completed=${trace.completed}")
        appendLine("first_sensor_timestamp_ns=${trace.firstSensorTimestampNs}")
        appendLine("timeline_clock=SystemClock.elapsedRealtimeNanos")
        trace.events.forEach { (stage, timestampNs) ->
            appendLine("$stage=${formatMs(timestampNs - trace.startedNs)} ms_from_t0")
        }
        trace.notes.forEach { (key, value) -> appendLine("$key=$value") }
        trace.native?.let { native ->
            appendLine("native_backend_mutex_wait=${formatUs(native.backendMutexWaitUs)}")
            appendLine("native_backend_initialization_performed=${native.backendInitializationPerformed}")
            appendLine("native_backend_initialization=${formatUs(native.backendInitializationUs)}")
            appendLine("native_spirv_retrieval=${formatUs(native.spirvLookupUs)}")
            appendLine("native_descriptor_layout_creation=${formatUs(native.descriptorLayoutUs)}")
            appendLine("native_pipeline_layout_creation=${formatUs(native.pipelineLayoutUs)}")
            appendLine("native_shader_module_creation=${formatUs(native.shaderModuleUs)}")
            appendLine("native_pipeline_cache_mutex_wait=${formatUs(native.pipelineCacheMutexWaitUs)}")
            appendLine("native_pipeline_cache_present=${native.pipelineCachePresent}")
            appendLine("native_vkCreateComputePipelines_legacy=${formatUs(native.computePipelineUs)}")
            appendLine("native_image_spirv_retrieval=${formatUs(native.imageSpirvLookupUs)}")
            appendLine("native_image_shader_module_creation=${formatUs(native.imageShaderModuleUs)}")
            appendLine("native_vkCreateComputePipelines_image=${formatUs(native.imageComputePipelineUs)}")
            appendLine("native_descriptor_command_resource_creation=${formatUs(native.descriptorCommandResourcesUs)}")
            appendLine("native_input_ahb_probe_import=${formatUs(native.inputAhbProbeUs)}")
            appendLine("native_output_ahb_import=${formatUs(native.outputAhbImportUs)}")
            appendLine("native_command_buffer_recording=${formatUs(native.commandRecordUs)}")
            appendLine("native_queue_mutex_wait=${formatUs(native.queueMutexWaitUs)}")
            appendLine("native_vkQueueSubmit_call=${formatUs(native.queueSubmitCallUs)}")
            appendLine("native_fence_wait=${formatUs(native.fenceWaitUs)}")
            appendLine("native_render_total=${formatUs(native.nativeRenderTotalUs)}")
        }
    }.trimEnd()

    private fun formatUs(value: Int): String = String.format(Locale.US, "%.3f ms", value / 1000.0)
    private fun formatMs(valueNs: Long): String = String.format(Locale.US, "%.3f", valueNs / 1_000_000.0)
}
