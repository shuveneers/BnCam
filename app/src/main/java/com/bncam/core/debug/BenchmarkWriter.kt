package com.bncam.core.debug

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 6 benchmark file writer. Appends per-capture ISP timing data as CSV lines
 * to a file on the app's external files directory for collection via `adb pull`.
 *
 * This is benchmark-only instrumentation and does not affect capture behavior.
 */
object BenchmarkWriter {
    private const val TAG = "BenchmarkWriter"
    private val FIELD_NAMES = listOf(
        "timestamp",
        "route",
        "format",
        "demosaicAlgorithm",
        "requestedDemosaicMode",
        "captureIndex",
        "defectCorrectionMs",
        "defectPreScanMs",
        "defectFullPassMs",
        "expensiveDefectPassSkipped",
        "greenSplitMs",
        "lensShadingMs",
        "normalizeLensWbFusedMs",
        "demosaicTimeMs",
        "demosaicSetupMs",
        "demosaicKernelMs",
        "demosaicFinalizeMs",
        "demosaicInputPrepMs",
        "demosaicWorkspaceSetupMs",
        "demosaicPureKernelMs",
        "demosaicBorderHandlingMs",
        "demosaicPostCopyMs",
        "demosaicTotalWrapperMs",
        "finalOutSpatialNrMs",
        "finalOutClampQuantMs",
        "highlightRecoveryMs",
        "toneAndVibranceMs",
        "finalOutputPassMs",
        "sharpenMs",
        "outputRotateMs",
        "jpegEncodeMs",
        "totalRawIspCoreMs",
        "allocationReuse",
        "demosaicScratchReused",
        "workingBufferBytesEstimate",
        "allocatedScratchBytesThisShot",
        "raw10UnpackMs",
        "rawSensorReadMs",
        "rawSensorToMasterRaw16Ms",
        "jniArrayLockMs",
        "metadataResolveMs",
        "rawPreprocessOuterMs",
        "rawJpegRenderCallOuterMs",
        "captureSensitivityIso",
        "captureExposureTimeNs",
        "yuvPlaneCopyMs",
        "yuvToBgrMs",
        "yuvPostProcessMs",
        "yuvRotateMs",
        "yuvJpegEncodeMs",
        "totalNativeYuvMs",
        "totalElapsedMs",
        "frameSelectTimeMs",
        "rawUnpackTimeMs"
    )

    private var captureIndex = 0

    @Synchronized
    fun record(
        context: Context,
        route: String,
        format: String,
        masterIspStats: String,
        dngMergeStats: String,
        totalElapsedMs: Double,
        frameSelectTimeMs: Double,
        rawUnpackTimeMs: Double
    ) {
        try {
            DiagnosticsAggregator.initialize(context.applicationContext)

            val parsed = parseStats(masterIspStats)
            val dngParsed = parseStats(dngMergeStats)

            captureIndex++
            val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).format(Date())

            val values = listOf(
                timestamp,
                route,
                format,
                parsed["demosaicAlgorithm"] ?: "unknown",
                parsed["requestedDemosaicMode"] ?: "unknown",
                captureIndex.toString(),
                parsed["defectCorrectionMs"] ?: "",
                parsed["defectPreScanMs"] ?: "",
                parsed["defectFullPassMs"] ?: "",
                parsed["expensiveDefectPassSkipped"] ?: "",
                parsed["greenSplitMs"] ?: "",
                parsed["lensShadingMs"] ?: "",
                parsed["normalizeLensWbFusedMs"] ?: "",
                parsed["demosaicTimeMs"] ?: "",
                parsed["demosaicSetupMs"] ?: "",
                parsed["demosaicKernelMs"] ?: "",
                parsed["demosaicFinalizeMs"] ?: "",
                parsed["demosaicInputPrepMs"] ?: "",
                parsed["demosaicWorkspaceSetupMs"] ?: "",
                parsed["demosaicPureKernelMs"] ?: "",
                parsed["demosaicBorderHandlingMs"] ?: "",
                parsed["demosaicPostCopyMs"] ?: "",
                parsed["demosaicTotalWrapperMs"] ?: "",
                parsed["finalOutSpatialNrMs"] ?: "",
                parsed["finalOutClampQuantMs"] ?: "",
                parsed["highlightRecoveryMs"] ?: "",
                parsed["toneAndVibranceMs"] ?: "",
                parsed["finalOutputPassMs"] ?: "",
                parsed["sharpenMs"] ?: "",
                parsed["outputRotateMs"] ?: "",
                parsed["jpegEncodeMs"] ?: "",
                parsed["totalRawIspCoreMs"] ?: "",
                parsed["allocationReuse"] ?: "",
                parsed["demosaicScratchReused"] ?: "",
                parsed["workingBufferBytesEstimate"] ?: "",
                parsed["allocatedScratchBytesThisShot"] ?: "",
                parsed["raw10UnpackMs"] ?: dngParsed["raw10UnpackMs"] ?: "",
                parsed["rawSensorReadMs"] ?: dngParsed["rawSensorReadMs"] ?: "",
                parsed["rawSensorToMasterRaw16Ms"] ?: dngParsed["rawSensorToMasterRaw16Ms"] ?: "",
                parsed["jniArrayLockMs"] ?: "",
                parsed["metadataResolveMs"] ?: "",
                parsed["rawPreprocessOuterMs"] ?: "",
                parsed["rawJpegRenderCallOuterMs"] ?: "",
                parsed["captureSensitivityIso"] ?: "",
                parsed["captureExposureTimeNs"] ?: "",
                parsed["yuvPlaneCopyMs"] ?: "",
                parsed["yuvToBgrMs"] ?: "",
                parsed["yuvPostProcessMs"] ?: "",
                parsed["yuvRotateMs"] ?: "",
                parsed["yuvJpegEncodeMs"] ?: "",
                parsed["totalNativeYuvMs"] ?: "",
                String.format(Locale.US, "%.3f", totalElapsedMs),
                String.format(Locale.US, "%.3f", frameSelectTimeMs),
                String.format(Locale.US, "%.3f", rawUnpackTimeMs)
            ).joinToString(",")

            val fields = values.split(',')
            val readable = FIELD_NAMES.zip(fields).joinToString("\n") { (key, value) -> "$key=$value" }
            DiagnosticsAggregator.record(
                stream = DiagnosticsAggregator.Stream.PERFORMANCE,
                scope = "BENCHMARK #$captureIndex",
                section = "ISP BENCHMARK",
                content = readable
            )

            Log.i(TAG, "BENCHMARK_RECORD: $values")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write benchmark record: ${e.message}")
        }
    }

    @Synchronized
    fun clear(context: Context) {
        DiagnosticsAggregator.initialize(context.applicationContext)
        captureIndex = 0
        DiagnosticsAggregator.record(
            stream = DiagnosticsAggregator.Stream.PERFORMANCE,
            scope = "SESSION",
            section = "ISP BENCHMARK",
            content = "benchmarkCounterReset=true"
        )
        Log.i(TAG, "Benchmark counter reset")
    }

    private fun parseStats(stats: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (pair in stats.split(";")) {
            val eq = pair.indexOf('=')
            if (eq > 0 && eq < pair.lastIndex) {
                result[pair.substring(0, eq).trim()] = pair.substring(eq + 1).trim()
            }
        }
        return result
    }
}
