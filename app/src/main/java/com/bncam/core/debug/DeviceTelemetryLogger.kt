package com.bncam.core.debug

import android.content.Context
import android.util.Log

/**
 * Structured device telemetry producer. File publication is owned exclusively by
 * [DiagnosticsAggregator]; camera/ISP call sites remain unaware of export layout.
 */
object DeviceTelemetryLogger {
    private const val TAG = "DeviceTelemetry"

    fun initialize(context: Context) {
        DiagnosticsAggregator.initialize(context.applicationContext)
        logEvent("TELEMETRY_INITIALIZED", "centralDiagnostics=true")
    }

    fun logEvent(event: String, detail: String) {
        Log.i(TAG, "[$event] $detail")
        DiagnosticsAggregator.record(
            stream = DiagnosticsAggregator.Stream.CAMERA,
            scope = "SESSION",
            section = "DEVICE TELEMETRY / $event",
            content = detail
        )
    }

    fun getTraceFilePath(context: Context): String {
        DiagnosticsAggregator.initialize(context.applicationContext)
        return DiagnosticsAggregator.streamPath(DiagnosticsAggregator.Stream.CAMERA).orEmpty()
    }
}
