package com.bncam.core.debug

import android.content.Context
import com.bncam.BuildConfig
import com.bncam.core.capture.CaptureRecipe
import com.bncam.core.capture.PortraitCaptureContext
import com.bncam.core.engine.ImageUtils
import com.bncam.core.isp.raw.Raw16RenderInput
import com.bncam.core.quality.DemosaicMode
import com.bncam.core.quality.RenderQualityConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Debug measurement only. Call synchronously on the existing ISP dispatcher while RAW is owned.
 * No settings writes, materialization for DNG, public media, or changes to production controls.
 * The ordinary production render follows this diagnostic work and owns its own native stats.
 */
object QualityCaptureReplay {
    data class Request(val scene: String, val lensRole: String, val repetition: Int)
    @Volatile var request: Request? = null
    fun snapshot(): Request? = request.takeIf { BuildConfig.DEBUG }

    fun export(context: Context, request: Request, input: Raw16RenderInput,
               config: RenderQualityConfig, recipe: CaptureRecipe, focus: Any, ev: Float,
               rotation: Int, portrait: PortraitCaptureContext, captureId: Long?): String {
        check(BuildConfig.DEBUG)
        val dir = File(context.filesDir, "quality/${UUID.randomUUID()}").apply { mkdirs() }
        val metadata = JSONObject(QualificationEvidence.raw(input, recipe, focus, ev))
        val rawSha = metadata.getString("rawSha256")
        FileOutputStream(File(dir, "raw.bin")).use { stream ->
            input.nativeRaw16Buffer.withDirectBuffer { buffer ->
                val view = buffer.asReadOnlyBuffer().apply { clear(); limit(input.raw16ByteCount) }
                while (view.hasRemaining()) stream.channel.write(view)
            }
        }
        File(dir, "raw-metadata.json").writeText(metadata.toString(2))
        // Canonicalize algorithm only for the common controls audit, never for actual rendering.
        val controls = JSONObject().apply {
            put("recipe", JSONObject(recipe.toJson()))
            put("render", JSONArray(config.copy(demosaic = DemosaicMode.resolveForPhase4("Malvar"))
                .debugPairs().map { (k, v) -> JSONObject().put("key", k).put("value", v) }))
            put("rawDomain", input.rawFrameInfo.toString())
            put("afHints", input.demosaicAfHints.toString())
            put("rotationDegrees", rotation)
            put("portrait", portrait.toString())
        }.toString()
        File(dir, "controls.json").writeText(controls)
        val controlsSha = QualificationEvidence.sha256(controls.toByteArray(Charsets.UTF_8))
        val outputs = JSONArray()
        for ((index, mode) in listOf("Malvar", "AMaZE", "Auto Hybrid").withIndex()) {
            val selected = config.copy(demosaic = DemosaicMode.resolveForPhase4(mode))
            var firstSha: String? = null
            // Two same-buffer renders measure determinism independently of live capture variance.
            repeat(2) { replay ->
                val start = android.os.SystemClock.elapsedRealtimeNanos()
                val result = checkNotNull(ImageUtils.renderJpegFromRaw16InputWithUltraHdrSafe(
                    input, selected, rotation, portrait)) { "Quality replay failed: $mode" }
                val duration = (android.os.SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                val stats = ImageUtils.lastMasterIspStats()
                val digest = QualificationEvidence.sha256(result.jpegBytes)
                val file = "mode-$index-replay-$replay.jpg"
                File(dir, file).writeBytes(result.jpegBytes)
                File(dir, "mode-$index-replay-$replay-native.txt").writeText(stats)
                if (replay == 0) firstSha = digest
                else check(firstSha == digest) { "Nondeterministic same-RAW quality replay: $mode; preserved $dir" }
                if (replay == 0) outputs.put(JSONObject().apply {
                    put("mode", mode); put("stage", "FINAL_JPEG"); put("domain", "ENCODED_SRGB")
                    put("coordinateFrame", "RENDERED_JPEG_PIXELS")
                    put("file", file); put("sha256", digest); put("rawSha256", rawSha)
                    put("controlsSha256", controlsSha); put("orientationDegrees", rotation)
                    put("renderMs", duration); put("nativeStats", stats)
                })
            }
        }
        val manifest = JSONObject().apply {
            put("schemaVersion", 1); put("gitRevision", BuildConfig.GIT_REVISION)
            put("captureId", captureId ?: JSONObject.NULL)
            put("cameraId", input.lensId); put("lensRole", request.lensRole)
            put("sceneId", request.scene); put("repetition", request.repetition)
            put("frameSource", config.frameSourceLabel)
            put("rawSha256", rawSha); put("rawFile", "raw.bin"); put("outputs", outputs)
            put("sameRawReplaysPerMode", 2); put("jpegDeterminism", "BITEXACT")
            put("scope", "Frozen production renderer replay; not independent live shutters per algorithm")
            put("unavailableStages", JSONArray(listOf("DIRECT_DEMOSAIC", "AFTER_WB_COLOR", "AFTER_TONE")))
        }
        File(dir, "manifest.json").writeText(manifest.toString(2))
        return dir.relativeTo(context.filesDir).path.replace('\\', '/')
    }
}
