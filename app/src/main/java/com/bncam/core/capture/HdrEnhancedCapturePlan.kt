package com.bncam.core.capture

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Capture plan snapshot for a deliberate non-ZSL HDR Enhanced burst.
 * Immutable and frozen at shutter time.
 */
data class HdrEnhancedCapturePlan(
    val requested: Boolean,
    val status: HdrEnhancedStatus,
    val sceneDynamicRangeEstimateEv: Float,
    val sceneMotionClass: HdrEnhancedMotionClass,
    val exposureAuthority: HdrExposureAuthority,
    val mainExposureTimeNs: Long,
    val mainSensitivityIso: Int,
    val expectedFrameDurationNs: Long,
    val expectedBurstCadenceNs: Long,
    val sensorMinFrameDurationNs: Long,
    val captureBudgetMs: Long,
    val frameBounds: HdrEnhancedFrameBounds,
    val mainFrameCount: Int,
    val auxPlan: HdrEnhancedAuxPlan,
    val highlightProtectionEvTarget: Float,
    val theoreticalSnrGain: Float,
    val reason: String,
    val profileExposurePriorityMode: CaptureExposurePriorityMode = CaptureExposurePriorityMode.BALANCED,
    val profileExposurePriorityHonored: Boolean = true,
    val profileExposurePriorityConstraint: String = "NONE"
)

object HdrEnhancedPlanner {
    private const val DEFAULT_CAPTURE_BUDGET_MS = 1200L
    private const val HARDWARE_MAX_FRAME_CAPACITY = 15
    private const val PLANNER_MAX_FRAMES = 15

    fun plan(
        baseExposureTimeNs: Long,
        baseSensitivityIso: Int,
        sensorMinFrameDurationNs: Long,
        rawHeadroomEv: Float? = null,
        histogramClippingFraction: Float? = null,
        sceneMotionScore: Float? = null,
        thermalState: Int = 0,
        availableMemoryMb: Long = 1024L,
        hardwareFrameCapacity: Int = HARDWARE_MAX_FRAME_CAPACITY,
        plannerMaximumFrames: Int = PLANNER_MAX_FRAMES,
        userCaptureBudgetMs: Long = DEFAULT_CAPTURE_BUDGET_MS,
        hdrEnhancedFrameSetting: String = "Auto",
        exposurePriorityMode: CaptureExposurePriorityMode = CaptureExposurePriorityMode.BALANCED,
        exposureBounds: ExposureBounds? = null
    ): HdrEnhancedCapturePlan {
        val userFrameLimit = hdrEnhancedFrameSetting.toIntOrNull()

        if (baseExposureTimeNs <= 0L || baseSensitivityIso <= 0) {
            val emptyBounds = HdrEnhancedFrameBounds(
                hdrEnhancedFrameSetting = hdrEnhancedFrameSetting,
                userFrameLimit = userFrameLimit,
                hardwareFrameCapacity = hardwareFrameCapacity.coerceAtLeast(0),
                plannerMaximumFrames = plannerMaximumFrames.coerceAtLeast(0),
                budgetLimitedMaximumFrames = 0,
                motionLimitedMaximumFrames = 0,
                memoryLimitedMaximumFrames = 0,
                finalMainFrameCount = 0,
                limitingConstraint = "INVALID_BASE_EXPOSURE"
            )
            return HdrEnhancedCapturePlan(
                requested = true,
                status = HdrEnhancedStatus.FAILED,
                sceneDynamicRangeEstimateEv = Float.NaN,
                sceneMotionClass = HdrEnhancedMotionClass.UNKNOWN,
                exposureAuthority = HdrExposureAuthority.AE_FALLBACK,
                mainExposureTimeNs = baseExposureTimeNs,
                mainSensitivityIso = baseSensitivityIso,
                expectedFrameDurationNs = 0L,
                expectedBurstCadenceNs = 0L,
                sensorMinFrameDurationNs = sensorMinFrameDurationNs,
                captureBudgetMs = userCaptureBudgetMs,
                frameBounds = emptyBounds,
                mainFrameCount = 0,
                auxPlan = HdrEnhancedAuxPlan(
                    role = HdrEnhancedAuxRole.NONE,
                    exposureTimeNs = 0L,
                    sensitivityIso = 0,
                    exposureScaleToMain = 1.0f,
                    reason = "invalid_base_exposure"
                ),
                highlightProtectionEvTarget = 0f,
                theoreticalSnrGain = 1.0f,
                reason = "invalid_base_exposure",
                profileExposurePriorityMode = exposurePriorityMode,
                profileExposurePriorityHonored = false,
                profileExposurePriorityConstraint = "INVALID_BASE_EXPOSURE"
            )
        }

        // 1. Determine RAW exposure authority and highlight protection target
        val (exposureAuthority, highlightProtectionTargetEv) = when {
            rawHeadroomEv != null && rawHeadroomEv.isFinite() -> {
                val shift = (rawHeadroomEv * 0.75f).coerceIn(-1.5f, 0.0f)
                Pair(HdrExposureAuthority.RAW_ETTR_HEADROOM, shift)
            }
            histogramClippingFraction != null && histogramClippingFraction > 0.05f -> {
                val shift = if (histogramClippingFraction > 0.15f) -1.2f else -0.6f
                Pair(HdrExposureAuthority.RAW_HISTOGRAM, shift)
            }
            histogramClippingFraction != null -> {
                Pair(HdrExposureAuthority.SENSOR_CLIP_ESTIMATE, -0.3f)
            }
            else -> {
                Pair(HdrExposureAuthority.AE_FALLBACK, -0.5f)
            }
        }

        // The base values are the ACTUAL pre-shutter repeating exposure. Profile Shutter/ISO
        // Priority has already been applied there, so never apply its multiplier a second time.
        // HDR only distributes its additional highlight-protection EV according to the active
        // priority: keep shutter fixed and lower ISO first for Shutter Priority; keep ISO fixed
        // and shorten shutter first for ISO Priority. Sensor floors may force a bounded override.
        val mainExposureResolution = resolveHighlightSafeMainExposure(
            baseExposureTimeNs = baseExposureTimeNs,
            baseSensitivityIso = baseSensitivityIso,
            highlightProtectionTargetEv = highlightProtectionTargetEv,
            priorityMode = exposurePriorityMode,
            bounds = exposureBounds
        )
        val mainExposureTimeNs = mainExposureResolution.exposureTimeNs
        val mainSensitivityIso = mainExposureResolution.sensitivityIso

        // 2. Expected frame duration and burst cadence (Sanitize sensorMinFrameDurationNs)
        val sanitizedSensorMinNs = sensorMinFrameDurationNs.takeIf { it in 1_000_000L..200_000_000L } ?: 33_333_333L
        val expectedFrameDurationNs = max(sanitizedSensorMinNs, mainExposureTimeNs)
        // Add 5ms readout/transfer overhead between frames
        val expectedBurstCadenceNs = expectedFrameDurationNs + 5_000_000L

        // 3. Cadence & Budget Solving
        val captureBudgetNs = userCaptureBudgetMs * 1_000_000L
        val budgetLimitedMax = (captureBudgetNs.toDouble() / expectedBurstCadenceNs.toDouble()).toInt()
            .coerceAtLeast(1)

        // 4. Motion Classification & Motion-Limited Frames
        val (motionClass, motionLimitedMax) = when {
            sceneMotionScore == null || !sceneMotionScore.isFinite() ->
                Pair(HdrEnhancedMotionClass.UNKNOWN, plannerMaximumFrames.coerceAtLeast(1))
            sceneMotionScore > 0.5f -> Pair(HdrEnhancedMotionClass.HIGH_MOTION, 6)
            sceneMotionScore > 0.2f -> Pair(HdrEnhancedMotionClass.MODERATE_MOTION, 10)
            else -> Pair(HdrEnhancedMotionClass.LOW_MOTION, 15)
        }

        // 5. Thermal / Memory-Limited Frames
        val memoryLimitedMax = when {
            thermalState >= 2 || availableMemoryMb < 512L -> 6
            availableMemoryMb < 1024L -> 10
            else -> 15
        }

        // 6. Constrain Final Main Frame Count (min of all applicable constraints)
        val candidatesList = mutableListOf<Int>()
        userFrameLimit?.let { candidatesList.add(it) }
        candidatesList.add(hardwareFrameCapacity.coerceAtLeast(1))
        candidatesList.add(plannerMaximumFrames.coerceAtLeast(1))
        candidatesList.add(budgetLimitedMax)
        candidatesList.add(motionLimitedMax)
        candidatesList.add(memoryLimitedMax)

        val finalMainFrameCount = candidatesList.minOrNull()?.coerceAtLeast(0) ?: 0

        val cadenceMs = expectedBurstCadenceNs / 1_000_000.0
        val limitingConstraint = when (finalMainFrameCount) {
            userFrameLimit -> "USER_SETTING_LIMITED ($hdrEnhancedFrameSetting frames user ceiling)"
            budgetLimitedMax -> "BUDGET_LIMITED (${userCaptureBudgetMs}ms budget / ${String.format(java.util.Locale.US, "%.1f", cadenceMs)}ms cadence = $budgetLimitedMax frames)"
            motionLimitedMax -> "MOTION_LIMITED ($motionClass scene motion limits burst to $motionLimitedMax frames)"
            memoryLimitedMax -> "MEMORY_LIMITED (memory/thermal limits burst to $memoryLimitedMax frames)"
            hardwareFrameCapacity -> "HARDWARE_CAPACITY_LIMITED ($hardwareFrameCapacity frames)"
            else -> "PLANNER_MAX_LIMITED ($plannerMaximumFrames frames)"
        }

        val frameBounds = HdrEnhancedFrameBounds(
            hdrEnhancedFrameSetting = hdrEnhancedFrameSetting,
            userFrameLimit = userFrameLimit,
            hardwareFrameCapacity = hardwareFrameCapacity,
            plannerMaximumFrames = plannerMaximumFrames,
            budgetLimitedMaximumFrames = budgetLimitedMax,
            motionLimitedMaximumFrames = motionLimitedMax,
            memoryLimitedMaximumFrames = memoryLimitedMax,
            finalMainFrameCount = finalMainFrameCount,
            limitingConstraint = limitingConstraint
        )

        // 7. Auxiliary exposures are optional and are not part of the current production
        // acquisition contract. Keep the plan truthful instead of reporting an auxiliary that
        // this runner does not actually submit.
        val auxPlan = HdrEnhancedAuxPlan(
            role = HdrEnhancedAuxRole.NONE,
            exposureTimeNs = 0L,
            sensitivityIso = 0,
            exposureScaleToMain = 1.0f,
            reason = "main_temporal_stack_only"
        )

        if (finalMainFrameCount < 4) {
            return HdrEnhancedCapturePlan(
                requested = true,
                status = HdrEnhancedStatus.FAILED,
                sceneDynamicRangeEstimateEv = Float.NaN,
                sceneMotionClass = motionClass,
                exposureAuthority = exposureAuthority,
                mainExposureTimeNs = mainExposureTimeNs,
                mainSensitivityIso = mainSensitivityIso,
                expectedFrameDurationNs = expectedFrameDurationNs,
                expectedBurstCadenceNs = expectedBurstCadenceNs,
                sensorMinFrameDurationNs = sanitizedSensorMinNs,
                captureBudgetMs = userCaptureBudgetMs,
                frameBounds = frameBounds,
                mainFrameCount = 0,
                auxPlan = auxPlan,
                highlightProtectionEvTarget = highlightProtectionTargetEv,
                theoreticalSnrGain = 1.0f,
                reason = "constraints_allow_fewer_than_hdr_enhanced_minimum:resolved=$finalMainFrameCount;minimum=4",
                profileExposurePriorityMode = exposurePriorityMode,
                profileExposurePriorityHonored = mainExposureResolution.priorityHonored,
                profileExposurePriorityConstraint = mainExposureResolution.constraint
            )
        }

        val theoreticalSnrGain = sqrt(finalMainFrameCount.toDouble()).toFloat()
        // Dynamic-range EV is not synthesized from the chosen exposure shift. No dedicated
        // sensor-domain DR estimator exists in the current pipeline, so keep this explicitly
        // unavailable rather than emitting a plausible dummy value.
        val sceneDynamicRangeEstimateEv = Float.NaN

        return HdrEnhancedCapturePlan(
            requested = true,
            status = HdrEnhancedStatus.PLANNED,
            sceneDynamicRangeEstimateEv = sceneDynamicRangeEstimateEv,
            sceneMotionClass = motionClass,
            exposureAuthority = exposureAuthority,
            mainExposureTimeNs = mainExposureTimeNs,
            mainSensitivityIso = mainSensitivityIso,
            expectedFrameDurationNs = expectedFrameDurationNs,
            expectedBurstCadenceNs = expectedBurstCadenceNs,
            sensorMinFrameDurationNs = sanitizedSensorMinNs,
            captureBudgetMs = userCaptureBudgetMs,
            frameBounds = frameBounds,
            mainFrameCount = finalMainFrameCount,
            auxPlan = auxPlan,
            highlightProtectionEvTarget = highlightProtectionTargetEv,
            theoreticalSnrGain = theoreticalSnrGain,
            reason = "hdr_enhanced_planned_successfully",
            profileExposurePriorityMode = exposurePriorityMode,
            profileExposurePriorityHonored = mainExposureResolution.priorityHonored,
            profileExposurePriorityConstraint = mainExposureResolution.constraint
        )
    }

    private data class HdrMainExposureResolution(
        val exposureTimeNs: Long,
        val sensitivityIso: Int,
        val priorityHonored: Boolean,
        val constraint: String
    )

    private fun resolveHighlightSafeMainExposure(
        baseExposureTimeNs: Long,
        baseSensitivityIso: Int,
        highlightProtectionTargetEv: Float,
        priorityMode: CaptureExposurePriorityMode,
        bounds: ExposureBounds?
    ): HdrMainExposureResolution {
        val scale = Math.pow(2.0, highlightProtectionTargetEv.toDouble())
        val minExposureNs = bounds?.minExposureNs ?: 500_000L
        val maxExposureNs = bounds?.maxExposureNs ?: Long.MAX_VALUE
        val minIso = bounds?.minIso ?: 1
        val maxIso = bounds?.maxIso ?: Int.MAX_VALUE
        val baseExposure = baseExposureTimeNs.coerceIn(minExposureNs, maxExposureNs)
        val baseIso = baseSensitivityIso.coerceIn(minIso, maxIso)
        val targetProduct = baseExposure.toDouble() * baseIso.toDouble() * scale

        return when (priorityMode) {
            CaptureExposurePriorityMode.SHUTTER_PRIORITY -> {
                val fixedExposure = baseExposure
                val requestedIso = (targetProduct / fixedExposure.toDouble()).roundToInt()
                if (requestedIso >= minIso) {
                    val iso = requestedIso.coerceIn(minIso, maxIso)
                    HdrMainExposureResolution(
                        exposureTimeNs = fixedExposure,
                        sensitivityIso = iso,
                        priorityHonored = true,
                        constraint = if (iso == requestedIso) "NONE" else "ISO_SENSOR_LIMIT"
                    )
                } else {
                    val iso = minIso
                    val requestedExposure = (targetProduct / iso.toDouble()).roundToLong()
                    val exposure = requestedExposure.coerceIn(minExposureNs, maxExposureNs)
                    HdrMainExposureResolution(
                        exposureTimeNs = exposure,
                        sensitivityIso = iso,
                        priorityHonored = false,
                        constraint = if (exposure == requestedExposure) {
                            "ISO_FLOOR_REQUIRES_SHUTTER_OVERRIDE"
                        } else {
                            "ISO_AND_SHUTTER_SENSOR_FLOOR"
                        }
                    )
                }
            }

            CaptureExposurePriorityMode.ISO_PRIORITY -> {
                val fixedIso = baseIso
                val requestedExposure = (targetProduct / fixedIso.toDouble()).roundToLong()
                if (requestedExposure >= minExposureNs) {
                    val exposure = requestedExposure.coerceIn(minExposureNs, maxExposureNs)
                    HdrMainExposureResolution(
                        exposureTimeNs = exposure,
                        sensitivityIso = fixedIso,
                        priorityHonored = true,
                        constraint = if (exposure == requestedExposure) "NONE" else "SHUTTER_SENSOR_LIMIT"
                    )
                } else {
                    val exposure = minExposureNs
                    val requestedIso = (targetProduct / exposure.toDouble()).roundToInt()
                    val iso = requestedIso.coerceIn(minIso, maxIso)
                    HdrMainExposureResolution(
                        exposureTimeNs = exposure,
                        sensitivityIso = iso,
                        priorityHonored = false,
                        constraint = if (iso == requestedIso) {
                            "SHUTTER_FLOOR_REQUIRES_ISO_OVERRIDE"
                        } else {
                            "SHUTTER_AND_ISO_SENSOR_FLOOR"
                        }
                    )
                }
            }

            CaptureExposurePriorityMode.BALANCED -> HdrMainExposureResolution(
                exposureTimeNs = (baseExposure.toDouble() * scale).roundToLong().coerceIn(minExposureNs, maxExposureNs),
                sensitivityIso = baseIso,
                priorityHonored = true,
                constraint = "NONE"
            )
        }
    }
}
