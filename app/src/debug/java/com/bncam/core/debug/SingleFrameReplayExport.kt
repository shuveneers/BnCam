package com.bncam.core.debug

import android.content.Context
import android.hardware.camera2.CaptureResult
import com.bncam.core.capture.CaptureRecipe
import com.bncam.core.isp.raw.Raw16RenderInput
import com.bncam.core.isp.raw.RawInputSource
import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject

/** Explicit, one-shot diagnostic export; never enabled by ordinary captures. */
object SingleFrameReplayExport {
    fun exportIfRequested(context: Context, input: Raw16RenderInput, recipe: CaptureRecipe) {
        val marker = File(context.filesDir, "single-frame-replay-request")
        if (!marker.isFile) return
        val info = input.rawFrameInfo
        val calibration = requireNotNull(input.finalCalibration)
        val result = requireNotNull(input.captureResult)
        val iso = requireNotNull(result.get(CaptureResult.SENSOR_SENSITIVITY))
        val exposure = requireNotNull(result.get(CaptureResult.SENSOR_EXPOSURE_TIME))
        val directory = File(context.filesDir, "single-frame-replay/${recipe.captureId}")
        check(directory.mkdirs())
        input.nativeRaw16Buffer.withDirectBuffer { source ->
            val view = source.duplicate()
            view.clear()
            view.limit(input.raw16ByteCount)
            FileOutputStream(File(directory, "raw.bin")).channel.use { channel ->
                while (view.hasRemaining()) channel.write(view)
            }
        }
        File(directory, "fixture.txt").writeText(buildString {
            appendLine("${input.width} ${input.height} ${info.sensorCfaPattern} $iso ${if (input.source == RawInputSource.RAW10) 0 else 1} ${exposure / 1e6} ${info.effectiveWhiteLevelInMasterUnits}")
            appendLine(info.effectiveBlackLevelPatternInMasterUnits.joinToString(" "))
            appendLine(calibration.effectiveWbGains.joinToString(" "))
            appendLine(requireNotNull(calibration.effectiveColorMatrix).joinToString(" "))
        })
        File(directory, "geometry.txt").writeText("${info.cfaOriginX} ${info.cfaOriginY} ${info.masterRowStrideBytes}\n")
        File(directory, "orientation.txt").writeText("${input.orientationDegrees}\n")
        File(directory, "recipe.json").writeText(recipe.toJson())
        File(directory, "domain.txt").writeText(info.dump())
        File(directory, "calibration.txt").writeText(calibration.debugPairs().joinToString("\n") { "${it.first}=${it.second}" })
        val metadata = JSONObject()
        metadata.put("captureId", recipe.captureId)
        metadata.put("physicalCameraId", info.physicalCameraId)
        metadata.put("lensId", input.lensId)
        metadata.put("orientationDegrees", input.orientationDegrees)
        metadata.put("captureResult", JSONObject().apply {
            result.keys.forEach { key -> put(key.name, result.get(key)?.toString() ?: JSONObject.NULL) }
        })
        File(directory, "metadata.json").writeText(metadata.toString(2))
        val noise = calibration.noiseSnapshot
        val shading = result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)
        if (noise != null && shading != null) {
            File(directory, "capture-metadata.txt").writeText(buildString {
                appendLine(noise.signalModelConfidence.toString())
                val s = noise.effectiveS; val o = noise.effectiveO
                for (i in 0..3) appendLine("${s[i]} ${o[i]}")
                appendLine("${shading.rowCount} ${shading.columnCount}")
                for (y in 0 until shading.rowCount) for (x in 0 until shading.columnCount)
                    for (c in 0..3) append("${shading.getGainFactor(c, x, y)} ")
            })
        }
        check(marker.delete())
        android.util.Log.i("BnCamReplayExport", "captureId=${recipe.captureId} directory=$directory bytes=${input.raw16ByteCount}")
    }
}
