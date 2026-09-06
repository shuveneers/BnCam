package com.bncam.core.quality

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.BlackLevelPattern
import com.bncam.core.engine.CaptureStrategy
import com.bncam.data.settings.ResolvedLensHardwareSettings
import com.bncam.data.settings.SettingsRepository
import com.bncam.data.settings.ProfileAwbSettings
import com.bncam.data.settings.ProfileIspKeys
import com.bncam.data.settings.ProfileDetailDefaults
import com.bncam.data.settings.ProfileDetailSettings
import com.bncam.data.settings.ProfileNoiseReductionDefaults
import com.bncam.data.settings.ProfileNoiseReductionSettings
import com.bncam.data.settings.ProfileSharpnessMethods
import kotlinx.coroutines.flow.first
import java.util.Locale

private const val DEFAULT_JPEG_QUALITY = 98


object ProfileCurveDefaults {
    const val TYPE_TONE = "Tone"
    const val TYPE_GAMMA = "Gamma"
    const val TYPE_SECT = "Sect"

    const val PRESET_DEFAULT = "Default"
    const val PRESET_MANUAL = "Manual"
    private const val PRESET_LINEAR = "Linear" // legacy alias; hidden because Default is already linear.
    const val PRESET_GENTLE_CONTRAST = "Gentle Contrast"
    const val PRESET_SHADOW_LIFT = "Shadow Lift"
    const val PRESET_HIGHLIGHT_ROLLOFF = "Highlight Roll-Off"
    const val PRESET_MIDTONE_FOCUS = "Midtone Focus"
    const val PRESET_SOFT_MATTE = "Soft Matte"

    private const val MANUAL_RESPONSE_FACTOR = 0.45f
    private const val PRESET_RESPONSE_FACTOR = 0.72f

    private val legacyPresetAliases: Map<String, String> = mapOf(
        "soft contrast" to PRESET_GENTLE_CONTRAST,
        "deep shadows" to PRESET_SHADOW_LIFT,
        "high dynamic" to PRESET_HIGHLIGHT_ROLLOFF,
        "cinematic muted" to PRESET_SOFT_MATTE,
        "faded film" to PRESET_SOFT_MATTE,
        "punchy s-curve" to PRESET_MIDTONE_FOCUS,
        "linear" to PRESET_DEFAULT
    )

    val presets: List<String> = listOf(
        PRESET_DEFAULT,
        PRESET_MANUAL,
        PRESET_GENTLE_CONTRAST,
        PRESET_SHADOW_LIFT,
        PRESET_HIGHLIGHT_ROLLOFF,
        PRESET_MIDTONE_FOCUS,
        PRESET_SOFT_MATTE
    )

    fun nodeCount(type: String): Int = if (type == TYPE_SECT) 7 else 16

    fun presetKey(type: String): String = when (type) {
        TYPE_GAMMA -> "curve_gamma_preset"
        TYPE_SECT -> "curve_sect_preset"
        else -> "curve_tone_preset"
    }

    fun pointKey(type: String, index: Int): String = when (type) {
        TYPE_GAMMA -> "curve_gamma_point_%02d".format(Locale.US, index)
        TYPE_SECT -> "curve_sect_point_%02d".format(Locale.US, index)
        else -> "curve_tone_point_%02d".format(Locale.US, index)
    }

    fun linearNodes(type: String): List<Float> {
        val count = nodeCount(type).coerceAtLeast(2)
        return List(count) { index -> index.toFloat() / (count - 1).toFloat() }
    }

    fun sanitizePreset(preset: String): String {
        val normalized = preset.trim()
        presets.firstOrNull { it.equals(normalized, ignoreCase = true) }?.let { return it }
        return legacyPresetAliases[normalized.lowercase(Locale.US)] ?: PRESET_DEFAULT
    }

    fun clampNode(value: Float): Float = value.coerceIn(0.0f, 1.0f)

    private fun dampAgainstLinear(type: String, nodes: List<Float>, factor: Float): List<Float> {
        val linear = linearNodes(type)
        return nodes.mapIndexed { index, raw ->
            val base = linear[index]
            val damped = base + (clampNode(raw) - base) * factor
            when (index) {
                0 -> 0.0f
                nodes.lastIndex -> 1.0f
                else -> clampNode(damped)
            }
        }
    }

    fun runtimeNodes(type: String, preset: String, nodes: List<Float>): List<Float> {
        val safePreset = sanitizePreset(preset)
        return when (safePreset) {
            PRESET_DEFAULT -> linearNodes(type)
            PRESET_MANUAL -> dampAgainstLinear(type, nodes, MANUAL_RESPONSE_FACTOR)
            else -> dampAgainstLinear(type, nodes, PRESET_RESPONSE_FACTOR)
        }
    }

    fun isRuntimeCurveActive(type: String, nodes: List<Float>): Boolean {
        val linear = linearNodes(type)
        return nodes.indices.any { index ->
            kotlin.math.abs(clampNode(nodes[index]) - linear[index]) > 0.0015f
        }
    }

    private fun toneGentleContrast(): List<Float> = listOf(0.000f, 0.052f, 0.108f, 0.168f, 0.232f, 0.301f, 0.375f, 0.454f, 0.538f, 0.623f, 0.706f, 0.784f, 0.857f, 0.924f, 0.971f, 1.000f)
    private fun toneShadowLift(): List<Float> = listOf(0.000f, 0.084f, 0.158f, 0.225f, 0.288f, 0.349f, 0.410f, 0.472f, 0.538f, 0.607f, 0.676f, 0.745f, 0.813f, 0.879f, 0.942f, 1.000f)
    private fun toneHighlightRolloff(): List<Float> = listOf(0.000f, 0.067f, 0.133f, 0.199f, 0.264f, 0.328f, 0.391f, 0.453f, 0.514f, 0.573f, 0.631f, 0.689f, 0.748f, 0.811f, 0.890f, 1.000f)
    private fun toneMidtoneFocus(): List<Float> = listOf(0.000f, 0.058f, 0.118f, 0.182f, 0.250f, 0.322f, 0.398f, 0.477f, 0.558f, 0.639f, 0.717f, 0.791f, 0.859f, 0.919f, 0.967f, 1.000f)
    private fun toneSoftMatte(): List<Float> = listOf(0.000f, 0.074f, 0.141f, 0.204f, 0.264f, 0.322f, 0.380f, 0.440f, 0.504f, 0.572f, 0.644f, 0.719f, 0.797f, 0.874f, 0.942f, 1.000f)

    private fun gammaGentleContrast(): List<Float> = listOf(0.000f, 0.049f, 0.104f, 0.163f, 0.228f, 0.299f, 0.376f, 0.458f, 0.543f, 0.627f, 0.708f, 0.784f, 0.854f, 0.920f, 0.969f, 1.000f)
    private fun gammaShadowLift(): List<Float> = listOf(0.000f, 0.090f, 0.166f, 0.231f, 0.291f, 0.349f, 0.407f, 0.467f, 0.531f, 0.599f, 0.670f, 0.741f, 0.811f, 0.879f, 0.942f, 1.000f)
    private fun gammaHighlightRolloff(): List<Float> = listOf(0.000f, 0.068f, 0.135f, 0.201f, 0.265f, 0.327f, 0.388f, 0.447f, 0.505f, 0.562f, 0.618f, 0.675f, 0.735f, 0.803f, 0.888f, 1.000f)
    private fun gammaMidtoneFocus(): List<Float> = listOf(0.000f, 0.056f, 0.115f, 0.178f, 0.245f, 0.317f, 0.394f, 0.476f, 0.562f, 0.646f, 0.723f, 0.793f, 0.855f, 0.914f, 0.964f, 1.000f)
    private fun gammaSoftMatte(): List<Float> = listOf(0.000f, 0.078f, 0.146f, 0.208f, 0.266f, 0.322f, 0.378f, 0.436f, 0.499f, 0.567f, 0.640f, 0.716f, 0.795f, 0.873f, 0.941f, 1.000f)

    private fun sectGentleContrast(): List<Float> = listOf(0.000f, 0.150f, 0.317f, 0.500f, 0.683f, 0.850f, 1.000f)
    private fun sectShadowLift(): List<Float> = listOf(0.000f, 0.185f, 0.345f, 0.500f, 0.655f, 0.818f, 1.000f)
    private fun sectHighlightRolloff(): List<Float> = listOf(0.000f, 0.167f, 0.333f, 0.500f, 0.652f, 0.792f, 1.000f)
    private fun sectMidtoneFocus(): List<Float> = listOf(0.000f, 0.158f, 0.326f, 0.500f, 0.674f, 0.842f, 1.000f)
    private fun sectSoftMatte(): List<Float> = listOf(0.000f, 0.176f, 0.338f, 0.500f, 0.662f, 0.824f, 1.000f)

    fun pointsForPreset(type: String, preset: String): List<Float> {
        val normalizedPreset = sanitizePreset(preset)
        if (normalizedPreset == PRESET_DEFAULT || normalizedPreset == PRESET_MANUAL) {
            return linearNodes(type)
        }
        val nodes = when (type) {
            TYPE_GAMMA -> when (normalizedPreset) {
                PRESET_GENTLE_CONTRAST -> gammaGentleContrast()
                PRESET_SHADOW_LIFT -> gammaShadowLift()
                PRESET_HIGHLIGHT_ROLLOFF -> gammaHighlightRolloff()
                PRESET_MIDTONE_FOCUS -> gammaMidtoneFocus()
                PRESET_SOFT_MATTE -> gammaSoftMatte()
                else -> linearNodes(TYPE_GAMMA)
            }
            TYPE_SECT -> when (normalizedPreset) {
                PRESET_GENTLE_CONTRAST -> sectGentleContrast()
                PRESET_SHADOW_LIFT -> sectShadowLift()
                PRESET_HIGHLIGHT_ROLLOFF -> sectHighlightRolloff()
                PRESET_MIDTONE_FOCUS -> sectMidtoneFocus()
                PRESET_SOFT_MATTE -> sectSoftMatte()
                else -> linearNodes(TYPE_SECT)
            }
            else -> when (normalizedPreset) {
                PRESET_GENTLE_CONTRAST -> toneGentleContrast()
                PRESET_SHADOW_LIFT -> toneShadowLift()
                PRESET_HIGHLIGHT_ROLLOFF -> toneHighlightRolloff()
                PRESET_MIDTONE_FOCUS -> toneMidtoneFocus()
                PRESET_SOFT_MATTE -> toneSoftMatte()
                else -> linearNodes(TYPE_TONE)
            }
        }
        return nodes.map { clampNode(it) }
    }
}

data class WhiteBalanceGains(
    val red: Float,
    val greenEven: Float,
    val greenOdd: Float,
    val blue: Float,
    val fromMetadata: Boolean,
    val source: String
) {
    fun toNativeArray(): FloatArray = floatArrayOf(red, greenEven, greenOdd, blue)

    fun description(): String = String.format(
        Locale.US,
        "R=%.3f, G_even=%.3f, G_odd=%.3f, B=%.3f (%s)",
        red,
        greenEven,
        greenOdd,
        blue,
        source
    )
}

data class ColorCorrectionMatrix(
    val values: FloatArray,
    val fromMetadata: Boolean,
    val source: String,
    val note: String,
    val validationPassed: Boolean = fromMetadata,
    val rejectReason: String = if (fromMetadata) "none" else note,
    val identityFallbackUsed: Boolean = !fromMetadata
) {
    fun toNativeArray(): FloatArray = if (values.size == 9) values else identityArray()

    fun description(): String = if (values.size == 9) {
        String.format(
            Locale.US,
            "[%.4f %.4f %.4f; %.4f %.4f %.4f; %.4f %.4f %.4f] (%s, validation=%s)",
            values[0], values[1], values[2],
            values[3], values[4], values[5],
            values[6], values[7], values[8],
            source,
            validationPassed.toString()
        )
    } else {
        "identity fallback ($source)"
    }

    companion object {
        fun identity(source: String, note: String): ColorCorrectionMatrix = ColorCorrectionMatrix(
            values = identityArray(),
            fromMetadata = false,
            source = source,
            note = note,
            validationPassed = false,
            rejectReason = note,
            identityFallbackUsed = true
        )

        fun identityArray(): FloatArray = floatArrayOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f
        )
    }
}

data class PostProcessingQualityConfig(
    val jpegQuality: Int
)

data class CurveRuntimeConfig(
    val tonePreset: String,
    val toneNodes: List<Float>,
    val gammaPreset: String,
    val gammaNodes: List<Float>,
    val sectionPreset: String,
    val sectionNodes: List<Float>
) {
    val toneActive: Boolean = ProfileCurveDefaults.isRuntimeCurveActive(ProfileCurveDefaults.TYPE_TONE, toneNodes)
    val gammaActive: Boolean = ProfileCurveDefaults.isRuntimeCurveActive(ProfileCurveDefaults.TYPE_GAMMA, gammaNodes)
    val sectionActive: Boolean = ProfileCurveDefaults.isRuntimeCurveActive(ProfileCurveDefaults.TYPE_SECT, sectionNodes)
    val anyActive: Boolean = toneActive || gammaActive || sectionActive

    fun debugPairs(): List<Pair<String, String>> = listOf(
        "Curve Runtime Active" to anyActive.toString(),
        "Tone Curve" to "$tonePreset active=$toneActive nodes=${toneNodes.joinToString(prefix = "[", postfix = "]") { String.format(Locale.US, "%.3f", it) }}",
        "Gamma Curve" to "$gammaPreset active=$gammaActive nodes=${gammaNodes.joinToString(prefix = "[", postfix = "]") { String.format(Locale.US, "%.3f", it) }}",
        "Section Curve" to "$sectionPreset active=$sectionActive nodes=${sectionNodes.joinToString(prefix = "[", postfix = "]") { String.format(Locale.US, "%.3f", it) }}"
    )

    companion object {
        fun linear(): CurveRuntimeConfig = CurveRuntimeConfig(
            tonePreset = ProfileCurveDefaults.PRESET_DEFAULT,
            toneNodes = ProfileCurveDefaults.linearNodes(ProfileCurveDefaults.TYPE_TONE),
            gammaPreset = ProfileCurveDefaults.PRESET_DEFAULT,
            gammaNodes = ProfileCurveDefaults.linearNodes(ProfileCurveDefaults.TYPE_GAMMA),
            sectionPreset = ProfileCurveDefaults.PRESET_DEFAULT,
            sectionNodes = ProfileCurveDefaults.linearNodes(ProfileCurveDefaults.TYPE_SECT)
        )
    }
}

data class ProfileNoiseTuning(
    val spectraEnabled: Boolean = false,
    val spectraDynamicIso: Float = SpectraProfileDefaults.DYNAMIC_ISO,
    val spectraStrength: Float = SpectraProfileDefaults.STRENGTH,
    val spectraLuma: Float = SpectraProfileDefaults.LUMA,
    val spectraChroma: Float = SpectraProfileDefaults.CHROMA,
    val spectraDetailProtection: Float = SpectraProfileDefaults.DETAIL_PROTECTION,
    val spectraLowFrequency: Float = SpectraProfileDefaults.LOW_FREQUENCY
) {
    fun sanitized(): ProfileNoiseTuning = copy(
        spectraDynamicIso = spectraDynamicIso.coerceIn(0f, 1f),
        spectraStrength = spectraStrength.coerceIn(-1f, 1f),
        spectraLuma = spectraLuma.coerceIn(-1f, 1f),
        spectraChroma = spectraChroma.coerceIn(-1f, 1f),
        spectraDetailProtection = spectraDetailProtection.coerceIn(-1f, 1f),
        spectraLowFrequency = spectraLowFrequency.coerceIn(-1f, 1f)
    )

    fun debugPairs(): List<Pair<String, String>> = listOf(
        "Profile SPECTRA Enabled" to spectraEnabled.toString(),
        "Profile SPECTRA Dynamic ISO" to String.format(Locale.US, "%.2f", spectraDynamicIso),
        "Profile SPECTRA Strength" to String.format(Locale.US, "%+.2f", spectraStrength),
        "Profile SPECTRA Luma" to String.format(Locale.US, "%+.2f", spectraLuma),
        "Profile SPECTRA Chroma" to String.format(Locale.US, "%+.2f", spectraChroma),
        "Profile SPECTRA Detail Protection" to String.format(Locale.US, "%+.2f", spectraDetailProtection),
        "Profile SPECTRA Low Frequency" to String.format(Locale.US, "%+.2f", spectraLowFrequency)
    )
}

data class ProfileNoiseReductionTuning(
    val luminance: Float = ProfileNoiseReductionDefaults.LUMINANCE,
    val luminanceDetail: Float = ProfileNoiseReductionDefaults.LUMINANCE_DETAIL,
    val luminanceContrast: Float = ProfileNoiseReductionDefaults.LUMINANCE_CONTRAST,
    val color: Float = ProfileNoiseReductionDefaults.COLOR,
    val colorDetail: Float = ProfileNoiseReductionDefaults.COLOR_DETAIL,
    val colorSmoothness: Float = ProfileNoiseReductionDefaults.COLOR_SMOOTHNESS
) {
    fun sanitized(): ProfileNoiseReductionTuning {
        val safe = ProfileNoiseReductionSettings(
            luminance, luminanceDetail, luminanceContrast, color, colorDetail, colorSmoothness
        ).sanitized()
        return copy(
            luminance = safe.luminance, luminanceDetail = safe.luminanceDetail,
            luminanceContrast = safe.luminanceContrast, color = safe.color,
            colorDetail = safe.colorDetail, colorSmoothness = safe.colorSmoothness
        )
    }

    fun debugPairs(): List<Pair<String, String>> = listOf(
        "Detail NR Luminance" to String.format(Locale.US, "%.0f", luminance * 100f),
        "Detail NR Luminance Detail" to String.format(Locale.US, "%.0f", luminanceDetail * 100f),
        "Detail NR Luminance Contrast" to String.format(Locale.US, "%.0f", luminanceContrast * 100f),
        "Detail NR Color" to String.format(Locale.US, "%.0f", color * 100f),
        "Detail NR Color Detail" to String.format(Locale.US, "%.0f", colorDetail * 100f),
        "Detail NR Color Smoothness" to String.format(Locale.US, "%.0f", colorSmoothness * 100f)
    )
}

data class ProfileColorTuning(
    val vibrance: Float = 0f,
    val saturation: Float = 0f,
    val contrast: Float = 0f
) {
    fun sanitized(): ProfileColorTuning = copy(
        vibrance = vibrance.coerceIn(-1f, 1f),
        saturation = saturation.coerceIn(-1f, 1f),
        contrast = contrast.coerceIn(-1f, 1f)
    )

    fun debugPairs(): List<Pair<String, String>> = listOf(
        "Profile Presence Vibrance" to String.format(Locale.US, "%+.2f", vibrance),
        "Profile Presence Saturation" to String.format(Locale.US, "%+.2f", saturation),
        "Live Color Contrast Offset" to String.format(Locale.US, "%+.2f", contrast)
    )
}

data class ProfileDetailTuning(
    val method: String = ProfileSharpnessMethods.NORMAL,
    val amount: Float = ProfileDetailDefaults.AMOUNT,
    val radius: Float = ProfileDetailDefaults.RADIUS,
    val detail: Float = ProfileDetailDefaults.DETAIL,
    val masking: Float = ProfileDetailDefaults.MASKING
) {
    fun sanitized(): ProfileDetailTuning {
        val safe = ProfileDetailSettings(amount, radius, detail, masking).sanitized()
        return copy(
            method = ProfileSharpnessMethods.sanitize(method),
            amount = safe.amount,
            radius = safe.radius,
            detail = safe.detail,
            masking = safe.masking
        )
    }


    fun debugPairs(): List<Pair<String, String>> = listOf(
        "Detail Sharpening Method" to method,
        "Global Sharpness" to String.format(Locale.US, "%+.2f", amount.coerceIn(-1f, 1f)),
        "Detail Sharpening Radius" to String.format(Locale.US, "%.2f", radius),
        "Detail Sharpening Detail" to String.format(Locale.US, "%.0f", detail.coerceIn(0f, 1f) * 100f),
        "Detail Sharpening Masking" to String.format(Locale.US, "%.0f", masking.coerceIn(0f, 1f) * 100f)
    )
}

data class RenderQualityPreferencesSnapshot(
    val profileId: String,
    val frameSourceFormat: Int,
    val captureMode: CaptureStrategy,
    val resolvedIspSettings: ResolvedIspSettings,
    val jpegQuality: Int,
    val demosaic: DemosaicSelection,
    val curves: CurveRuntimeConfig,
    val profileAwb: ProfileAwbSettings = ProfileAwbSettings(),
    val noiseTuning: ProfileNoiseTuning = ProfileNoiseTuning(),
    val noiseReductionTuning: ProfileNoiseReductionTuning = ProfileNoiseReductionTuning(),
    val toneTuning: ProfileToneTuning = ProfileToneTuning(),
    val colorTuning: ProfileColorTuning = ProfileColorTuning(),
    val liveViewfinderTuning: ViewfinderLiveTuningSnapshot = ViewfinderLiveTuningSnapshot(),
    val detailTuning: ProfileDetailTuning = ProfileDetailTuning(),
    val ultraHdrGainmapEnabled: Boolean = false
)

data class RenderQualityConfig(
    val profileId: String,
    val frameSourceFormat: Int,
    val frameSourceLabel: String,
    val captureMode: CaptureStrategy,
    val cfaPattern: Int,
    val cfaName: String,
    val cfaSource: String,
    val cfaSupportedForRawJpeg: Boolean,
    val blackLevels: List<Float>,
    val blackLevelSource: String,
    val whiteLevel: Int,
    val whiteLevelSource: String,
    val rawLevelNote: String,
    val whiteBalanceGains: WhiteBalanceGains,
    val colorCorrectionMatrix: ColorCorrectionMatrix,
    val post: PostProcessingQualityConfig,
    val demosaic: DemosaicSelection = DemosaicMode.resolveForPhase4(null),
    val resolvedIspSettings: ResolvedIspSettings? = null,
    val commonPostRender: CommonPostRenderResult? = null,
    val outputEncode: OutputEncodeResult? = null,
    val curves: CurveRuntimeConfig = CurveRuntimeConfig.linear(),
    val profileNoiseTuning: ProfileNoiseTuning = ProfileNoiseTuning(),
    val profileNoiseReductionTuning: ProfileNoiseReductionTuning = ProfileNoiseReductionTuning(),
    val profileToneTuning: ProfileToneTuning = ProfileToneTuning(),
    val profileColorTuning: ProfileColorTuning = ProfileColorTuning(),
    val profileDetailTuning: ProfileDetailTuning = ProfileDetailTuning(),
    val lensHardwareSettings: ResolvedLensHardwareSettings? = null,
    val finalCalibration: FinalSensorCalibration? = null,
    val ultraHdrGainmapEnabled: Boolean = false
){
    val isRawPipeline: Boolean = frameSourceFormat == ImageFormat.RAW10 || frameSourceFormat == ImageFormat.RAW_SENSOR
    val isYuvPipeline: Boolean = frameSourceFormat == ImageFormat.YUV_420_888
    val isMultiFrameMode: Boolean = captureMode != CaptureStrategy.SINGLE_FRAME_ZSL

    fun debugPairs(): List<Pair<String, String>> = listOf(
        "Profile ID" to profileId,
        "Frame Source" to frameSourceLabel,
        "Capture Mode" to captureMode.name,
        "CFA Source" to cfaSource,
        "CFA Pattern" to "$cfaPattern / $cfaName",
        "CFA Supported For RAW JPEG" to cfaSupportedForRawJpeg.toString(),
        "Black Levels" to blackLevels.joinToString(prefix = "[", postfix = "]"),
        "Black Level Source" to blackLevelSource,
        "White Level" to whiteLevel.toString(),
        "White Level Source" to whiteLevelSource,
        "RAW Level Note" to rawLevelNote,
        "White Balance Source" to whiteBalanceGains.source,
        "White Balance Gains" to whiteBalanceGains.description(),
        "Color Matrix Source" to colorCorrectionMatrix.source,
        "Color Matrix Applied" to colorCorrectionMatrix.validationPassed.toString(),
        "Color Matrix Validation Passed" to colorCorrectionMatrix.validationPassed.toString(),
        "Color Matrix Reject Reason" to colorCorrectionMatrix.rejectReason,
        "Identity Matrix Fallback Used" to colorCorrectionMatrix.identityFallbackUsed.toString(),
        "Color Matrix Values" to colorCorrectionMatrix.description(),
        "Color Matrix Note" to colorCorrectionMatrix.note,
        "Lens Hardware Config Present" to (lensHardwareSettings != null).toString(),
        "Lens Hardware Fingerprint" to (lensHardwareSettings?.fingerprint() ?: "none"),
        "JPEG Quality" to post.jpegQuality.toString(),
        "Ultra HDR Gainmap Requested" to ultraHdrGainmapEnabled.toString()
    ) + demosaic.debugPairs + curves.debugPairs() + profileNoiseTuning.debugPairs() + profileNoiseReductionTuning.debugPairs() + profileToneTuning.debugPairs() + profileColorTuning.debugPairs() + profileDetailTuning.debugPairs() + (finalCalibration?.debugPairs() ?: listOf("Sensor Calibration Summary" to "not resolved")) + commonPostRenderDebugPairs() + outputEncodeDebugPairs()

    private fun commonPostRenderDebugPairs(): List<Pair<String, String>> {
        val result = commonPostRender ?: return listOf(
            "Common Post-Render Applied" to "false",
            "Common Post-Render Scope" to "not resolved"
        )
        return result.debugPairs()
    }

    private fun outputEncodeDebugPairs(): List<Pair<String, String>> {
        val result = outputEncode ?: return listOf(
            "JPEG Encode Settings Applied" to "false",
            "Output Encode Scope" to "not resolved"
        )
        return result.debugPairs()
    }

    companion object {
        suspend fun snapshotPreferences(
            repo: SettingsRepository,
            profileId: String,
            frameSourceFormat: Int,
            captureMode: CaptureStrategy
        ): RenderQualityPreferencesSnapshot {
            val resolvedIspSettings = LibpatcherProfileResolver.resolve(
                repo = repo,
                profileId = profileId,
                captureMode = captureMode,
                frameSourceFormat = frameSourceFormat
            )
            val jpegQuality = readJpegQuality(repo, profileId)
            val demosaic = DemosaicMode.resolveForPhase4(
                repo.getProfileString(
                    profileId,
                    DemosaicMode.PROFILE_KEY,
                    DemosaicMode.DEFAULT.displayName
                ).first()
            )
            val tonePreset = loadCurvePreset(repo, profileId, ProfileCurveDefaults.TYPE_TONE)
            val gammaPreset = loadCurvePreset(repo, profileId, ProfileCurveDefaults.TYPE_GAMMA)
            val sectionPreset = loadCurvePreset(repo, profileId, ProfileCurveDefaults.TYPE_SECT)
            val profileAwb = repo.getProfileAwbSettingsFlow(profileId).first()
            val noiseTuning = ProfileNoiseTuning(
                spectraEnabled = readProfileIntOrFallback(repo, profileId, ProfileIspKeys.SPECTRA_ENABLED, 0) == 1,
                spectraDynamicIso = readProfileFloatOrFallback(repo, profileId, ProfileIspKeys.SPECTRA_DYNAMIC_ISO, SpectraProfileDefaults.DYNAMIC_ISO, 0f..1f),
                spectraStrength = SpectraProfileDefaults.STRENGTH, // calibrated master authority; legacy stored strength is intentionally ignored
                spectraLuma = readProfileFloatOrFallback(repo, profileId, ProfileIspKeys.SPECTRA_LUMA, SpectraProfileDefaults.LUMA, -1f..1f),
                spectraChroma = readProfileFloatOrFallback(repo, profileId, ProfileIspKeys.SPECTRA_CHROMA, SpectraProfileDefaults.CHROMA, -1f..1f),
                spectraDetailProtection = readProfileFloatOrFallback(repo, profileId, ProfileIspKeys.SPECTRA_DETAIL, SpectraProfileDefaults.DETAIL_PROTECTION, -1f..1f),
                spectraLowFrequency = readProfileFloatOrFallback(repo, profileId, ProfileIspKeys.SPECTRA_LOW_FREQUENCY, SpectraProfileDefaults.LOW_FREQUENCY, -1f..1f)
            ).sanitized()
            val noiseReductionTuning = ProfileNoiseReductionTuning(
                luminance = readProfileFloatOrFallback(repo, profileId, ProfileIspKeys.DETAIL_NR_LUMINANCE, ProfileNoiseReductionDefaults.LUMINANCE, 0f..1f),
                luminanceDetail = readProfileFloatOrFallback(repo, profileId, ProfileIspKeys.DETAIL_NR_LUMINANCE_DETAIL, ProfileNoiseReductionDefaults.LUMINANCE_DETAIL, 0f..1f),
                luminanceContrast = readProfileFloatOrFallback(repo, profileId, ProfileIspKeys.DETAIL_NR_LUMINANCE_CONTRAST, ProfileNoiseReductionDefaults.LUMINANCE_CONTRAST, 0f..1f),
                color = readProfileFloatOrFallback(repo, profileId, ProfileIspKeys.DETAIL_NR_COLOR, ProfileNoiseReductionDefaults.COLOR, 0f..1f),
                colorDetail = readProfileFloatOrFallback(repo, profileId, ProfileIspKeys.DETAIL_NR_COLOR_DETAIL, ProfileNoiseReductionDefaults.COLOR_DETAIL, 0f..1f),
                colorSmoothness = readProfileFloatOrFallback(repo, profileId, ProfileIspKeys.DETAIL_NR_COLOR_SMOOTHNESS, ProfileNoiseReductionDefaults.COLOR_SMOOTHNESS, 0f..1f)
            ).sanitized()

            val toneTuning = ProfileToneTuning(
                exposure = readProfileFloatOrFallback(repo, profileId, ProfileIspKeys.TONE_EXPOSURE, 0f, -1f..1f),
                highlights = readProfileFloatOrFallback(repo, profileId, ProfileIspKeys.TONE_HIGHLIGHTS, 0f, -1f..1f),
                shadows = readProfileFloatOrFallback(repo, profileId, ProfileIspKeys.TONE_SHADOWS, 0f, -1f..1f),
                whites = readProfileFloatOrFallback(repo, profileId, ProfileIspKeys.TONE_WHITES, 0f, -1f..1f),
                blacks = readProfileFloatOrFallback(repo, profileId, ProfileIspKeys.TONE_BLACKS, 0f, -1f..1f),
                contrast = readProfileFloatOrFallback(repo, profileId, ProfileIspKeys.TONE_CONTRAST, 0f, -1f..1f),
                // Profile V3 has no hidden local-tone authority. Old persisted values are
                // deliberately ignored so a removed control cannot continue changing output.
                localToneBias = 0f
            ).sanitized()
            val baseColorTuning = ProfileColorTuning(
                vibrance = readProfileFloatOrFallback(repo, profileId, ProfileIspKeys.PRESENCE_VIBRANCE, 0f, -1f..1f),
                saturation = readProfileFloatOrFallback(repo, profileId, ProfileIspKeys.PRESENCE_SATURATION, 0f, -1f..1f),
                // Profile tonal contrast is owned by ProfileToneTuning/GTM. Keep this RGB lane
                // neutral; ViewfinderLiveTuning may still apply a temporary live override.
                contrast = 0f
            ).sanitized()
            val liveViewfinderTuning = ViewfinderLiveTuning.snapshot()
            val colorTuning = liveViewfinderTuning.applyColor(baseColorTuning)
            // Live WB is a BnCam colour-pipeline override for every source. RAW applies the
            // target before demosaic; YUV converts the same absolute target into a post-HAL
            // compensation relative to the captured Camera2 AWB metadata. Never push this
            // creative/UI control into a vendor Camera2 manual-WB request.
            val effectiveProfileAwb = liveViewfinderTuning.applyRawWhiteBalance(profileAwb)
            val sharpeningMethod = ProfileSharpnessMethods.sanitize(
                repo.getProfileString(
                    profileId,
                    ProfileIspKeys.DETAIL_SHARPENING_METHOD,
                    ProfileSharpnessMethods.NORMAL
                ).first()
            )
            val normalDetailTuning = ProfileDetailTuning(
                method = sharpeningMethod,
                amount = readProfileFloatOrFallback(repo, profileId, ProfileIspKeys.DETAIL_SHARPENING_AMOUNT, ProfileDetailDefaults.AMOUNT, -1f..1f),
                radius = readProfileFloatOrFallback(repo, profileId, ProfileIspKeys.DETAIL_SHARPENING_RADIUS, ProfileDetailDefaults.RADIUS, 0f..ProfileDetailDefaults.MAX_RADIUS),
                detail = readProfileFloatOrFallback(repo, profileId, ProfileIspKeys.DETAIL_SHARPENING_DETAIL, ProfileDetailDefaults.DETAIL, 0f..1f),
                masking = readProfileFloatOrFallback(repo, profileId, ProfileIspKeys.DETAIL_SHARPENING_MASKING, ProfileDetailDefaults.MASKING, 0f..1f)
            ).sanitized()
            // Polysharp owns sharpening exclusively when selected. Its pixel backend is deliberately
            // not connected yet, so Normal Sharpness is neutralized rather than silently stacking.
            val detailTuning = if (sharpeningMethod == ProfileSharpnessMethods.POLYSHARP) {
                normalDetailTuning.copy(amount = 0f, radius = 0f, detail = 0f, masking = 0f)
            } else {
                normalDetailTuning
            }
            // Lightroom-style GTM/LTM is the sole profile tone authority. Legacy native
            // Shadow Lift / Toe Exponent / Soft Black controls have been removed end-to-end.
            return RenderQualityPreferencesSnapshot(
                profileId = profileId,
                frameSourceFormat = frameSourceFormat,
                captureMode = captureMode,
                resolvedIspSettings = resolvedIspSettings,
                jpegQuality = jpegQuality,
                demosaic = demosaic,
                curves = CurveRuntimeConfig(
                    tonePreset = tonePreset,
                    toneNodes = loadCurveNodes(
                        repo,
                        profileId,
                        ProfileCurveDefaults.TYPE_TONE,
                        tonePreset
                    ),
                    gammaPreset = gammaPreset,
                    gammaNodes = loadCurveNodes(
                        repo,
                        profileId,
                        ProfileCurveDefaults.TYPE_GAMMA,
                        gammaPreset
                    ),
                    sectionPreset = sectionPreset,
                    sectionNodes = loadCurveNodes(
                        repo,
                        profileId,
                        ProfileCurveDefaults.TYPE_SECT,
                        sectionPreset
                    )
                ),
                profileAwb = effectiveProfileAwb,
                noiseTuning = noiseTuning,
                noiseReductionTuning = noiseReductionTuning,
                toneTuning = toneTuning,
                colorTuning = colorTuning,
                liveViewfinderTuning = liveViewfinderTuning,
                detailTuning = detailTuning,
                ultraHdrGainmapEnabled = repo.ultraHdrGainmapEnabledFlow.first()
            )
        }

        suspend fun load(
            repo: SettingsRepository,
            profileId: String,
            frameSourceFormat: Int,
            captureMode: CaptureStrategy,
            characteristics: CameraCharacteristics,
            captureResult: CaptureResult?,
            lensHardwareSettings: ResolvedLensHardwareSettings? = null,
            preferenceSnapshot: RenderQualityPreferencesSnapshot? = null,
            stableAutoWhiteBalance: StableWhiteBalanceSnapshot? = null
        ): RenderQualityConfig {
            val frameSourceLabel = formatLabel(frameSourceFormat)

            // Resolve one deterministic physical sensor authority before entering the central
            // calibration resolver. This prevents a logical multi-camera TotalCaptureResult from
            // being interpreted against logical characteristics or an arbitrary physical child.
            val calibrationInput = PhysicalSensorProfileRegistry.resolveCurrentCalibrationInput(
                fallbackCharacteristics = characteristics,
                captureResult = captureResult
            )

            // 1. Haal de keiharde waarheid en UI overrides op via de nieuwe architectuur!
            val finalCal = SensorCalibrationResolver.resolve(
                lensId = lensHardwareSettings?.lensId ?: "default",
                physicalCameraId = calibrationInput.physicalCameraId,
                frameSourceFormat = frameSourceFormat,
                characteristics = calibrationInput.characteristics,
                captureResult = calibrationInput.captureResult,
                lensSettings = lensHardwareSettings,
                profileAwbSettings = preferenceSnapshot?.profileAwb
                    ?: repo.getProfileAwbSettingsFlow(profileId).first(),
                profileNoiseTuning = preferenceSnapshot?.noiseTuning,
                stableAutoWhiteBalance = stableAutoWhiteBalance
            )

            // 2. Map the central SensorCalibrationResolver output to the legacy config fields.
            // This keeps SensorCalibrationResolver as the single source of truth for RAW metadata.
            val cfaPattern = finalCal.base.cfaPattern
            val cfaSource = "CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT"
            val cfaName = finalCal.base.cfaName
            val cfaSupported = isSupportedBayerCfa(cfaPattern)

            val blackLevels = finalCal.effectiveBlackLevels.toList()
            val whiteLevel = finalCal.effectiveWhiteLevel
            val blackLevelSource = finalCal.effectiveBlackLevelSource
            val whiteLevelSource = finalCal.effectiveWhiteLevelSource
            val rawLevelNote = "SensorCalibrationResolver V2. appliedDomain=${finalCal.effectiveWhiteLevelAppliedDomain}; blackScale=${String.format(Locale.US, "%.6f", finalCal.blackLevelScaleFactor)}; whiteScale=${String.format(Locale.US, "%.6f", finalCal.effectiveWhiteLevelScaleFactor)}; sensorAuthority=${calibrationInput.authority}; physicalCameraId=${calibrationInput.physicalCameraId ?: "logical"}; deterministic=${calibrationInput.deterministic}; staticFingerprint=${calibrationInput.staticFingerprint ?: "none"}."

            val colorMatrix = ColorCorrectionMatrix(
                values = finalCal.effectiveColorMatrix ?: ColorCorrectionMatrix.identityArray(),
                fromMetadata = finalCal.effectiveColorMatrixSource.contains("CaptureResult", ignoreCase = true) ||
                    finalCal.effectiveColorMatrixSource.contains("CameraCharacteristics", ignoreCase = true),
                source = finalCal.effectiveColorMatrixSource,
                note = finalCal.effectiveColorMatrixNote,
                validationPassed = finalCal.effectiveColorMatrixApplied,
                rejectReason = finalCal.effectiveColorMatrixRejectReason,
                identityFallbackUsed = finalCal.effectiveColorMatrixIdentityFallbackUsed
            )

            val effectiveWb = finalCal.effectiveWbGains.takeIf { it.size >= 4 } ?: floatArrayOf(1f, 1f, 1f, 1f)
            val wbGains = WhiteBalanceGains(
                red = effectiveWb[0],
                greenEven = effectiveWb[1],
                greenOdd = effectiveWb[2],
                blue = effectiveWb[3],
                fromMetadata = finalCal.effectiveWbSource.contains("CaptureResult", ignoreCase = true),
                source = finalCal.effectiveWbSource
            )

            val capturedPreferences = preferenceSnapshot
                ?: snapshotPreferences(repo, profileId, frameSourceFormat, captureMode)
            require(capturedPreferences.profileId == profileId)
            require(capturedPreferences.frameSourceFormat == frameSourceFormat)
            require(capturedPreferences.captureMode == captureMode)
            val resolvedIspSettings = capturedPreferences.resolvedIspSettings

            val basePost = PostProcessingQualityConfig(
                jpegQuality = capturedPreferences.jpegQuality
            )
            val demosaic = capturedPreferences.demosaic

            val postBeforeCommon = basePost
            val commonPostRender = LibpatcherPostRenderEngine.applyCommonPostRender(
                base = postBeforeCommon,
                resolved = resolvedIspSettings
            )

            val outputEncode = LibpatcherOutputEncodeEngine.applyOutputEncode(
                base = commonPostRender.post,
                resolved = resolvedIspSettings
            )

            val curves = capturedPreferences.curves

            return RenderQualityConfig(
                profileId = profileId,
                frameSourceFormat = frameSourceFormat,
                frameSourceLabel = frameSourceLabel,
                captureMode = captureMode,
                cfaPattern = cfaPattern,
                cfaName = cfaName,
                cfaSource = cfaSource,
                cfaSupportedForRawJpeg = cfaSupported,
                blackLevels = blackLevels,
                blackLevelSource = blackLevelSource,
                whiteLevel = whiteLevel,
                whiteLevelSource = whiteLevelSource,
                rawLevelNote = rawLevelNote,
                whiteBalanceGains = wbGains,
                colorCorrectionMatrix = colorMatrix,
                post = outputEncode.post,
                demosaic = demosaic,
                resolvedIspSettings = resolvedIspSettings,
                commonPostRender = commonPostRender,
                outputEncode = outputEncode,
                curves = curves,
                profileNoiseTuning = capturedPreferences.noiseTuning,
                profileNoiseReductionTuning = capturedPreferences.noiseReductionTuning,
                profileToneTuning = capturedPreferences.toneTuning,
                profileColorTuning = capturedPreferences.colorTuning,
                profileDetailTuning = capturedPreferences.detailTuning,
                lensHardwareSettings = lensHardwareSettings,
                finalCalibration = finalCal,
                ultraHdrGainmapEnabled = capturedPreferences.ultraHdrGainmapEnabled
            )
        }

        private data class EffectiveRawLevels(
            val blackLevels: List<Int>,
            val whiteLevel: Int,
            val scaledToRaw10: Boolean,
            val note: String
        )

        private fun readBlackLevelPattern(pattern: BlackLevelPattern?): List<Int> {
            fun offset(column: Int, row: Int): Int = try {
                pattern?.getOffsetForIndex(column, row) ?: 64
            } catch (_: Throwable) {
                64
            }
            return listOf(
                offset(0, 0),
                offset(1, 0),
                offset(0, 1),
                offset(1, 1)
            )
        }

        private fun resolveEffectiveRawLevels(
            frameSourceFormat: Int,
            reportedBlackLevels: List<Int>,
            reportedWhiteLevel: Int?
        ): EffectiveRawLevels {
            val fallbackWhite = if (frameSourceFormat == ImageFormat.RAW_SENSOR) 65535 else 1023
            val reportedWhite = (reportedWhiteLevel ?: fallbackWhite).coerceAtLeast(1)

            if (frameSourceFormat == ImageFormat.RAW10 && reportedWhite > 1023) {
                val scale = 1023f / reportedWhite.toFloat()
                val scaledBlack = reportedBlackLevels.map { value ->
                    (value * scale).toInt().coerceIn(0, 1022)
                }
                return EffectiveRawLevels(
                    blackLevels = scaledBlack,
                    whiteLevel = 1023,
                    scaledToRaw10 = true,
                    note = "RAW10 uses packed 10-bit samples. Phone metadata reported white=$reportedWhite, so black/white levels were scaled into RAW10 domain with scale=${String.format(Locale.US, "%.5f", scale)}."
                )
            }

            val safeWhite = reportedWhite.coerceAtLeast(2)
            val safeBlack = reportedBlackLevels.map { it.coerceIn(0, safeWhite - 1) }
            return EffectiveRawLevels(
                blackLevels = safeBlack,
                whiteLevel = safeWhite,
                scaledToRaw10 = false,
                note = if (reportedWhiteLevel == null) {
                    "Metadata white level missing; using format fallback white=$safeWhite with black levels $safeBlack."
                } else {
                    "Using phone metadata raw levels directly for this frame source."
                }
            )
        }


        private suspend fun readProfileFloatCompat(
            repo: SettingsRepository,
            profileId: String,
            key: String,
            defaultValue: Float,
            vararg legacyKeys: String
        ): Float {
            if (DefaultIspProfile.isDisabledProfileId(profileId)) return defaultValue
            if (repo.hasProfileOverride(profileId, key)) {
                return repo.getProfileFloat(profileId, key, defaultValue).first()
            }
            for (legacyKey in legacyKeys) {
                if (repo.hasProfileOverride(profileId, legacyKey)) {
                    return repo.getProfileFloat(profileId, legacyKey, defaultValue).first()
                }
            }
            return defaultValue
        }

        private suspend fun readProfileIntOrFallback(
            repo: SettingsRepository,
            profileId: String,
            key: String,
            fallback: Int
        ): Int {
            return if (!DefaultIspProfile.isDisabledProfileId(profileId) && repo.hasProfileOverride(profileId, key)) {
                repo.getProfileInt(profileId, key, fallback).first()
            } else fallback
        }

        private suspend fun readProfileFloatOrFallback(
            repo: SettingsRepository,
            profileId: String,
            key: String,
            fallback: Float,
            range: ClosedFloatingPointRange<Float>
        ): Float {
            val value = if (!DefaultIspProfile.isDisabledProfileId(profileId) && repo.hasProfileOverride(profileId, key)) {
                repo.getProfileFloat(profileId, key, fallback).first()
            } else fallback
            return value.takeIf { it.isFinite() }?.coerceIn(range.start, range.endInclusive)
                ?: fallback.coerceIn(range.start, range.endInclusive)
        }

        private suspend fun readJpegQuality(
            repo: SettingsRepository,
            profileId: String
        ): Int {
            if (DefaultIspProfile.isDisabledProfileId(profileId)) return DEFAULT_JPEG_QUALITY
            return if (repo.hasProfileOverride(profileId, "post_jpeg_quality")) {
                repo.getProfileInt(profileId, "post_jpeg_quality", DEFAULT_JPEG_QUALITY).first().coerceIn(80, 100)
            } else {
                DEFAULT_JPEG_QUALITY
            }
        }

        private suspend fun loadCurvePreset(
            repo: SettingsRepository,
            profileId: String,
            type: String
        ): String {
            val key = ProfileCurveDefaults.presetKey(type)
            if (DefaultIspProfile.isDisabledProfileId(profileId) || !repo.hasProfileOverride(profileId, key)) {
                return DefaultIspProfile.curvePreset(type)
            }
            return ProfileCurveDefaults.sanitizePreset(repo.getProfileString(profileId, key, DefaultIspProfile.curvePreset(type)).first())
        }

        private suspend fun loadCurveNodes(
            repo: SettingsRepository,
            profileId: String,
            type: String,
            preset: String
        ): List<Float> {
            val safePreset = ProfileCurveDefaults.sanitizePreset(preset)
            if (DefaultIspProfile.isDisabledProfileId(profileId)) {
                return DefaultIspProfile.curveNodes(type)
            }
            if (safePreset != ProfileCurveDefaults.PRESET_MANUAL) {
                return ProfileCurveDefaults.runtimeNodes(type, safePreset, ProfileCurveDefaults.pointsForPreset(type, safePreset))
            }
            val manualNodes = List(ProfileCurveDefaults.nodeCount(type)) { index ->
                val key = ProfileCurveDefaults.pointKey(type, index)
                val defaultValue = DefaultIspProfile.curveNodes(type)[index]
                if (repo.hasProfileOverride(profileId, key)) {
                    ProfileCurveDefaults.clampNode(repo.getProfileFloat(profileId, key, defaultValue).first())
                } else {
                    defaultValue
                }
            }
            return ProfileCurveDefaults.runtimeNodes(type, safePreset, manualNodes)
        }

        // RAW color metadata resolution is intentionally owned by SensorCalibrationResolver.
        // Do not add a parallel resolver here; RenderQualityConfig only maps the central result.

        fun formatLabel(format: Int): String = when (format) {
            ImageFormat.RAW_SENSOR -> "RAW_SENSOR"
            ImageFormat.RAW10 -> "RAW10"
            ImageFormat.YUV_420_888 -> "YUV"
            else -> "UNKNOWN($format)"
        }

        fun cfaName(cfa: Int): String = when (cfa) {
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> "RGGB"
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> "GRBG"
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> "GBRG"
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> "BGGR"
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGB -> "RGB_NON_BAYER"
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO -> "MONO_NON_BAYER"
            else -> "UNKNOWN_OR_UNSUPPORTED"
        }

        fun isSupportedBayerCfa(cfa: Int): Boolean = when (cfa) {
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB,
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG,
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG,
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> true
            else -> false
        }

        private fun format(value: Float): String = String.format(Locale.US, "%.3f", value)
    }
}
