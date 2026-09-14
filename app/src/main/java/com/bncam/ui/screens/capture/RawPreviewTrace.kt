package com.bncam.ui.screens.capture

import android.os.Trace
import java.util.concurrent.atomic.AtomicLong

/** Lightweight sampled Perfetto/atrace sections for the RAW live-preview critical path. */
object RawPreviewTrace {
    private val imageAvailableCounter = AtomicLong(0L)
    private val ringHandoffCounter = AtomicLong(0L)
    private val renderCounter = AtomicLong(0L)
    private val glHandoffCounter = AtomicLong(0L)
    private val drawCounter = AtomicLong(0L)

    fun beginImageAvailable(): Boolean = beginSampled("BnCam_RAW_ImageAvailable", imageAvailableCounter)
    fun beginRingHandoff(): Boolean = beginSampled("BnCam_RAW_RingHandoff", ringHandoffCounter)
    fun beginRender(): Boolean = beginSampled("BnCam_RAW_Render", renderCounter)
    fun beginGlHandoff(): Boolean = beginSampled("BnCam_RAW_GL_Handoff", glHandoffCounter)
    fun beginDraw(): Boolean = beginSampled("BnCam_RAW_Draw", drawCounter)

    fun end(active: Boolean) {
        if (active) Trace.endSection()
    }

    private fun beginSampled(name: String, counter: AtomicLong): Boolean {
        val ordinal = counter.incrementAndGet()
        // Trace the first few frames so startup is always visible, then one in eight steady-state
        // frames so diagnostics do not become the performance problem they are measuring.
        val sampled = ordinal <= STARTUP_TRACE_FRAMES || ordinal % STEADY_STATE_SAMPLE_DIVISOR == 0L
        if (sampled) Trace.beginSection(name)
        return sampled
    }

    private const val STARTUP_TRACE_FRAMES = 8L
    private const val STEADY_STATE_SAMPLE_DIVISOR = 8L
}
