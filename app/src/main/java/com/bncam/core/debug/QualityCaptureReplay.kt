package com.bncam.core.debug

import android.content.Context
import android.util.Log
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
import java.util.concurrent.CancellationException

/** Debug measurement only. Call synchronously on the existing ISP dispatcher while RAW is owned.
 * No settings writes, materialization for DNG, public media, or changes to production controls.
 * The ordinary production render follows this diagnostic work and owns its own native stats.
 */
object QualityCaptureReplay {
    data class Request(
        val scene: String, val lensRole: String, val repetition: Int,
        val detailRefinement: Boolean = false
    )
    @Volatile var request: Request? = null
    fun snapshot(): Request? = request.takeIf { BuildConfig.DEBUG }

    fun export(context: Context, request: Request, input: Raw16RenderInput,
               config: RenderQualityConfig, recipe: CaptureRecipe, focus: Any, ev: Float,
               rotation: Int, portrait: PortraitCaptureContext, captureId: Long?): String? {
        check(BuildConfig.DEBUG)
        if (request.detailRefinement) {
            return runOptionalDetailRefinementReplay(
                onFailure = { Log.w("QualityCaptureReplay", "Optional JPEG comparison unavailable", it) }
            ) {
                val dir = File(context.filesDir, "quality/" + UUID.randomUUID())
                check(dir.mkdirs()) { "Unable to create JPEG comparison directory" }
                val metadata = JSONObject(QualificationEvidence.raw(input, recipe, focus, ev))
                exportDetailRefinement(dir, input, config, rotation, portrait, metadata)
                dir.relativeTo(context.filesDir).path.replace('\\', '/')
            }
        }
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

    private fun exportDetailRefinement(
        dir: File, input: Raw16RenderInput, config: RenderQualityConfig,
        rotation: Int, portrait: PortraitCaptureContext, metadata: JSONObject
    ) {
        // Exercise all three positive controls with the same moderate strengths in both renders.
        val detail = config.profileDetailTuning.copy(amount = 0.45f, detail = 0.40f, edge = 0.35f)
        val comparison = config.copy(profileDetailTuning = detail)
        val outputs = JSONObject()
        val hashes = mutableMapOf<String, String>()

        try {
            for (variant in listOf("baseline", "refined", "refined", "baseline")) {
                ImageUtils.setOriginalSingleFrameDetailForReplay(variant == "baseline")
                val start = android.os.SystemClock.elapsedRealtimeNanos()
                val result = checkNotNull(ImageUtils.renderJpegFromRaw16InputWithUltraHdrSafe(
                    input, comparison, rotation, portrait
                )) { "JPEG detail replay failed: $variant" }
                val durationMs = (android.os.SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                val sha = QualificationEvidence.sha256(result.jpegBytes)
                val previous = hashes.putIfAbsent(variant, sha)
                if (previous == null) {
                    File(dir, "$variant.jpg").writeBytes(result.jpegBytes)
                    outputs.put(variant, JSONObject().put("sha256", sha).put("firstRenderMs", durationMs)
                        .put("nativeStats", ImageUtils.lastMasterIspStats()))
                } else {
                    check(sha == previous) { "JPEG detail replay was nondeterministic: $variant" }
                    outputs.getJSONObject(variant).put("secondRenderMs", durationMs)
                }
            }
        } finally {
            ImageUtils.setOriginalSingleFrameDetailForReplay(false)
        }
        File(dir, "manifest.json").writeText(JSONObject()
            .put("gitRevision", BuildConfig.GIT_REVISION).put("cameraId", input.lensId)
            .put("frameSource", config.frameSourceLabel).put("rawSha256", metadata.getString("rawSha256"))
            .put("iso", metadata.get("effectiveIso")).put("exposureNs", metadata.get("effectiveExposureNs"))
            .put("positiveControls", JSONObject().put("sharpness", detail.amount)
                .put("detail", detail.detail).put("edge", detail.edge))
            .put("outputs", outputs).toString(2))
    }
}

internal inline fun <T> runOptionalDetailRefinementReplay(
    onFailure: (Throwable) -> Unit,
    replay: () -> T
): T? = runCatching(replay).getOrElse { failure ->
    if (failure is CancellationException || (failure !is Exception && failure !is LinkageError)) {
        throw failure
    }
    onFailure(failure)
    null
}
