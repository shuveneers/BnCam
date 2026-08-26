package com.bncam.core.capture

import kotlin.math.max

enum class MeteringMode(val settingValue: String) {
    AUTO_DEFAULT_AE("Auto"),
    CENTER_WEIGHTED("Center Weighted"),
    FRAME_AVERAGE("Frame Average"),
    SPOT("Spot");

    companion object {
        /**
         * Canonicalizes current and historic values onto the four standard AE metering modes.
         * Removed BnCam-specific evaluative/highlight modes migrate to the camera's native Auto AE
         * instead of preserving a competing exposure controller.
         */
        fun fromSetting(value: String): MeteringMode = when (value.trim().lowercase()) {
            "center weighted", "center-weighted", "high key" -> CENTER_WEIGHTED
            "frame average", "frame-average", "average" -> FRAME_AVERAGE
            "spot", "spot metering", "spot / tap", "spot/tap", "silhouette" -> SPOT
            "auto", "default", "default ae", "native", "matrix",
            "evaluative / highlight protect", "evaluative",
            "highlight protect", "highlight-protect", "hdr", "ettr", "underexposed",
            "", "off" -> AUTO_DEFAULT_AE
            else -> AUTO_DEFAULT_AE
        }
    }
}

data class NormalizedPoint(val x: Float, val y: Float) {
    fun bounded(): NormalizedPoint = NormalizedPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
}

data class NormalizedMeteringRegion(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val weight: Int
) {
    init {
        require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f)
        require(right > left && bottom > top)
        require(weight in 1..1000)
    }
}

data class MeteringPlan(
    val mode: MeteringMode,
    val regions: List<NormalizedMeteringRegion>,
    val source: String,
    val supported: Boolean,
    val restoreInitialAeRegions: Boolean = false
) {
    fun summary(): String = buildString {
        append("mode=${mode.settingValue}")
        append(";source=$source")
        append(";supported=$supported")
        append(";restoreInitial=$restoreInitialAeRegions")
        append(";regionCount=${regions.size}")
        append(";weights=${regions.joinToString(prefix = "[", postfix = "]") { it.weight.toString() }}")
    }
}

/**
 * Standard DSLR-style Camera2 AE metering policy adapted from the behavior introduced in
 * PhotonCamera commit f6b6519a7da885daef638438ee4ff60723d1bac8.
 *
 * The four modes intentionally delegate exposure solving to the HAL. BnCam only supplies the
 * spatial AE regions; it does not layer a second statistics-driven EV controller on top.
 */
object CameraMeteringPolicy {
    private val CENTER = NormalizedPoint(0.5f, 0.5f)

    fun plan(
        mode: MeteringMode,
        maxAeRegions: Int,
        touchOverridePoint: NormalizedPoint? = null
    ): MeteringPlan {
        // Touch-to-focus is an explicit temporary 3A owner and therefore overrides the selected
        // standard metering mode, matching PhotonCamera's touch-focus contract.
        if (touchOverridePoint != null) {
            if (maxAeRegions <= 0) {
                return MeteringPlan(
                    mode = mode,
                    regions = emptyList(),
                    source = "touch_override_unsupported_no_ae_regions",
                    supported = false,
                    restoreInitialAeRegions = true
                )
            }
            return MeteringPlan(
                mode = mode,
                regions = listOf(regionAt(touchOverridePoint.bounded(), 0.125f, 999)),
                source = "touch_focus_override",
                supported = true
            )
        }

        if (mode == MeteringMode.AUTO_DEFAULT_AE) {
            return MeteringPlan(
                mode = mode,
                regions = emptyList(),
                source = "camera_default_ae_regions",
                supported = true,
                restoreInitialAeRegions = true
            )
        }

        if (maxAeRegions <= 0) {
            return MeteringPlan(
                mode = mode,
                regions = emptyList(),
                source = "camera_reports_no_custom_ae_regions",
                supported = false,
                restoreInitialAeRegions = true
            )
        }

        return when (mode) {
            MeteringMode.AUTO_DEFAULT_AE -> error("handled above")

            MeteringMode.CENTER_WEIGHTED -> {
                val regions = if (maxAeRegions >= 3) {
                    listOf(
                        regionAt(CENTER, 0.70f, 200),
                        regionAt(CENTER, 0.45f, 300),
                        regionAt(CENTER, 0.20f, 500)
                    )
                } else {
                    // PhotonCamera fallback for HALs exposing fewer than three AE regions.
                    listOf(regionAt(CENTER, 0.60f, 1000))
                }
                MeteringPlan(
                    mode = mode,
                    regions = regions,
                    source = if (maxAeRegions >= 3) {
                        "center_weighted_70_45_20"
                    } else {
                        "center_weighted_60_fallback"
                    },
                    supported = true
                )
            }

            MeteringMode.FRAME_AVERAGE -> MeteringPlan(
                mode = mode,
                regions = listOf(NormalizedMeteringRegion(0f, 0f, 1f, 1f, 1000)),
                source = "frame_average_full_active_array",
                supported = true
            )

            MeteringMode.SPOT -> MeteringPlan(
                mode = mode,
                // Width and height are 15.8%; area is approximately 2.5% of the sensor.
                regions = listOf(regionAt(CENTER, 0.158f, 1000)),
                source = "spot_center_2_5pct_area",
                supported = true
            )
        }
    }

    private fun regionAt(point: NormalizedPoint, size: Float, weight: Int): NormalizedMeteringRegion {
        val bounded = point.bounded()
        val boundedSize = size.coerceIn(0.01f, 1f)
        val half = boundedSize * 0.5f
        val left = (bounded.x - half).coerceIn(0f, 1f - boundedSize)
        val top = (bounded.y - half).coerceIn(0f, 1f - boundedSize)
        val right = (left + boundedSize).coerceIn(0f, 1f)
        val bottom = (top + boundedSize).coerceIn(0f, 1f)
        return NormalizedMeteringRegion(left, top, right, bottom, weight.coerceIn(1, 1000))
    }
}

data class ExposureBounds(
    val minIso: Int,
    val maxIso: Int,
    val minExposureNs: Long,
    val maxExposureNs: Long
)

data class ExposurePlan(
    val autoExposure: Boolean,
    val sensitivityIso: Int?,
    val exposureTimeNs: Long?,
    val frameDurationNs: Long?,
    val pendingReason: String?,
    val isoSource: String,
    val exposureSource: String
) {
    val ready: Boolean get() = autoExposure || pendingReason == null

    fun summary(): String = buildString {
        append("mode=${if (autoExposure) "AUTO" else if (ready) "MANUAL" else "MANUAL_PENDING"}")
        append(";iso=${sensitivityIso ?: "auto"}")
        append(";exposureNs=${exposureTimeNs ?: "auto"}")
        append(";isoSource=$isoSource")
        append(";exposureSource=$exposureSource")
        append(";pendingReason=${pendingReason ?: "none"}")
    }
}

object CameraExposurePolicy {
    fun resolve(
        requestedIso: Int?,
        requestedExposureNs: Long?,
        measuredIso: Int?,
        measuredExposureNs: Long?,
        bounds: ExposureBounds
    ): ExposurePlan {
        if (requestedIso == null && requestedExposureNs == null) {
            return ExposurePlan(
                autoExposure = true,
                sensitivityIso = null,
                exposureTimeNs = null,
                frameDurationNs = null,
                pendingReason = null,
                isoSource = "camera_ae",
                exposureSource = "camera_ae"
            )
        }

        if (requestedIso != null && requestedExposureNs == null) {
            val resolvedIso = requestedIso.coerceIn(bounds.minIso, bounds.maxIso)
            val baseExposureNs = if (measuredIso != null && measuredExposureNs != null && measuredIso > 0) {
                ((measuredIso.toDouble() * measuredExposureNs.toDouble()) / resolvedIso.toDouble()).toLong()
            } else {
                30_000_000L
            }.coerceIn(bounds.minExposureNs, bounds.maxExposureNs)
            return ExposurePlan(
                autoExposure = false,
                sensitivityIso = resolvedIso,
                exposureTimeNs = baseExposureNs,
                frameDurationNs = max(baseExposureNs, bounds.minExposureNs),
                pendingReason = null,
                isoSource = "user_fixed_iso",
                exposureSource = "camera_ae_auto_shutter"
            )
        }

        if (requestedIso == null && requestedExposureNs != null) {
            val resolvedExposure = requestedExposureNs.coerceIn(bounds.minExposureNs, bounds.maxExposureNs)
            val baseIso = if (measuredIso != null && measuredExposureNs != null && measuredExposureNs > 0L) {
                ((measuredIso.toDouble() * measuredExposureNs.toDouble()) / resolvedExposure.toDouble()).toInt()
            } else {
                400
            }.coerceIn(bounds.minIso, bounds.maxIso)
            return ExposurePlan(
                autoExposure = false,
                sensitivityIso = baseIso,
                exposureTimeNs = resolvedExposure,
                frameDurationNs = max(resolvedExposure, bounds.minExposureNs),
                pendingReason = null,
                isoSource = "camera_ae_auto_iso",
                exposureSource = "user_fixed_shutter"
            )
        }

        val resolvedIso = requestedIso!!.coerceIn(bounds.minIso, bounds.maxIso)
        val resolvedExposure = requestedExposureNs!!.coerceIn(bounds.minExposureNs, bounds.maxExposureNs)
        return ExposurePlan(
            autoExposure = false,
            sensitivityIso = resolvedIso,
            exposureTimeNs = resolvedExposure,
            frameDurationNs = max(resolvedExposure, bounds.minExposureNs),
            pendingReason = null,
            isoSource = "user_manual_iso",
            exposureSource = "user_manual_shutter"
        )
    }
}
