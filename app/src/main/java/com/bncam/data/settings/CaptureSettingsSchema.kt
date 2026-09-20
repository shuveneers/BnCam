package com.bncam.data.settings

import com.bncam.core.capture.CaptureMode
import com.bncam.core.capture.FrameOrigin
import com.bncam.core.capture.OutputPolicy

enum class CaptureSettingType { BOOLEAN, INT, FLOAT, STRING, ENUM }
enum class CaptureSettingVisibility { BASIC, PRO, LAB, HIDDEN }
enum class CaptureSettingState { ACTIVE, UNAVAILABLE, EXPOSED_INEFFECTIVE, OBSOLETE, DUPLICATE_LEGACY }

object CaptureSettingKeys {
    const val FRAME_SOURCE = "frame_source"
    const val CAPTURE_MODE = "capture_mode"
    const val OUTPUT_POLICY = "save_format"
    const val BASE_POSITION = "base_position"
    const val BASE_CANDIDATES = "base_candidates"
    const val BASE_INCLUDE_IN_MERGE = "base_include_in_merge"
    const val BASE_BIAS = "base_bias"
    const val BASE_TEMPORAL_BIAS = "base_temporal_bias"
    const val SELECTION_REQUESTED_FRAMES = "selection_req_frames"
    const val SELECTION_FRAME_BIAS = "selection_frame_bias"
    const val SELECTION_ACCEPT_ALL = "selection_accept_all"
    const val SELECTION_REJECT_DUPES = "selection_reject_dupes"
    const val SELECTION_ALIGNABLE_ONLY = "selection_alignable_only"
    const val SELECTION_DISCARD_FIRST = "selection_discard_first"
    const val SELECTION_PREFER_RECENT = "selection_prefer_recent"
    const val SELECTION_IGNORE_STALE = "selection_ignore_stale"
    const val ALIGNMENT_METHOD = "multiframe_alignment_method"
    const val FUSION_METHOD = "multiframe_fusion_method"
    const val FUSION_FRAMES_YUV = "multiframe_fusion_frames_yuv"
    const val FUSION_FRAMES_RAW10 = "multiframe_fusion_frames_raw10"
    const val FUSION_FRAMES_RAW_SENSOR = "multiframe_fusion_frames_raw_sensor"
    const val LEGACY_JPEG_FRAMES_YUV = "multiframe_jpeg_fusion_frames_yuv"
    const val LEGACY_JPEG_FRAMES_RAW10 = "multiframe_jpeg_fusion_frames_raw10"
    const val LEGACY_JPEG_FRAMES_RAW_SENSOR = "multiframe_jpeg_fusion_frames_raw_sensor"
    const val DNG_MASTER_FRAMES_RAW10 = "multiframe_dng_master_frames_raw10"
    const val DNG_MASTER_FRAMES_RAW_SENSOR = "multiframe_dng_master_frames_raw_sensor"
    const val EXPOSURE_STRATEGY = "multiframe_exposure_strategy"
    const val EXPOSURE_PRIORITY_MODE = "capture_exposure_priority_mode"
    const val SHUTTER_PRIORITY_MULTIPLIER = "capture_shutter_priority_multiplier"
    const val ISO_PRIORITY_MULTIPLIER = "capture_iso_priority_multiplier"
    const val CAPTURE_EV_BIAS = "capture_ev_bias"
    const val SHOT_BIAS_EXPOSURE = "shot_bias_exposure"
    const val SHOT_BIAS_MAX_FRAME_EXPOSURE = "shot_bias_max_frame_exposure"
    const val MERGE_SUBPIXEL = "merge_subpixel"
    const val MERGE_LINEAR_INTERPOLATION = "merge_linear_interp"
    const val MERGE_STRICTNESS = "merge_strictness"
    const val MERGE_MAX_SHIFT = "merge_max_shift"
    const val DEMOSAIC_METHOD = "demosaic_mode"
    const val JPEG_QUALITY = "post_jpeg_quality"
    const val PHONE_ASSISTANCE_SENSORS = "phone_assistance_sensors"
}

data class CaptureSettingDefinition(
    val stableKey: String,
    val displayName: String,
    val technicalDescription: String,
    val valueType: CaptureSettingType,
    val defaultValue: String,
    val validRangeOrOptions: String,
    val applicableSources: Set<FrameOrigin> = FrameOrigin.entries.toSet(),
    val applicableModes: Set<CaptureMode> = setOf(CaptureMode.SINGLE, CaptureMode.MULTI),
    val applicableOutputPolicies: Set<OutputPolicy> = OutputPolicy.entries.toSet(),
    val visibility: CaptureSettingVisibility,
    val state: CaptureSettingState,
    val requiresSessionRecreation: Boolean,
    val capturedInRecipe: Boolean,
    val appearsInTrace: Boolean,
    val migrationBehavior: String
)

data class CaptureSettingsInventory(
    val activeAndExposed: List<CaptureSettingDefinition>,
    val activeButHidden: List<CaptureSettingDefinition>,
    val exposedButIneffective: List<CaptureSettingDefinition>,
    val unavailable: List<CaptureSettingDefinition>,
    val obsolete: List<CaptureSettingDefinition>,
    val duplicates: List<CaptureSettingDefinition>,
    val requiringMigration: List<CaptureSettingDefinition>
)

/**
 * Central schema for settings that can change capture, reconstruction, publication or trace truth.
 * The broader UI catalog remains intact; this schema is the authoritative capture-facing subset.
 */
object CaptureSettingsSchema {
    private val allSources = FrameOrigin.entries.toSet()
    private val multiOnly = setOf(CaptureMode.MULTI)

    val definitions: List<CaptureSettingDefinition> = listOf(
        active(CaptureSettingKeys.FRAME_SOURCE, "Frame Source", CaptureSettingType.ENUM, "YUV", "YUV|RAW10|RAW_SENSOR", CaptureSettingVisibility.PRO, session = true),
        active(CaptureSettingKeys.CAPTURE_MODE, "Capture Mode", CaptureSettingType.ENUM, "SINGLE", "SINGLE|MULTI", CaptureSettingVisibility.PRO, session = true),
        active(CaptureSettingKeys.OUTPUT_POLICY, "Output Policy", CaptureSettingType.ENUM, "JPEG", "JPEG|JPEG_PLUS_RAW|RAW_ONLY", CaptureSettingVisibility.PRO),
        active(CaptureSettingKeys.BASE_CANDIDATES, "Candidate Count", CaptureSettingType.INT, "3", "1..source warm-buffer capacity", CaptureSettingVisibility.PRO),
        active(CaptureSettingKeys.BASE_POSITION, "Anchor Position", CaptureSettingType.ENUM, "Auto", "current Single Frame selection policy", CaptureSettingVisibility.LAB),
        active(CaptureSettingKeys.BASE_INCLUDE_IN_MERGE, "Include Anchor", CaptureSettingType.BOOLEAN, "true", "true|false", CaptureSettingVisibility.LAB),
        active(CaptureSettingKeys.BASE_BIAS, "Anchor Bias", CaptureSettingType.ENUM, "Overall Best Score", "current Single Frame bias values", CaptureSettingVisibility.LAB, modes = setOf(CaptureMode.SINGLE)),
        active(CaptureSettingKeys.SELECTION_FRAME_BIAS, "Frame Selection Bias", CaptureSettingType.ENUM, "Auto", "current Single Frame bias values", CaptureSettingVisibility.LAB, modes = setOf(CaptureMode.SINGLE)),
        active(CaptureSettingKeys.ALIGNMENT_METHOD, "Alignment Method", CaptureSettingType.ENUM, "auto", "registry selectable methods", CaptureSettingVisibility.PRO, modes = multiOnly),
        active(CaptureSettingKeys.FUSION_METHOD, "Fusion Method", CaptureSettingType.ENUM, "auto", "registry selectable methods", CaptureSettingVisibility.PRO, modes = multiOnly),
        frameCount(
            CaptureSettingKeys.FUSION_FRAMES_YUV,
            "YUV Processing Frames",
            setOf(FrameOrigin.YUV),
            "8",
            "2..${com.bncam.core.capture.FrameCapacityPolicy.maximumProcessingFrames(FrameOrigin.YUV)}"
        ),
        frameCount(
            CaptureSettingKeys.FUSION_FRAMES_RAW10,
            "RAW10 Processing Frames",
            setOf(FrameOrigin.RAW10),
            "8",
            "2..${com.bncam.core.capture.FrameCapacityPolicy.maximumProcessingFrames(FrameOrigin.RAW10)}"
        ),
        frameCount(
            CaptureSettingKeys.FUSION_FRAMES_RAW_SENSOR,
            "RAW_SENSOR Processing Frames",
            setOf(FrameOrigin.RAW_SENSOR),
            "5",
            "2..${com.bncam.core.capture.FrameCapacityPolicy.maximumProcessingFrames(FrameOrigin.RAW_SENSOR)}"
        ),
        active(CaptureSettingKeys.EXPOSURE_STRATEGY, "Exposure Strategy", CaptureSettingType.ENUM, "ETTR", "ETTR (sensor authority)", CaptureSettingVisibility.PRO),
        active(
            CaptureSettingKeys.SHOT_BIAS_EXPOSURE,
            "Shot Bias Exposure",
            CaptureSettingType.STRING,
            "Auto",
            com.bncam.core.capture.ShotBiasExposureChoice.uiValues.joinToString("|"),
            CaptureSettingVisibility.PRO
        ),
        active(
            CaptureSettingKeys.SHOT_BIAS_MAX_FRAME_EXPOSURE,
            "Max Exposure For A Frame",
            CaptureSettingType.STRING,
            "Max exposure time",
            com.bncam.core.capture.MaxFrameExposureChoice.uiValues.joinToString("|"),
            CaptureSettingVisibility.PRO
        ),
        active(CaptureSettingKeys.CAPTURE_EV_BIAS, "Capture EV Bias", CaptureSettingType.FLOAT, "0.0", "-2.0..2.0 EV", CaptureSettingVisibility.PRO),
        superseded(CaptureSettingKeys.EXPOSURE_PRIORITY_MODE, "Shot Bias legacy priority mode", CaptureSettingType.STRING, CaptureSettingKeys.SHOT_BIAS_EXPOSURE),
        superseded(CaptureSettingKeys.SHUTTER_PRIORITY_MULTIPLIER, "Shot Bias legacy shutter multiplier", CaptureSettingType.FLOAT, CaptureSettingKeys.SHOT_BIAS_EXPOSURE),
        superseded(CaptureSettingKeys.ISO_PRIORITY_MULTIPLIER, "Shot Bias legacy ISO multiplier", CaptureSettingType.FLOAT, CaptureSettingKeys.SHOT_BIAS_EXPOSURE),
        superseded(CaptureSettingKeys.DNG_MASTER_FRAMES_RAW10, "Profile DNG master frames RAW10", CaptureSettingType.INT, "App Settings → Output / app_dng_master_frames_raw"),
        superseded(CaptureSettingKeys.DNG_MASTER_FRAMES_RAW_SENSOR, "Profile DNG master frames RAW_SENSOR", CaptureSettingType.INT, "App Settings → Output / app_dng_master_frames_raw"),
        active(CaptureSettingKeys.MERGE_STRICTNESS, "Merge Strictness", CaptureSettingType.FLOAT, "0.8", "0.0..1.0", CaptureSettingVisibility.LAB, modes = multiOnly),
        active(CaptureSettingKeys.MERGE_MAX_SHIFT, "Maximum Translation", CaptureSettingType.INT, "150", "1..sensor bounded", CaptureSettingVisibility.LAB, modes = multiOnly),
        active(CaptureSettingKeys.DEMOSAIC_METHOD, "Demosaic", CaptureSettingType.ENUM, "Malvar Inspired", "Malvar Inspired|RCD Inspired|AMAZE Inspired|Auto", CaptureSettingVisibility.PRO, sources = setOf(FrameOrigin.RAW10, FrameOrigin.RAW_SENSOR)),
        active(CaptureSettingKeys.JPEG_QUALITY, "JPEG Quality", CaptureSettingType.INT, "98", "80..100", CaptureSettingVisibility.PRO, outputs = setOf(OutputPolicy.JPEG, OutputPolicy.JPEG_PLUS_RAW)),
        active(CaptureSettingKeys.PHONE_ASSISTANCE_SENSORS, "Phone Assistance Sensors", CaptureSettingType.BOOLEAN, "false", "true|false", CaptureSettingVisibility.PRO),
        unavailable(CaptureSettingKeys.BASE_TEMPORAL_BIAS, "Multi-frame Temporal Bias"),
        unavailable(CaptureSettingKeys.SELECTION_ACCEPT_ALL, "Multi-frame Accept All"),
        unavailable(CaptureSettingKeys.SELECTION_ALIGNABLE_ONLY, "Multi-frame Alignable Only"),
        unavailable(CaptureSettingKeys.SELECTION_PREFER_RECENT, "Multi-frame Prefer Recent"),
        unavailable(CaptureSettingKeys.MERGE_SUBPIXEL, "Sub-pixel Alignment Toggle"),
        unavailable(CaptureSettingKeys.MERGE_LINEAR_INTERPOLATION, "Linear Interpolation Toggle"),
        legacy(CaptureSettingKeys.LEGACY_JPEG_FRAMES_YUV, CaptureSettingKeys.FUSION_FRAMES_YUV),
        legacy(CaptureSettingKeys.LEGACY_JPEG_FRAMES_RAW10, CaptureSettingKeys.FUSION_FRAMES_RAW10),
        legacy(CaptureSettingKeys.LEGACY_JPEG_FRAMES_RAW_SENSOR, CaptureSettingKeys.FUSION_FRAMES_RAW_SENSOR)
    )

    fun definition(key: String): CaptureSettingDefinition? =
        definitions.firstOrNull { it.stableKey == key }

    fun inventory(): CaptureSettingsInventory = CaptureSettingsInventory(
        activeAndExposed = definitions.filter {
            it.state == CaptureSettingState.ACTIVE && it.visibility != CaptureSettingVisibility.HIDDEN
        },
        activeButHidden = definitions.filter {
            it.state == CaptureSettingState.ACTIVE && it.visibility == CaptureSettingVisibility.HIDDEN
        },
        exposedButIneffective = definitions.filter {
            it.state == CaptureSettingState.EXPOSED_INEFFECTIVE
        },
        unavailable = definitions.filter {
            it.state == CaptureSettingState.UNAVAILABLE
        },
        obsolete = definitions.filter { it.state == CaptureSettingState.OBSOLETE },
        duplicates = definitions.filter { it.state == CaptureSettingState.DUPLICATE_LEGACY },
        requiringMigration = definitions.filter {
            it.migrationBehavior != "none"
        }
    )

    private fun active(
        key: String,
        name: String,
        type: CaptureSettingType,
        default: String,
        range: String,
        visibility: CaptureSettingVisibility,
        session: Boolean = false,
        sources: Set<FrameOrigin> = allSources,
        modes: Set<CaptureMode> = setOf(CaptureMode.SINGLE, CaptureMode.MULTI),
        outputs: Set<OutputPolicy> = OutputPolicy.entries.toSet()
    ) = CaptureSettingDefinition(
        stableKey = key,
        displayName = name,
        technicalDescription = "$name is captured before shutter execution and validated by its typed owner.",
        valueType = type,
        defaultValue = default,
        validRangeOrOptions = range,
        applicableSources = sources,
        applicableModes = modes,
        applicableOutputPolicies = outputs,
        visibility = visibility,
        state = CaptureSettingState.ACTIVE,
        requiresSessionRecreation = session,
        capturedInRecipe = true,
        appearsInTrace = true,
        migrationBehavior = "none"
    )

    private fun frameCount(
        key: String,
        name: String,
        sources: Set<FrameOrigin>,
        default: String,
        range: String
    ) = active(
        key,
        name,
        CaptureSettingType.INT,
        default,
        range,
        CaptureSettingVisibility.PRO,
        sources = sources,
        modes = multiOnly
    )

    private fun dngFrameCount(
        key: String,
        name: String,
        sources: Set<FrameOrigin>,
        default: String,
        range: String
    ) = active(
        key,
        name,
        CaptureSettingType.INT,
        default,
        range,
        CaptureSettingVisibility.PRO,
        sources = sources,
        modes = multiOnly,
        outputs = setOf(OutputPolicy.JPEG_PLUS_RAW, OutputPolicy.RAW_ONLY)
    )

    private fun unavailable(key: String, name: String) = CaptureSettingDefinition(
        stableKey = key,
        displayName = name,
        technicalDescription = "Stored for compatibility but unavailable in production UI because the production multi-frame runner does not consume it.",
        valueType = CaptureSettingType.STRING,
        defaultValue = "legacy",
        validRangeOrOptions = "preserved",
        visibility = CaptureSettingVisibility.HIDDEN,
        state = CaptureSettingState.UNAVAILABLE,
        requiresSessionRecreation = false,
        capturedInRecipe = true,
        appearsInTrace = true,
        migrationBehavior = "preserve stored value; hide from production UI; do not claim execution"
    )

    private fun superseded(
        key: String,
        name: String,
        type: CaptureSettingType,
        replacement: String
    ) = CaptureSettingDefinition(
        stableKey = key,
        displayName = name,
        technicalDescription = "Compatibility key superseded by '$replacement'.",
        valueType = type,
        defaultValue = "legacy",
        validRangeOrOptions = "legacy",
        visibility = CaptureSettingVisibility.HIDDEN,
        state = CaptureSettingState.OBSOLETE,
        requiresSessionRecreation = false,
        capturedInRecipe = false,
        appearsInTrace = true,
        migrationBehavior = "decode for compatibility only; do not expose or execute; new writes use '$replacement'"
    )

    private fun legacy(key: String, replacement: String) = CaptureSettingDefinition(
        stableKey = key,
        displayName = "Legacy frame-count key",
        technicalDescription = "Compatibility key superseded by '$replacement'.",
        valueType = CaptureSettingType.INT,
        defaultValue = "-1",
        validRangeOrOptions = "legacy",
        visibility = CaptureSettingVisibility.HIDDEN,
        state = CaptureSettingState.DUPLICATE_LEGACY,
        requiresSessionRecreation = false,
        capturedInRecipe = false,
        appearsInTrace = true,
        migrationBehavior = "read only when '$replacement' is absent; write only '$replacement'"
    )
}
