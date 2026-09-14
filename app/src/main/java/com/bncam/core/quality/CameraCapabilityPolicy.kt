package com.bncam.core.quality

/** Features for which Camera2 discovery and BnCam implementation authority must stay separate. */
enum class CameraCapabilityFeature {
    RAW_PIPELINE,
    LENS_SHADING_MAP,
    DISTORTION_CORRECTION,
    TONEMAP_CURVE,
    MANUAL_FOCUS
}

/**
 * Explicit BnCam-side implementation facts.
 *
 * There are deliberately no defaults: camera hardware support must never silently imply that a
 * BnCam processing path exists or should be enabled.
 */
data class BnCamCapabilityImplementation(
    val rawPipeline: Boolean,
    val lensShadingMapConsumer: Boolean,
    val distortionCorrectionConsumer: Boolean,
    val tonemapCurveConsumer: Boolean,
    val manualFocusController: Boolean
)

data class CameraCapabilityDecision(
    val feature: CameraCapabilityFeature,
    val cameraFactAvailable: Boolean,
    val bnCamImplementationAvailable: Boolean,
    val eligible: Boolean,
    val authority: String,
    val reason: String
)

data class CameraCapabilityPolicySnapshot(
    val rawPipeline: CameraCapabilityDecision,
    val lensShadingMap: CameraCapabilityDecision,
    val distortionCorrection: CameraCapabilityDecision,
    val tonemapCurve: CameraCapabilityDecision,
    val manualFocus: CameraCapabilityDecision
) {
    fun decision(feature: CameraCapabilityFeature): CameraCapabilityDecision = when (feature) {
        CameraCapabilityFeature.RAW_PIPELINE -> rawPipeline
        CameraCapabilityFeature.LENS_SHADING_MAP -> lensShadingMap
        CameraCapabilityFeature.DISTORTION_CORRECTION -> distortionCorrection
        CameraCapabilityFeature.TONEMAP_CURVE -> tonemapCurve
        CameraCapabilityFeature.MANUAL_FOCUS -> manualFocus
    }

    fun debugPairs(): List<Pair<String, String>> = CameraCapabilityFeature.values().flatMap { feature ->
        val value = decision(feature)
        val prefix = "Capability ${feature.name}"
        listOf(
            "$prefix Camera Fact" to value.cameraFactAvailable.toString(),
            "$prefix BnCam Implementation" to value.bnCamImplementationAvailable.toString(),
            "$prefix Eligible" to value.eligible.toString(),
            "$prefix Authority" to value.authority,
            "$prefix Reason" to value.reason
        )
    }
}

/**
 * Pure feature-eligibility policy. It is intentionally incapable of choosing denoise strength,
 * sharpening, tone, colour, frame count, exposure bias or any other image tuning value.
 */
object CameraCapabilityPolicy {
    const val AUTHORITY = "CAMERA_FACT_AND_EXPLICIT_BNCAM_POLICY"

    fun resolve(
        camera: CameraCapabilitySnapshot,
        implementation: BnCamCapabilityImplementation
    ): CameraCapabilityPolicySnapshot {
        val manualFocusFact = (camera.minimumFocusDistanceDiopters ?: 0.0f) > 0.0f
        return CameraCapabilityPolicySnapshot(
            rawPipeline = decide(
                CameraCapabilityFeature.RAW_PIPELINE,
                camera.supportsRaw,
                implementation.rawPipeline
            ),
            lensShadingMap = decide(
                CameraCapabilityFeature.LENS_SHADING_MAP,
                camera.supportsLensShadingMap,
                implementation.lensShadingMapConsumer
            ),
            distortionCorrection = decide(
                CameraCapabilityFeature.DISTORTION_CORRECTION,
                camera.supportsDistortionCorrection,
                implementation.distortionCorrectionConsumer
            ),
            tonemapCurve = decide(
                CameraCapabilityFeature.TONEMAP_CURVE,
                camera.supportsTonemapCurve,
                implementation.tonemapCurveConsumer
            ),
            manualFocus = decide(
                CameraCapabilityFeature.MANUAL_FOCUS,
                manualFocusFact,
                implementation.manualFocusController
            )
        )
    }

    private fun decide(
        feature: CameraCapabilityFeature,
        cameraFact: Boolean,
        implementationFact: Boolean
    ): CameraCapabilityDecision {
        val eligible = cameraFact && implementationFact
        val reason = when {
            !cameraFact -> "CAMERA_CAPABILITY_UNAVAILABLE"
            !implementationFact -> "BNCAM_IMPLEMENTATION_UNAVAILABLE"
            else -> "ELIGIBLE_CAMERA_FACT_AND_BNCAM_IMPLEMENTATION"
        }
        return CameraCapabilityDecision(
            feature = feature,
            cameraFactAvailable = cameraFact,
            bnCamImplementationAvailable = implementationFact,
            eligible = eligible,
            authority = AUTHORITY,
            reason = reason
        )
    }
}
