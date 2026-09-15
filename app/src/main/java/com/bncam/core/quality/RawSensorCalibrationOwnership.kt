package com.bncam.core.quality

/**
 * One role in the RAW sensor-characterisation chain. These roles deliberately remain separate:
 * a usable render fallback for one role must never make another role look calibrated.
 */
enum class RawCalibrationRole {
    CFA,
    BLACK_LEVEL,
    WHITE_LEVEL,
    LENS_SHADING,
    WHITE_BALANCE,
    COLOR_TRANSFORM
}

/**
 * Provenance class for a single calibration role. Only the three EXACT_* states are sensor
 * calibration. CONTROLLED_RENDER_FALLBACK may keep rendering alive, but is never calibration.
 */
enum class RawCalibrationAuthority {
    EXACT_FRAME_METADATA,
    EXACT_SENSOR_CHARACTERISTICS,
    EXACT_SENSOR_STATIC_PROFILE,
    CONTROLLED_RENDER_FALLBACK,
    UNAVAILABLE,
    REJECTED_SENSOR_AUTHORITY;

    val isSensorAuthoritative: Boolean
        get() = this == EXACT_FRAME_METADATA ||
            this == EXACT_SENSOR_CHARACTERISTICS ||
            this == EXACT_SENSOR_STATIC_PROFILE
}

data class RawCalibrationRoleOwnership(
    val role: RawCalibrationRole,
    val authority: RawCalibrationAuthority,
    val source: String,
    val reason: String = "none"
) {
    val isSensorAuthoritative: Boolean get() = authority.isSensorAuthoritative
}

data class RawSensorCalibrationOwnershipSnapshot(
    val sensorAuthorityId: String,
    val sourceAuthorityCoherent: Boolean,
    val sourceAuthorityReason: String,
    val roles: List<RawCalibrationRoleOwnership>
) {
    fun role(role: RawCalibrationRole): RawCalibrationRoleOwnership =
        roles.firstOrNull { it.role == role }
            ?: RawCalibrationRoleOwnership(
                role = role,
                authority = RawCalibrationAuthority.UNAVAILABLE,
                source = "UNAVAILABLE",
                reason = "ROLE_NOT_RESOLVED"
            )

    /** CFA + black + white are the non-negotiable RAW normalization contract. */
    val mandatoryRawNormalizationReady: Boolean
        get() = sourceAuthorityCoherent &&
            role(RawCalibrationRole.CFA).isSensorAuthoritative &&
            role(RawCalibrationRole.BLACK_LEVEL).isSensorAuthoritative &&
            role(RawCalibrationRole.WHITE_LEVEL).isSensorAuthoritative

    /** WB + CCM are colour roles. A neutral/identity render fallback never satisfies this. */
    val calibratedColorReady: Boolean
        get() = sourceAuthorityCoherent &&
            role(RawCalibrationRole.WHITE_BALANCE).isSensorAuthoritative &&
            role(RawCalibrationRole.COLOR_TRANSFORM).isSensorAuthoritative

    val mandatoryRejectionReason: String
        get() = when {
            !sourceAuthorityCoherent -> sourceAuthorityReason
            !role(RawCalibrationRole.CFA).isSensorAuthoritative ->
                "CFA_${role(RawCalibrationRole.CFA).reason}"
            !role(RawCalibrationRole.BLACK_LEVEL).isSensorAuthoritative ->
                "BLACK_LEVEL_${role(RawCalibrationRole.BLACK_LEVEL).reason}"
            !role(RawCalibrationRole.WHITE_LEVEL).isSensorAuthoritative ->
                "WHITE_LEVEL_${role(RawCalibrationRole.WHITE_LEVEL).reason}"
            else -> "none"
        }

    fun debugAuditLines(): List<String> = buildList {
        add(
            "Calibration Ownership | authority=$sensorAuthorityId; coherent=$sourceAuthorityCoherent; " +
                "reason=$sourceAuthorityReason; mandatoryRawReady=$mandatoryRawNormalizationReady; " +
                "calibratedColorReady=$calibratedColorReady"
        )
        RawCalibrationRole.values().forEach { calibrationRole ->
            val ownership = role(calibrationRole)
            add(
                "Calibration Role ${calibrationRole.name} | authority=${ownership.authority.name}; " +
                    "sensorAuthoritative=${ownership.isSensorAuthoritative}; source=${ownership.source}; " +
                    "reason=${ownership.reason}"
            )
        }
    }
}

/**
 * Single authority map for RAW sensor calibration roles. It never synthesizes sensor calibration.
 * A controlled neutral/identity render fallback is represented explicitly as such.
 */
object RawSensorCalibrationOwnershipPolicy {
    fun resolve(metadata: SensorMetadata): RawSensorCalibrationOwnershipSnapshot {
        val sensorAuthorityId = metadata.sensorIdentity.sourceId
        val sourceAuthorityReason = when {
            metadata.logicalMetadataFallbackUsed -> "LOGICAL_METADATA_FALLBACK_FORBIDDEN"
            metadata.foreignSensorMetadataUsed -> "FOREIGN_SENSOR_METADATA_FORBIDDEN"
            sensorAuthorityId.isBlank() -> "SENSOR_AUTHORITY_ID_UNAVAILABLE"
            metadata.calibrationSourceId != sensorAuthorityId -> "CALIBRATION_SOURCE_MISMATCH"
            metadata.characteristicsSourceId != sensorAuthorityId -> "CHARACTERISTICS_SOURCE_MISMATCH"
            metadata.captureResultSourceId != sensorAuthorityId -> "CAPTURE_RESULT_SOURCE_MISMATCH"
            else -> "none"
        }
        val coherent = sourceAuthorityReason == "none"

        fun rejected(role: RawCalibrationRole, source: String): RawCalibrationRoleOwnership =
            RawCalibrationRoleOwnership(
                role = role,
                authority = RawCalibrationAuthority.REJECTED_SENSOR_AUTHORITY,
                source = source,
                reason = sourceAuthorityReason
            )

        fun normalizedReason(reason: String, fallback: String): String =
            reason.takeUnless { it.isBlank() || it.equals("none", ignoreCase = true) } ?: fallback

        fun unavailable(
            role: RawCalibrationRole,
            source: String,
            reason: String,
            fallbackReason: String = "UNAVAILABLE"
        ): RawCalibrationRoleOwnership = RawCalibrationRoleOwnership(
            role = role,
            authority = RawCalibrationAuthority.UNAVAILABLE,
            source = source,
            reason = normalizedReason(reason, fallbackReason)
        )

        val cfa = if (!coherent) {
            rejected(RawCalibrationRole.CFA, metadata.cfa.source)
        } else if (metadata.cfa.isValid) {
            RawCalibrationRoleOwnership(
                RawCalibrationRole.CFA,
                RawCalibrationAuthority.EXACT_SENSOR_CHARACTERISTICS,
                metadata.cfa.source
            )
        } else {
            unavailable(RawCalibrationRole.CFA, metadata.cfa.source, metadata.cfa.reason)
        }

        val black = if (!coherent) {
            rejected(RawCalibrationRole.BLACK_LEVEL, metadata.effectiveBlackLevel.source)
        } else when {
            metadata.dynamicBlackLevel.isValid -> RawCalibrationRoleOwnership(
                RawCalibrationRole.BLACK_LEVEL,
                RawCalibrationAuthority.EXACT_FRAME_METADATA,
                metadata.dynamicBlackLevel.source
            )
            metadata.staticBlackLevel.isValid -> RawCalibrationRoleOwnership(
                RawCalibrationRole.BLACK_LEVEL,
                RawCalibrationAuthority.EXACT_SENSOR_CHARACTERISTICS,
                metadata.staticBlackLevel.source
            )
            else -> unavailable(
                RawCalibrationRole.BLACK_LEVEL,
                metadata.effectiveBlackLevel.source,
                metadata.effectiveBlackLevel.reason
            )
        }

        val white = if (!coherent) {
            rejected(RawCalibrationRole.WHITE_LEVEL, metadata.effectiveWhiteLevelField.source)
        } else when {
            metadata.dynamicWhiteLevelField.isValid -> RawCalibrationRoleOwnership(
                RawCalibrationRole.WHITE_LEVEL,
                RawCalibrationAuthority.EXACT_FRAME_METADATA,
                metadata.dynamicWhiteLevelField.source
            )
            metadata.staticWhiteLevelField.isValid -> RawCalibrationRoleOwnership(
                RawCalibrationRole.WHITE_LEVEL,
                RawCalibrationAuthority.EXACT_SENSOR_CHARACTERISTICS,
                metadata.staticWhiteLevelField.source
            )
            else -> unavailable(
                RawCalibrationRole.WHITE_LEVEL,
                metadata.effectiveWhiteLevelField.source,
                metadata.effectiveWhiteLevelField.reason
            )
        }

        val shading = if (!coherent) {
            rejected(RawCalibrationRole.LENS_SHADING, metadata.captureResultSourceId)
        } else if (metadata.hasDynamicLensShadingMap) {
            RawCalibrationRoleOwnership(
                RawCalibrationRole.LENS_SHADING,
                RawCalibrationAuthority.EXACT_FRAME_METADATA,
                "${metadata.captureResultSourceId}:STATISTICS_LENS_SHADING_CORRECTION_MAP"
            )
        } else {
            unavailable(
                RawCalibrationRole.LENS_SHADING,
                metadata.captureResultSourceId,
                "EXACT_FRAME_LENS_SHADING_MAP_UNAVAILABLE"
            )
        }

        val wbField = metadata.colorCorrectionGainsField
        val wbValues = wbField.value
        val wbValid = wbField.isValid && wbValues != null && wbValues.size == 4 &&
            wbValues.all { it.isFinite() && it > 0f }
        val wb = if (!coherent) {
            rejected(RawCalibrationRole.WHITE_BALANCE, wbField.source)
        } else if (wbValid) {
            RawCalibrationRoleOwnership(
                RawCalibrationRole.WHITE_BALANCE,
                RawCalibrationAuthority.EXACT_FRAME_METADATA,
                wbField.source
            )
        } else {
            RawCalibrationRoleOwnership(
                RawCalibrationRole.WHITE_BALANCE,
                RawCalibrationAuthority.CONTROLLED_RENDER_FALLBACK,
                wbField.source,
                normalizedReason(wbField.reason, "EXACT_FRAME_WB_UNAVAILABLE")
            )
        }

        val ccmField = metadata.colorCorrectionTransformField
        val ccmValues = ccmField.value
        val directCcmValid = ccmField.isValid && ccmValues != null && ccmValues.size == 9 &&
            ccmValues.all { it.isFinite() }
        fun validMatrix(field: SensorMetadataValue<List<Float>>): Boolean {
            val values = field.value
            return field.isValid && values != null && values.size == 9 && values.all { it.isFinite() }
        }
        val staticPrimaryColorProfileValid =
            metadata.referenceIlluminant1Field.isValid &&
                validMatrix(metadata.colorTransform1) &&
                validMatrix(metadata.cameraCalibration1) &&
                validMatrix(metadata.forwardMatrix1)
        val color = when {
            !coherent -> rejected(RawCalibrationRole.COLOR_TRANSFORM, ccmField.source)
            directCcmValid -> RawCalibrationRoleOwnership(
                RawCalibrationRole.COLOR_TRANSFORM,
                RawCalibrationAuthority.EXACT_FRAME_METADATA,
                ccmField.source
            )
            staticPrimaryColorProfileValid -> RawCalibrationRoleOwnership(
                RawCalibrationRole.COLOR_TRANSFORM,
                RawCalibrationAuthority.EXACT_SENSOR_STATIC_PROFILE,
                "${metadata.calibrationSourceId}:STATIC_PRIMARY_COLOR_PROFILE"
            )
            else -> RawCalibrationRoleOwnership(
                RawCalibrationRole.COLOR_TRANSFORM,
                RawCalibrationAuthority.CONTROLLED_RENDER_FALLBACK,
                ccmField.source,
                normalizedReason(ccmField.reason, "NO_AUTHORITATIVE_COLOR_TRANSFORM")
            )
        }

        return RawSensorCalibrationOwnershipSnapshot(
            sensorAuthorityId = sensorAuthorityId,
            sourceAuthorityCoherent = coherent,
            sourceAuthorityReason = sourceAuthorityReason,
            roles = listOf(cfa, black, white, shading, wb, color)
        )
    }
}
