package com.bncam.core.capture

/**
 * Runtime trust gate for Camera2 API-36 exposure-time-priority on the default RAW repeating route.
 *
 * Production now trials exposure-time priority whenever the camera advertises the capability.
 * Advertising support is only static evidence, so the tracker consumes repeating request/result
 * truth and rejects the route for the current pipeline generation when priority/shutter authority
 * is not realized reliably.
 *
 * A second failure class is photometric unsolvability at the requested shutter. If AE remains
 * SEARCHING while ISO is pinned at either sensor bound, the current shutter/reference pairing can
 * no longer solve the scene. The first sustained bound condition requests a fresh ordinary-AE
 * reference; if the same generation again reaches an unsolved ISO bound before convergence, API36
 * priority is rejected for that generation and the caller can fall back to deterministic manual
 * control without oscillating between authorities.
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
    val consecutiveMaxIsoSearchingFrames: Int,
    val boundRebootstrapAttempts: Int,
    val consecutiveRealizedRepeatingFrames: Int,
    val api36Allowed: Boolean,
    val api36Trusted: Boolean,
    val reason: String
) {
    fun summary(): String =
        "action=$action;generation=$generation;realizationFailures=$consecutiveRealizationFailures;" +
            "minIsoSearchingFrames=$consecutiveMinIsoSearchingFrames;" +
            "maxIsoSearchingFrames=$consecutiveMaxIsoSearchingFrames;" +
            "boundRebootstrapAttempts=$boundRebootstrapAttempts;" +
            "realizedRepeatingFrames=$consecutiveRealizedRepeatingFrames;" +
            "api36Allowed=$api36Allowed;api36Trusted=$api36Trusted;reason=$reason"
}

class DefaultRawApi36AuthorityTracker(
    private val realizationFailureThreshold: Int = 3,
    private val isoBoundSearchingThreshold: Int = 3,
    private val maxBoundRebootstrapAttempts: Int = 1,
    private val priorityEnabled: Boolean = true
) {
    private var generation: Int = -1
    private var realizationFailures: Int = 0
    private var minIsoSearchingFrames: Int = 0
    private var maxIsoSearchingFrames: Int = 0
    private var boundRebootstrapAttempts: Int = 0
    private var realizedRepeatingFrames: Int = 0
    private var rejected: Boolean = false

    @Synchronized
    fun isAllowed(currentGeneration: Int): Boolean {
        ensureGeneration(currentGeneration)
        return priorityEnabled && !rejected
    }

    /** Runtime trust means the priority/shutter contract has been realized on repeating results. */
    @Synchronized
    fun isTrusted(currentGeneration: Int): Boolean {
        ensureGeneration(currentGeneration)
        return priorityEnabled && !rejected && realizedRepeatingFrames >= 2
    }

    @Synchronized
    fun reset(currentGeneration: Int) {
        generation = currentGeneration
        realizationFailures = 0
        minIsoSearchingFrames = 0
        maxIsoSearchingFrames = 0
        boundRebootstrapAttempts = 0
        realizedRepeatingFrames = 0
        rejected = false
    }

    @Synchronized
    fun observe(
        currentGeneration: Int,
        repeatingResult: Boolean,
        realizationStatus: String,
        aeSearching: Boolean,
        actualIso: Int?,
        minIso: Int?,
        maxIso: Int? = null
    ): DefaultRawApi36AuthorityDecision {
        ensureGeneration(currentGeneration)

        if (!priorityEnabled) {
            return decision(
                DefaultRawApi36AuthorityAction.KEEP_PRIORITY,
                "api36_exposure_time_priority_feature_disabled"
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
            realizationStatus == "API36_EXPOSURE_NOT_REALIZED_LONG" ||
            realizationStatus == "API36_INVALID_REQUEST" ||
            realizationStatus == "API36_INVALID_RESULT"
        if (realizationFailed) {
            realizationFailures++
            realizedRepeatingFrames = 0
        } else if (realizationStatus == "API36_EXPOSURE_REALIZED") {
            realizationFailures = 0
            realizedRepeatingFrames++
        } else {
            realizedRepeatingFrames = 0
        }

        if (realizationFailures >= realizationFailureThreshold.coerceAtLeast(1)) {
            rejectGeneration()
            return decision(
                DefaultRawApi36AuthorityAction.REJECT_API36_PRIORITY,
                "priority_or_shutter_not_realized_consecutively"
            )
        }

        val exposureRealized = realizationStatus == "API36_EXPOSURE_REALIZED"
        val atMinIso = actualIso != null && minIso != null && minIso > 0 && actualIso <= minIso
        val atMaxIso = actualIso != null && maxIso != null && maxIso > 0 && actualIso >= maxIso
        val unsolvedAtMinIso = exposureRealized && aeSearching && atMinIso
        val unsolvedAtMaxIso = exposureRealized && aeSearching && atMaxIso

        minIsoSearchingFrames = if (unsolvedAtMinIso) minIsoSearchingFrames + 1 else 0
        maxIsoSearchingFrames = if (unsolvedAtMaxIso) maxIsoSearchingFrames + 1 else 0

        // A genuinely converged priority result proves the new shutter/reference combination is
        // photometrically solvable, so a prior rebootstrap attempt no longer counts against it.
        if (exposureRealized && !aeSearching) {
            boundRebootstrapAttempts = 0
        }

        val threshold = isoBoundSearchingThreshold.coerceAtLeast(1)
        val boundReason = when {
            minIsoSearchingFrames >= threshold ->
                "ae_searching_at_min_iso_requested_shutter_not_photometrically_solvable"
            maxIsoSearchingFrames >= threshold ->
                "ae_searching_at_max_iso_requested_shutter_not_photometrically_solvable"
            else -> null
        }
        if (boundReason != null) {
            minIsoSearchingFrames = 0
            maxIsoSearchingFrames = 0
            realizationFailures = 0

            if (boundRebootstrapAttempts >= maxBoundRebootstrapAttempts.coerceAtLeast(0)) {
                rejectGeneration()
                return decision(
                    DefaultRawApi36AuthorityAction.REJECT_API36_PRIORITY,
                    "$boundReason;rebootstrap_exhausted"
                )
            }

            boundRebootstrapAttempts++
            return decision(
                DefaultRawApi36AuthorityAction.REBOOTSTRAP_AE_REFERENCE,
                boundReason
            )
        }

        return decision(DefaultRawApi36AuthorityAction.KEEP_PRIORITY, "priority_route_observation_accepted")
    }

    private fun rejectGeneration() {
        rejected = true
        minIsoSearchingFrames = 0
        maxIsoSearchingFrames = 0
        realizedRepeatingFrames = 0
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
            consecutiveMaxIsoSearchingFrames = maxIsoSearchingFrames,
            boundRebootstrapAttempts = boundRebootstrapAttempts,
            consecutiveRealizedRepeatingFrames = realizedRepeatingFrames,
            api36Allowed = priorityEnabled && !rejected,
            api36Trusted = priorityEnabled && !rejected && realizedRepeatingFrames >= 2,
            reason = reason
        )
}
