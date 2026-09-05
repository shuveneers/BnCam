package com.bncam.core.capture

/**
 * Runtime trust gate for Camera2 API-36 exposure-time-priority on the default RAW repeating route.
 *
 * Exposure-time priority deliberately leaves SENSOR_SENSITIVITY to Camera2 AE. That is useful when
 * shutter ownership alone is sufficient, but it cannot guarantee BnCam's low-gain acquisition
 * policy. The default RAW production route therefore keeps this optional authority disabled and
 * uses the existing adaptive AE-OFF manual feedback path after Camera2 AE bootstrap, where BnCam
 * owns both shutter and ISO. The tracker remains available for explicit capability experiments by
 * constructing it with [priorityEnabled] = true.
 *
 * When enabled, advertising the capability is only static evidence. This tracker consumes
 * request/result truth from repeating frames and separates two failure classes:
 * 1) the requested priority/shutter is not realized -> reject this API route for the generation;
 * 2) the shutter is realized, but AE remains SEARCHING at minimum ISO -> the brightness reference
 *    is no longer solvable at that shutter, so reacquire a fresh ordinary-AE reference.
 */
enum class DefaultRawApi36AuthorityAction {
    KEEP_PRIORITY,
    REBOOTSTRAP_AE_REFERENCE,
    REJECT_API36_PRIORITY
}

data class DefaultRawApi36AuthorityDecision(
    val action: DefaultRawApi36AuthorityAction,
    val generation: Int,
    val consecutiveRealizationFailures: Int,
    val consecutiveMinIsoSearchingFrames: Int,
    val api36Allowed: Boolean,
    val reason: String
) {
    fun summary(): String =
        "action=$action;generation=$generation;realizationFailures=$consecutiveRealizationFailures;" +
            "minIsoSearchingFrames=$consecutiveMinIsoSearchingFrames;api36Allowed=$api36Allowed;reason=$reason"
}

class DefaultRawApi36AuthorityTracker(
    private val realizationFailureThreshold: Int = 3,
    private val minIsoSearchingThreshold: Int = 3,
    private val priorityEnabled: Boolean = false
) {
    private var generation: Int = -1
    private var realizationFailures: Int = 0
    private var minIsoSearchingFrames: Int = 0
    private var rejected: Boolean = false

    @Synchronized
    fun isAllowed(currentGeneration: Int): Boolean {
        ensureGeneration(currentGeneration)
        return priorityEnabled && !rejected
    }

    @Synchronized
    fun reset(currentGeneration: Int) {
        generation = currentGeneration
        realizationFailures = 0
        minIsoSearchingFrames = 0
        rejected = false
    }

    @Synchronized
    fun observe(
        currentGeneration: Int,
        repeatingResult: Boolean,
        realizationStatus: String,
        aeSearching: Boolean,
        actualIso: Int?,
        minIso: Int?
    ): DefaultRawApi36AuthorityDecision {
        ensureGeneration(currentGeneration)

        // Keep observation side-effect free when production deliberately uses deterministic
        // AE-OFF sensor control. Returning KEEP prevents a stale/non-API36 result from triggering a
        // rebootstrap loop, while api36Allowed remains false in diagnostics.
        if (!priorityEnabled) {
            return decision(
                DefaultRawApi36AuthorityAction.KEEP_PRIORITY,
                "api36_exposure_time_priority_disabled_for_deterministic_iso_ownership"
            )
        }

        if (rejected) {
            return decision(
                DefaultRawApi36AuthorityAction.REJECT_API36_PRIORITY,
                "already_rejected_for_generation"
            )
        }
        if (!repeatingResult) {
            return decision(DefaultRawApi36AuthorityAction.KEEP_PRIORITY, "non_repeating_result_ignored")
        }

        val realizationFailed = realizationStatus == "API36_PRIORITY_MODE_MISMATCH" ||
            realizationStatus == "API36_EXPOSURE_NOT_REALIZED_SHORT" ||
            realizationStatus == "API36_EXPOSURE_NOT_REALIZED_LONG"
        if (realizationFailed) {
            realizationFailures++
        } else if (realizationStatus == "API36_EXPOSURE_REALIZED") {
            realizationFailures = 0
        }

        if (realizationFailures >= realizationFailureThreshold.coerceAtLeast(1)) {
            rejected = true
            minIsoSearchingFrames = 0
            return decision(
                DefaultRawApi36AuthorityAction.REJECT_API36_PRIORITY,
                "priority_or_shutter_not_realized_consecutively"
            )
        }

        val atMinIso = actualIso != null && minIso != null && minIso > 0 && actualIso <= minIso
        val unsolvedAtMinIso = realizationStatus == "API36_EXPOSURE_REALIZED" && aeSearching && atMinIso
        if (unsolvedAtMinIso) {
            minIsoSearchingFrames++
        } else {
            minIsoSearchingFrames = 0
        }

        if (minIsoSearchingFrames >= minIsoSearchingThreshold.coerceAtLeast(1)) {
            minIsoSearchingFrames = 0
            realizationFailures = 0
            return decision(
                DefaultRawApi36AuthorityAction.REBOOTSTRAP_AE_REFERENCE,
                "ae_searching_at_min_iso_requested_shutter_not_photometrically_solvable"
            )
        }

        return decision(DefaultRawApi36AuthorityAction.KEEP_PRIORITY, "priority_route_observation_accepted")
    }

    private fun ensureGeneration(currentGeneration: Int) {
        if (generation != currentGeneration) reset(currentGeneration)
    }

    private fun decision(action: DefaultRawApi36AuthorityAction, reason: String) =
        DefaultRawApi36AuthorityDecision(
            action = action,
            generation = generation,
            consecutiveRealizationFailures = realizationFailures,
            consecutiveMinIsoSearchingFrames = minIsoSearchingFrames,
            api36Allowed = priorityEnabled && !rejected,
            reason = reason
        )
}
