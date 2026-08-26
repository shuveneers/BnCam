package com.bncam.core.quality

import com.bncam.core.engine.CaptureStrategy

data class LibpatcherSectionDef(
    val id: String,
    val title: String,
    val description: String,
    val topics: List<LibpatcherTopicDef>
)

data class LibpatcherTopicDef(
    val id: String,
    val title: String,
    val description: String,
    val tags: Set<String>,
    val groups: List<LibpatcherGroupDef>,
    val customScreen: String? = null
)

data class LibpatcherGroupDef(
    val title: String,
    val controls: List<LibpatcherControlDef>
)

data class LibpatcherControlDef(
    val key: String,
    val title: String,
    val kind: LibpatcherControlKind,
    val tags: Set<String> = emptySet(),
    val description: String = "",
    val defaultValue: String = "",
    val enumOptions: List<String> = emptyList()
)

enum class LibpatcherControlKind {
    BASELINE_SLIDER,
    OPTIONAL_SLIDER,
    TOGGLE,
    ENUM,
    READ_ONLY,
    ACTION
}

enum class LibpatcherRuntimeScope {
    COMMON_RENDER,
    RAW_DEMOSAIC,
    OUTPUT_ENCODE,
    NOT_RUNTIME_ISP
}

data class LibpatcherControlRef(
    val section: LibpatcherSectionDef,
    val topic: LibpatcherTopicDef,
    val group: LibpatcherGroupDef,
    val control: LibpatcherControlDef,
    val runtimeScope: LibpatcherRuntimeScope
) {
    val key: String get() = control.key
    val isPersistent: Boolean get() = control.kind != LibpatcherControlKind.READ_ONLY && control.kind != LibpatcherControlKind.ACTION
}

object LibpatcherSettingsCatalog {
    const val CUSTOM_CURVE_MODULATION = "curve_modulation"

    const val KEY_CHROMA_SUPPRESS = "noise_chroma_suppress"
    const val KEY_LUMA_SUPPRESS = "noise_luma_suppress"
    const val KEY_SPATIAL_SUPPRESS = "noise_spatial_suppress"
    const val KEY_TEMPORAL_SUPPRESS = "noise_temporal_suppress"
    const val KEY_MOTION_CORRECT = "noise_motion_correct"
    const val KEY_POLY_SHARP_SMALL = "poly_sharp_small"
    const val KEY_POLY_SHARP_MEDIUM = "poly_sharp_medium"
    const val KEY_POLY_SHARP_LARGE = "poly_sharp_large"
    const val KEY_SHARP_GAIN_MACRO = "poly_sharp_gain_macro"
    const val KEY_SHARP_GAIN_MICRO = "poly_sharp_gain_micro"
    const val KEY_SOFT_SHARP = "soft_sharp"
    const val KEY_SHARP_EDGE = "sharp_edge"
    const val KEY_HALO_PROTECTION = "halo_protection"
    const val KEY_DEMOSAIC_MODE = DemosaicMode.PROFILE_KEY

    val sections: List<LibpatcherSectionDef> = scopeSectionKeys(
        listOf(
            section(
                id = "isp_tuning",
                title = "ISP Tuning",
                description = "Profile-scoped tone and curve controls used by the production renderer.",
                topics = listOf(
                    topic(
                        id = "tone",
                        title = "Tone Curve",
                        description = "Lightroom-style creative tone curve.",
                        tags = setOf("ALL"),
                        groups = emptyList(),
                        customScreen = CUSTOM_CURVE_MODULATION
                    )
                )
            ),
            section(
                id = "raw_processing",
                title = "RAW Processing",
                description = "Profile-scoped processing for RAW10 and RAW_SENSOR JPEG output.",
                topics = listOf(
                    topic(
                        id = "raw_demosaic",
                        title = "Demosaic",
                        description = "Converts the normalized Bayer mosaic to scene-linear RGB.",
                        tags = setOf("RAW"),
                        groups = listOf(
                            group(
                                "Bayer interpolation",
                                enumControl(
                                    "Demosaic mode",
                                    "RAW",
                                    defaultValue = DemosaicMode.DEFAULT.displayName,
                                    options = DemosaicMode.USER_ORDER.map { it.displayName },
                                    description = "Select BnCam's Bayer reconstruction route. Auto evaluates the available Inspired demosaics scene-adaptively."
                                )
                            )
                        )
                    )
                )
            ),
            section(
                id = "output_encode",
                title = "Output Encode",
                description = "Profile-scoped controls that are wired into the final JPEG encoder.",
                topics = listOf(
                    topic(
                        id = "jpeg_output",
                        title = "JPEG Output",
                        description = "Controls final JPEG encoding only. RAW/DNG payloads are not affected.",
                        tags = setOf("ALL"),
                        groups = listOf(
                            group(
                                "Output Quality",
                                real("JPEG Quality", "ALL")
                            )
                        )
                    )
                )
            )
        )
    )

    fun allControlRefs(): List<LibpatcherControlRef> {
        return sections.flatMap { section ->
            section.topics.flatMap { topic ->
                topic.groups.flatMap { group ->
                    group.controls.map { control ->
                        LibpatcherControlRef(
                            section = section,
                            topic = topic,
                            group = group,
                            control = control,
                            runtimeScope = runtimeScopeFor(control)
                        )
                    }
                }
            }
        }
    }

    fun runtimeIspControlRefs(): List<LibpatcherControlRef> {
        return allControlRefs().filter { it.isPersistent && it.runtimeScope != LibpatcherRuntimeScope.NOT_RUNTIME_ISP }
    }

    fun visibleRuntimeIspControlRefs(captureMode: CaptureStrategy, frameSource: String): List<LibpatcherControlRef> {
        return runtimeIspControlRefs().filter { ref ->
            isTopicVisible(ref.topic, captureMode, frameSource) &&
                    matchesFrame(ref.control.tags, frameSource) &&
                    matchesMode(ref.control.tags, captureMode)
        }
    }

    fun hiddenRuntimeIspControlRefs(captureMode: CaptureStrategy, frameSource: String): List<Pair<LibpatcherControlRef, String>> {
        return runtimeIspControlRefs().mapNotNull { ref ->
            val reason = visibilityReason(ref, captureMode, frameSource)
            if (reason == null) null else ref to reason
        }
    }

    fun visibilityReason(ref: LibpatcherControlRef, captureMode: CaptureStrategy, frameSource: String): String? {
        if (!matchesMode(ref.topic.tags, captureMode)) return "hidden: topic not applicable to capture mode ${captureMode.name}"
        if (!matchesFrame(ref.topic.tags, frameSource)) return "hidden: topic not applicable to frame source $frameSource"
        if (!matchesMode(ref.control.tags, captureMode)) return "hidden: control not applicable to capture mode ${captureMode.name}"
        if (!matchesFrame(ref.control.tags, frameSource)) return "hidden: control not applicable to frame source $frameSource"
        return null
    }

    private val commonRenderTitles = setOf(
        "Chroma Suppress",
        "Luma Suppress",
        "Spatial Suppress",
        "LUT Noise Suppress",
        "Temporal Suppress",
        "Motion Correct",
        "Polynomal Sharp Small",
        "Polynomal Sharp Medium",
        "Polynomal Sharp Large",
        "Sharp Gain Macro",
        "Sharp Gain Micro",
        "Soft Sharp",
        "Sharp Edge",
        "Halo Protection",
        "HDR Range 1",
        "HDR Range 2",
        "Shadow Compensation",
        "Overall Lightness"
    )

    private fun runtimeScopeFor(control: LibpatcherControlDef): LibpatcherRuntimeScope {
        if (control.kind == LibpatcherControlKind.READ_ONLY || control.kind == LibpatcherControlKind.ACTION) {
            return LibpatcherRuntimeScope.NOT_RUNTIME_ISP
        }
        if (control.title == "JPEG Quality") return LibpatcherRuntimeScope.OUTPUT_ENCODE
        if (control.key == KEY_DEMOSAIC_MODE) return LibpatcherRuntimeScope.RAW_DEMOSAIC
        if (control.title in commonRenderTitles) return LibpatcherRuntimeScope.COMMON_RENDER
        return LibpatcherRuntimeScope.NOT_RUNTIME_ISP
    }

    fun visibleSections(captureMode: CaptureStrategy, frameSource: String): List<LibpatcherSectionDef> {
        return sections.mapNotNull { section ->
            val visibleTopics = section.topics.mapNotNull topicFilter@{ topic ->
                if (!isTopicVisible(topic, captureMode, frameSource)) return@topicFilter null
                val visibleGroups = topic.groups.mapNotNull { group ->
                    val controls = group.controls.filter { control -> isControlVisible(topic, control, captureMode, frameSource) }
                    if (controls.isEmpty()) null else group.copy(controls = controls)
                }
                if (visibleGroups.isEmpty() && topic.customScreen == null) null else topic.copy(groups = visibleGroups)
            }
            if (visibleTopics.isEmpty()) null else section.copy(topics = visibleTopics)
        }
    }

    fun visibleControls(topic: LibpatcherTopicDef, group: LibpatcherGroupDef, captureMode: CaptureStrategy, frameSource: String): List<LibpatcherControlDef> {
        return group.controls.filter { isControlVisible(topic, it, captureMode, frameSource) }
    }

    fun visibleControls(topic: LibpatcherTopicDef, captureMode: CaptureStrategy, frameSource: String): List<LibpatcherControlDef> {
        return topic.groups.flatMap { group -> visibleControls(topic, group, captureMode, frameSource) }
    }

    fun hiddenControlCount(topic: LibpatcherTopicDef, captureMode: CaptureStrategy, frameSource: String): Int = 0

    fun isTopicVisible(topic: LibpatcherTopicDef, captureMode: CaptureStrategy, frameSource: String): Boolean {
        return matchesFrame(topic.tags, frameSource) && matchesMode(topic.tags, captureMode)
    }

    private fun isControlVisible(topic: LibpatcherTopicDef, control: LibpatcherControlDef, captureMode: CaptureStrategy, frameSource: String): Boolean {
        return isTopicVisible(topic, captureMode, frameSource) &&
                matchesFrame(control.tags, frameSource) &&
                matchesMode(control.tags, captureMode) &&
                runtimeScopeFor(control) != LibpatcherRuntimeScope.NOT_RUNTIME_ISP
    }

    private fun matchesFrame(tags: Set<String>, frameSource: String): Boolean {
        val frameTags = tags.filter { it in setOf("ALL", "YUV", "RAW", "RAW10", "RS") }.toSet()
        if (frameTags.isEmpty() || "ALL" in frameTags) return true
        val normalized = frameSource.uppercase()
        val isRaw = normalized == "RAW10" || normalized == "RAW_SENSOR"
        return (normalized == "YUV" && "YUV" in frameTags) ||
                (isRaw && "RAW" in frameTags) ||
                (normalized == "RAW10" && "RAW10" in frameTags) ||
                (normalized == "RAW_SENSOR" && "RS" in frameTags)
    }

    private fun matchesMode(tags: Set<String>, captureMode: CaptureStrategy): Boolean {
        val modeTags = tags.filter { it in setOf("SF", "MF", "DBG", "CSF") }.toSet()
        if (modeTags.isEmpty()) return true
        return modeTags.any { it in captureModeTokens(captureMode) }
    }

    fun captureModeTokens(captureMode: CaptureStrategy): Set<String> {
        return when (captureMode) {
            CaptureStrategy.SINGLE_FRAME_ZSL -> setOf("SF")
            CaptureStrategy.MULTI_FRAME_ZSL -> setOf("MF")
            CaptureStrategy.HDR_ENHANCED -> setOf("MF", "HDR")
        }
    }

    fun routeName(captureMode: CaptureStrategy, frameSource: String): String {
        val source = frameSource.uppercase()
        return when {
            captureMode == CaptureStrategy.SINGLE_FRAME_ZSL && source == "YUV" -> "CAMERAX_YUV_SINGLE_FRAME"
            captureMode == CaptureStrategy.SINGLE_FRAME_ZSL && source == "RAW10" -> "CAMERA2_RAW10_WARM_BUFFER_SINGLE_FRAME"
            captureMode == CaptureStrategy.SINGLE_FRAME_ZSL && source == "RAW_SENSOR" -> "CAMERA2_RAW_SENSOR_SINGLE_FRAME"
            captureMode == CaptureStrategy.MULTI_FRAME_ZSL && source == "YUV" -> "CAMERA2_YUV_NEAR_ZSL_MULTI_FRAME"
            captureMode == CaptureStrategy.MULTI_FRAME_ZSL && source == "RAW10" -> "CAMERA2_RAW10_NEAR_ZSL_MULTI_FRAME"
            captureMode == CaptureStrategy.MULTI_FRAME_ZSL && source == "RAW_SENSOR" -> "CAMERA2_RAW_SENSOR_MULTI_FRAME"
            captureMode == CaptureStrategy.HDR_ENHANCED && source == "RAW10" -> "CAMERA2_RAW10_HDR_ENHANCED_BURST"
            captureMode == CaptureStrategy.HDR_ENHANCED && source == "RAW_SENSOR" -> "CAMERA2_RAW_SENSOR_HDR_ENHANCED_BURST"
            captureMode == CaptureStrategy.HDR_ENHANCED && source == "YUV" -> "CAMERA2_YUV_HDR_ENHANCED_BURST"
            else -> "${captureMode.name}_$source"
        }
    }

    private fun scopeSectionKeys(sections: List<LibpatcherSectionDef>): List<LibpatcherSectionDef> {
        return sections.map { section ->
            section.copy(
                topics = section.topics.map { topic ->
                    topic.copy(
                        groups = topic.groups.map { group ->
                            group.copy(
                                controls = group.controls.map { control ->
                                    control.copy(key = stableStorageKeyFor(control.title) ?: control.title.toKeyPart())
                                }
                            )
                        }
                    )
                }
            )
        }
    }

    private fun stableStorageKeyFor(title: String): String? = when (title) {
        "Chroma Suppress" -> KEY_CHROMA_SUPPRESS
        "Luma Suppress" -> KEY_LUMA_SUPPRESS
        "Spatial Suppress" -> KEY_SPATIAL_SUPPRESS
        "LUT Noise Suppress" -> "lut_noise_suppress"
        "Temporal Suppress" -> KEY_TEMPORAL_SUPPRESS
        "Motion Correct" -> KEY_MOTION_CORRECT
        "Polynomal Sharp Small" -> KEY_POLY_SHARP_SMALL
        "Polynomal Sharp Medium" -> KEY_POLY_SHARP_MEDIUM
        "Polynomal Sharp Large" -> KEY_POLY_SHARP_LARGE
        "Sharp Gain Macro" -> KEY_SHARP_GAIN_MACRO
        "Sharp Gain Micro" -> KEY_SHARP_GAIN_MICRO
        "Soft Sharp" -> KEY_SOFT_SHARP
        "Sharp Edge" -> KEY_SHARP_EDGE
        "Halo Protection" -> KEY_HALO_PROTECTION
        "HDR Range 1" -> "hdr_range_1"
        "HDR Range 2" -> "hdr_range_2"
        "Shadow Compensation" -> "shadow_comp"
        "Overall Lightness" -> "overall_lightness"
        "JPEG Quality" -> "post_jpeg_quality"
        "Demosaic mode" -> KEY_DEMOSAIC_MODE
        else -> null
    }

    private fun section(id: String, title: String, description: String, topics: List<LibpatcherTopicDef>): LibpatcherSectionDef {
        return LibpatcherSectionDef(id = id, title = title, description = description, topics = topics)
    }

    private fun topic(
        id: String,
        title: String,
        description: String,
        tags: Set<String>,
        groups: List<LibpatcherGroupDef>,
        customScreen: String? = null
    ): LibpatcherTopicDef {
        return LibpatcherTopicDef(id = id, title = title, description = description, tags = tags, groups = groups, customScreen = customScreen)
    }

    private fun group(title: String, vararg controls: LibpatcherControlDef): LibpatcherGroupDef {
        return LibpatcherGroupDef(title = title, controls = controls.toList())
    }

    fun isTemporaryActiveIspControl(title: String): Boolean {
        return title == "JPEG Quality"
    }

    private fun real(title: String, vararg tags: String): LibpatcherControlDef {
        return control(title, LibpatcherControlKind.BASELINE_SLIDER, descriptionFor(title), *tags)
    }

    private fun optional(title: String, vararg tags: String): LibpatcherControlDef {
        return control(title, LibpatcherControlKind.OPTIONAL_SLIDER, descriptionFor(title), *tags)
    }

    private fun bipolar(title: String, vararg tags: String): LibpatcherControlDef {
        return control(title, LibpatcherControlKind.BASELINE_SLIDER, descriptionFor(title), *tags)
    }

    private fun enumControl(
        title: String,
        vararg tags: String,
        defaultValue: String,
        options: List<String>,
        description: String
    ): LibpatcherControlDef {
        return control(title, LibpatcherControlKind.ENUM, description, *tags).copy(
            defaultValue = defaultValue,
            enumOptions = options
        )
    }

    private fun descriptionFor(title: String): String = when (title) {
        "HDR Range 1" -> "Foreground/shadow tone bias. Positive lifts protected shadows; negative gently deepens shadows without changing RAW exposure."
        "HDR Range 2" -> "Highlight tone bias. Negative compresses bright regions; positive gives controlled highlight lift."
        "Shadow Compensation" -> "Interior recovery. Positive lifts shadows/lower mids while guarding highlights. Neutral has no extra effect."
        "Overall Lightness" -> "Display brightness bias. Positive lifts; negative gently darkens. Neutral has no extra effect."
        "JPEG Quality" -> "Sets the final JPEG encoder quality."
        "Demosaic mode" -> "Select Malvar Inspired, RCD Inspired, AMAZE Inspired, or Auto."
        "Chroma Suppress" -> "Suppresses color noise and chroma mottling."
        "Luma Suppress" -> "Suppresses luminance noise."
        "Spatial Suppress" -> "Filters static noise patterns in a single frame."
        "LUT Noise Suppress" -> "Shapes where denoise is allowed. Positive keeps smoothing mostly in shadows/mids and protects highlights/detail."
        "Temporal Suppress" -> "Temporal noise reduction strength for multi-frame-derived input."
        "Motion Correct" -> "Reduces motion-related merge/render artifacts."
        "Polynomal Sharp Small" -> "Sharpens the finest texture band."
        "Polynomal Sharp Medium" -> "Sharpens medium structures and edges."
        "Polynomal Sharp Large" -> "Enhances larger contours."
        "Sharp Gain Macro" -> "Intensity of larger edge sharpening."
        "Sharp Gain Micro" -> "Intensity of fine texture sharpening."
        "Soft Sharp" -> "Applies soft sharpening without broad libpatcher tone changes."
        "Sharp Edge" -> "Strengthens visible edges."
        "Halo Protection" -> "Reduces bright or dark halos caused by aggressive sharpening."
        else -> "Preserved libpatcher control."
    }

    private fun control(title: String, kind: LibpatcherControlKind, description: String, vararg tags: String): LibpatcherControlDef {
        return LibpatcherControlDef(
            key = keyForTitle(title),
            title = title,
            kind = kind,
            tags = tags.toSet(),
            description = description
        )
    }

    private fun keyForTitle(title: String): String = when (title) {
        "Chroma Suppress" -> KEY_CHROMA_SUPPRESS
        "Luma Suppress" -> KEY_LUMA_SUPPRESS
        "Spatial Suppress" -> KEY_SPATIAL_SUPPRESS
        "Temporal Suppress" -> KEY_TEMPORAL_SUPPRESS
        "Motion Correct" -> KEY_MOTION_CORRECT
        "Polynomal Sharp Small" -> KEY_POLY_SHARP_SMALL
        "Polynomal Sharp Medium" -> KEY_POLY_SHARP_MEDIUM
        "Polynomal Sharp Large" -> KEY_POLY_SHARP_LARGE
        "Sharp Gain Macro" -> KEY_SHARP_GAIN_MACRO
        "Sharp Gain Micro" -> KEY_SHARP_GAIN_MICRO
        "Soft Sharp" -> KEY_SOFT_SHARP
        "Sharp Edge" -> KEY_SHARP_EDGE
        "Halo Protection" -> KEY_HALO_PROTECTION
        "JPEG Quality" -> "post_jpeg_quality"
        "Demosaic mode" -> KEY_DEMOSAIC_MODE
        "HDR Range 1" -> "hdr_range_1"
        "HDR Range 2" -> "hdr_range_2"
        "Shadow Compensation" -> "shadow_comp"
        "Overall Lightness" -> "overall_lightness"
        else -> title.toKeyPart()
    }

    private fun String.toKeyPart(): String {
        return lowercase()
            .replace("/", " ")
            .replace("-", " ")
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
    }
}
