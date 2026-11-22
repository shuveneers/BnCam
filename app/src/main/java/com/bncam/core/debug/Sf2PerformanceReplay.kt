package com.bncam.core.debug

import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.bncam.BuildConfig
import com.bncam.core.engine.ImageUtils
import com.bncam.core.isp.raw.Raw16RenderInput
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Same-master debug replay on the synchronous ISP thread. */
object Sf2PerformanceReplay {
    data class Request(val id: String, val directory: File, val runs: Int, val power: PowerManager)

    private enum class Mode(val stage: Int, val nativeName: String) {
        OFF(0, "OFF"),
        ORIGINAL(6, "GUIDED_V2_BALANCED_REFERENCE"),
        OPTIMIZED(3, "GUIDED_V2_BALANCED_DEFAULT")
    }

    private val next = AtomicReference<Request?>()

    fun request(request: Request) {
        if (BuildConfig.DEBUG) next.set(request)
    }

    fun takeRequest(): Request? = if (BuildConfig.DEBUG) next.getAndSet(null) else null

    fun run(request: Request, input: Raw16RenderInput, rawCrc32: String, render: () -> ByteArray?) {
        val report = JSONObject().apply {
            put("buildSha", BuildConfig.GIT_REVISION)
            put("id", request.id)
            put("rawMasterCrc32", rawCrc32)
            put("cameraId", input.lensId)
            put("width", input.width)
            put("height", input.height)
        }
        val rows = JSONArray()
        val metrics = listOf("vulkanDemosaicKernelMs", "fusedColourLumaGpuMs",
            "vulkanAwbCcmSynchronizationMs", "vulkanAwbCcmPersistentResidentBytes",
            "totalNativeRawIspOuterMs", "chromaMaxRgError", "chromaMaxBgError",
            "meanStructureConfidence", "hfNoiseAuthority", "extraFullFrameReadbacks")
        fun field(stats: String, key: String): String? =
            Regex("(?:^|[;{]\\s*)${Regex.escape(key)}=([^;}]+)").find(stats)?.groupValues?.get(1)
        try {
            val warmups = 2
            var originalJpeg: ByteArray? = null
            var optimizedJpeg: ByteArray? = null
            repeat(warmups + request.runs) { cycle ->
                repeat(Mode.entries.size) { offset ->
                    val mode = Mode.entries[(cycle + offset) % Mode.entries.size]
                    ImageUtils.setSf2BenchmarkStageNative(mode.stage)
                    val thermalStatus = request.power.currentThermalStatus
                    val start = SystemClock.elapsedRealtimeNanos()
                    val jpeg = checkNotNull(render()) { "JPEG render failed for ${mode.name}" }
                    val jpegRenderMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                    val stats = ImageUtils.lastMasterIspStats()
                    val luma = stats.substringAfter("baselinePhysicalLuma={", "").substringBefore('}')
                    check(field(luma, "stage") == mode.nativeName &&
                        field(luma, "gpuExecuted") == (mode != Mode.OFF).toString() &&
                        field(stats, "vulkanAwbCcmExecutionSucceeded") == "true" &&
                        (field(luma, "fusedColourLumaGpuMs")?.toDoubleOrNull() ?: 0.0) > 0.0) {
                        "Expected resident GPU path for ${mode.name}, got $luma"
                    }
                    rows.put(JSONObject().apply {
                        put("cycle", cycle)
                        put("warmup", cycle < warmups)
                        put("mode", mode.name)
                        put("jpegRenderMs", jpegRenderMs)
                        put("thermalStatus", thermalStatus)
                        put("source", field(stats.substringAfter(": "), "source"))
                        put("stage", mode.nativeName)
                        for (key in metrics) {
                            put(key, field(luma, key)?.toDoubleOrNull() ?: field(stats, key)?.toDoubleOrNull())
                        }
                    })
                    if (cycle == warmups) {
                        when (mode) {
                            Mode.ORIGINAL -> originalJpeg = jpeg
                            Mode.OPTIMIZED -> optimizedJpeg = jpeg
                            Mode.OFF -> File(request.directory, "off.jpg").writeBytes(jpeg)
                        }
                    }
                }
            }
            val original = checkNotNull(originalJpeg)
            val optimized = checkNotNull(optimizedJpeg)
            File(request.directory, "original.jpg").writeBytes(original)
            File(request.directory, "optimized.jpg").writeBytes(optimized)
            report.put("balancedJpegBytesIdentical", original.contentEquals(optimized))
            // These two validation-only passes reuse the existing color readback
            // and stay outside the timed samples and the published JPEG.
            for (mode in listOf(Mode.ORIGINAL, Mode.OPTIMIZED)) {
                ImageUtils.setSf2BenchmarkStageNative(mode.stage, readLinear = true)
                checkNotNull(render()) { "Linear comparison render failed for ${mode.name}" }
            }
            val linear = JSONObject(ImageUtils.getSf2BenchmarkLinearReportNative())
            check(linear.getDouble("maxRgbDifference") <= 1e-4 &&
                linear.getDouble("maxOpponentDifference") <= 1e-4) { "Linear comparison exceeded tolerance: $linear" }
            report.put("linearRgbComparison", linear)
            report.put("complete", true)
        } catch (error: Exception) {
            Log.e("Sf2PerformanceReplay", "Replay failed for ${request.id}", error)
            report.put("complete", false)
            report.put("error", error.message)
        } finally {
            ImageUtils.setSf2BenchmarkStageNative(-1)
        }
        runCatching {
            report.put("samples", rows)
            val temporary = File(request.directory, "results.tmp")
            temporary.writeText(report.toString(2))
            check(temporary.renameTo(File(request.directory, "results.json")))
        }.onFailure { Log.e("Sf2PerformanceReplay", "Could not write replay report", it) }
    }
}
