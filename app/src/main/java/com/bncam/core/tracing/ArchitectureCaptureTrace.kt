package com.bncam.core.tracing

import com.bncam.core.capture.CaptureRecipe
import com.bncam.core.output.CaptureWarning
import com.bncam.core.output.PublicationPolicyResolver
import com.bncam.core.output.StringCaptureArtifacts
import java.security.MessageDigest

enum class CaptureTraceSection {
    OVERVIEW,
    HARDWARE_AND_CAPABILITIES,
    CAPTURE_REQUEST,
    RESOLVED_CAPTURE_RECIPE,
    BUFFER_LIFECYCLE,
    FRAME_CANDIDATES,
    FRAME_SELECTION,
    ALIGNMENT,
    FUSION,
    ISP_EXECUTION,
    OUTPUT_AND_PUBLICATION,
    PERFORMANCE_AND_THERMAL,
    WARNINGS_FALLBACKS_FAILURES,
    VENDOR_TAGS,
    EXCEPTIONS
}


data class CaptureTraceDecision(
    val requested: String?,
    val supported: String?,
    val resolved: String?,
    val executed: String?,
    val result: String?,
    val fallback: Boolean,
    val reason: String?
)

data class CaptureTraceRecord(
    val key: String,
    val value: String? = null,
    val decision: CaptureTraceDecision? = null
)

data class CaptureTraceException(
    val stage: String,
    val type: String,
    val message: String,
    val fingerprint: String
)

data class ArchitectureCaptureTrace(
    val schemaVersion: Int,
    val captureId: String,
    val recipeSchemaVersion: Int,
    val recipeProfileHash: String,
    val sections: Map<CaptureTraceSection, List<CaptureTraceRecord>>,
    val warnings: List<CaptureWarning>,
    val exceptions: List<CaptureTraceException>
) {
    fun toJson(): String = StableJson.encode(
        linkedMapOf(
            "schemaVersion" to schemaVersion,
            "captureId" to captureId,
            "recipeSchemaVersion" to recipeSchemaVersion,
            "recipeProfileHash" to recipeProfileHash,
            "sections" to CaptureTraceSection.entries.associate { section ->
                section.name to sections[section].orEmpty().map { record ->
                    linkedMapOf(
                        "key" to record.key,
                        "value" to record.value,
                        "decision" to record.decision?.let {
                            linkedMapOf(
                                "requested" to it.requested,
                                "supported" to it.supported,
                                "resolved" to it.resolved,
                                "executed" to it.executed,
                                "result" to it.result,
                                "fallback" to it.fallback,
                                "reason" to it.reason
                            )
                        }
                    )
                }
            },
            "warnings" to warnings.map {
                linkedMapOf(
                    "code" to it.code.name,
                    "severity" to it.severity.name,
                    "message" to it.message,
                    "reason" to it.reason
                )
            },
            "exceptions" to exceptions.map {
                linkedMapOf(
                    "stage" to it.stage,
                    "type" to it.type,
                    "message" to it.message,
                    "fingerprint" to it.fingerprint
                )
            }
        )
    )

    fun renderSection(section: CaptureTraceSection): String = buildString {
        appendLine(section.name.replace('_', ' '))
        appendLine("=".repeat(section.name.length))
        val records = sections[section].orEmpty()
        if (records.isEmpty()) appendLine("No records.")
        records.forEach { record ->
            append(record.key)
            append(": ")
            if (record.decision != null) {
                val decision = record.decision
                append(
                    "requested=${decision.requested ?: "none"}; " +
                        "supported=${decision.supported ?: "unknown"}; " +
                        "resolved=${decision.resolved ?: "none"}; " +
                        "executed=${decision.executed ?: "not_recorded"}; " +
                        "result=${decision.result ?: "unknown"}; " +
                        "fallback=${decision.fallback}; " +
                        "reason=${decision.reason ?: "none"}"
                )
            } else {
                append(record.value ?: "none")
            }
            appendLine()
        }
    }
}

class CaptureTraceRecorder(
    private val recipe: CaptureRecipe,
    private val captureId: String
) {
    private val records = linkedMapOf<CaptureTraceSection, MutableList<CaptureTraceRecord>>()
    private val warnings = mutableListOf<CaptureWarning>()
    private val exceptions = mutableListOf<CaptureTraceException>()
    private var completedTrace: ArchitectureCaptureTrace? = null

    init {
        record(CaptureTraceSection.OVERVIEW, "captureId", captureId)
        record(CaptureTraceSection.OVERVIEW, "profileId", recipe.activeProfileIdentifier)
        record(CaptureTraceSection.OVERVIEW, "profileHash", recipe.profileVersionHash)
        record(CaptureTraceSection.OVERVIEW, "frameSource", recipe.frameSource.name)
        record(CaptureTraceSection.OVERVIEW, "captureMode", recipe.captureMode.name)
        record(CaptureTraceSection.HARDWARE_AND_CAPABILITIES, "logicalCameraId", recipe.logicalCameraId)
        record(
            CaptureTraceSection.HARDWARE_AND_CAPABILITIES,
            "physicalCameraId",
            recipe.physicalCameraId ?: "none"
        )
        record(
            CaptureTraceSection.HARDWARE_AND_CAPABILITIES,
            "hardwareOverrideFingerprint",
            recipe.hardwareOverrideFingerprint
        )
        record(CaptureTraceSection.CAPTURE_REQUEST, "outputPolicy", recipe.outputPolicy.name)
        capacity("warmBuffer", recipe.warmBufferResolution)
        capacity("candidateCount", recipe.candidateCountResolution)
        capacity("processingFrameCount", recipe.processingFrameResolution)
        capacity("dngMasterFrameCount", recipe.dngMasterFrameResolution)
        decision(
            section = CaptureTraceSection.OUTPUT_AND_PUBLICATION,
            key = "dngSourcePlanned",
            requested = recipe.dngSource.name,
            supported = recipe.outputPolicy.producesRaw.toString(),
            resolved = recipe.dngSource.name,
            executed = null,
            result = "resolved_at_shutter",
            fallback = false,
            reason = "dng_master_frame_count_${recipe.dngMasterFrameResolution.effectiveValue}"
        )
        method(CaptureTraceSection.FRAME_SELECTION, "frameSelection", recipe.frameSelectionMethod)
        method(CaptureTraceSection.FRAME_SELECTION, "anchorSelection", recipe.anchorSelectionMethod)
        method(CaptureTraceSection.ALIGNMENT, "alignment", recipe.alignmentMethod)
        method(CaptureTraceSection.FUSION, "fusion", recipe.fusionMethod)
        method(CaptureTraceSection.ISP_EXECUTION, "demosaic", recipe.demosaicMethod)
        listOf(
            Triple(
                CaptureTraceSection.FRAME_SELECTION,
                "base_temporal_bias",
                recipe.executionSettings.selection.baseTemporalBias
            ),
            Triple(
                CaptureTraceSection.FRAME_SELECTION,
                "selection_accept_all",
                recipe.executionSettings.selection.acceptAll
            ),
            Triple(
                CaptureTraceSection.FRAME_SELECTION,
                "selection_alignable_only",
                recipe.executionSettings.selection.useAlignableOnly
            ),
            Triple(
                CaptureTraceSection.FRAME_SELECTION,
                "selection_prefer_recent",
                recipe.executionSettings.selection.preferRecent
            ),
            Triple(
                CaptureTraceSection.ALIGNMENT,
                "merge_subpixel",
                recipe.executionSettings.merge.subPixel
            ),
            Triple(
                CaptureTraceSection.ALIGNMENT,
                "merge_linear_interp",
                recipe.executionSettings.merge.linearInterpolation
            )
        ).forEach { (section, key, requestedValue) ->
            decision(
                section = section,
                key = key,
                requested = requestedValue.toString(),
                supported = "false",
                resolved = "unavailable",
                executed = "not_executed",
                result = "not_applicable",
                fallback = false,
                reason = "unavailable_production_setting_preserved_only"
            )
        }
        record(CaptureTraceSection.ISP_EXECUTION, "computeBackend", recipe.computeBackendId)
        record(CaptureTraceSection.PERFORMANCE_AND_THERMAL, "thermalStateAtShutter", recipe.thermalState)
        recipe.capabilityResolutions.forEach {
            decision(
                CaptureTraceSection.HARDWARE_AND_CAPABILITIES,
                it.capability,
                requested = it.requested,
                supported = it.supported,
                resolved = it.resolved,
                executed = null,
                result = "resolved_at_shutter",
                fallback = it.requested != it.resolved,
                reason = it.reason
            )
        }
    }

    @Synchronized
    fun record(section: CaptureTraceSection, key: String, value: Any?) {
        records.getOrPut(section) { mutableListOf() }
            .add(CaptureTraceRecord(key = key, value = value?.toString()))
    }

    @Synchronized
    fun decision(
        section: CaptureTraceSection,
        key: String,
        requested: String?,
        supported: String?,
        resolved: String?,
        executed: String?,
        result: String?,
        fallback: Boolean,
        reason: String?
    ) {
        records.getOrPut(section) { mutableListOf() }.add(
            CaptureTraceRecord(
                key = key,
                decision = CaptureTraceDecision(
                    requested = requested,
                    supported = supported,
                    resolved = resolved,
                    executed = executed,
                    result = result,
                    fallback = fallback,
                    reason = reason
                )
            )
        )
    }

    @Synchronized
    fun warning(warning: CaptureWarning) {
        warnings += warning
    }

    @Synchronized
    fun exception(stage: String, failure: Throwable) {
        val safeMessage = failure.message.orEmpty().take(512)
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest("${failure.javaClass.name}:$safeMessage".toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { "%02x".format(it) }
        exceptions += CaptureTraceException(
            stage = stage,
            type = failure.javaClass.name,
            message = safeMessage,
            fingerprint = fingerprint
        )
    }

    @Synchronized
    fun complete(artifacts: StringCaptureArtifacts): ArchitectureCaptureTrace {
        completedTrace?.let { return it }
        decision(
            section = CaptureTraceSection.OUTPUT_AND_PUBLICATION,
            key = "publication",
            requested = recipe.outputPolicy.name,
            supported = "true",
            resolved = artifacts.publicationResult.name,
            executed = listOfNotNull(
                artifacts.jpegUri?.let { "JPEG" },
                artifacts.dngUri?.let { "DNG" },
                artifacts.smartAssetUri?.let { "SMART_ASSET" }
            ).joinToString("+").ifBlank { "none" },
            result = artifacts.publicationResult.name,
            fallback = artifacts.publicationResult.name == "PARTIAL_SUCCESS",
            reason = artifacts.reason
        )
        record(
            CaptureTraceSection.OUTPUT_AND_PUBLICATION,
            "thumbnailAvailable",
            (artifacts.thumbnailUri != null).toString()
        )
        artifacts.warnings.forEach(::warning)
        warnings.forEach {
            record(
                CaptureTraceSection.WARNINGS_FALLBACKS_FAILURES,
                it.code.name,
                "${it.severity}:${it.reason ?: it.message}"
            )
        }
        exceptions.forEach {
            record(
                CaptureTraceSection.EXCEPTIONS,
                it.stage,
                "${it.type}:${it.message};fingerprint=${it.fingerprint}"
            )
        }
        return ArchitectureCaptureTrace(
            schemaVersion = 1,
            captureId = captureId,
            recipeSchemaVersion = recipe.schemaVersion,
            recipeProfileHash = recipe.profileVersionHash,
            sections = CaptureTraceSection.entries.associateWith {
                records[it].orEmpty().toList()
            },
            warnings = warnings.toList(),
            exceptions = exceptions.toList()
        ).also { completedTrace = it }
    }

    @Synchronized
    fun completeFailure(stage: String, failure: Throwable): ArchitectureCaptureTrace {
        completedTrace?.let { return it }
        exception(stage, failure)
        val reason =
            "${failure.javaClass.simpleName}:${failure.message ?: "no_message"}".take(512)
        return complete(
            PublicationPolicyResolver.resolveStrings(
                outputPolicy = recipe.outputPolicy,
                jpegSucceeded = false,
                jpegUri = null,
                dngSucceeded = false,
                dngUri = null,
                jpegFailureReason = reason,
                dngFailureReason = reason
            )
        )
    }

    private fun capacity(
        key: String,
        capacity: com.bncam.core.capture.FrameCapacityResolution
    ) {
        record(
            CaptureTraceSection.RESOLVED_CAPTURE_RECIPE,
            "$key.runtimeSafeMaximum",
            capacity.runtimeSafeMaximum
        )
        decision(
            section = CaptureTraceSection.RESOLVED_CAPTURE_RECIPE,
            key = key,
            requested = capacity.requestedValue.toString(),
            supported = capacity.runtimeSafeMaximum.toString(),
            resolved = capacity.effectiveValue.toString(),
            executed = null,
            result = capacity.resolutionReason.name,
            fallback = capacity.wasClamped,
            reason = capacity.limitingReasons.joinToString(",").ifBlank { "as_requested" }
        )
    }

    private fun method(
        section: CaptureTraceSection,
        key: String,
        method: com.bncam.core.capture.MethodResolution
    ) {
        decision(
            section = section,
            key = key,
            requested = method.requestedId,
            supported = method.supported.toString(),
            resolved = method.resolvedId,
            executed = null,
            result = method.requestedAvailability?.name,
            fallback = method.fallback,
            reason = method.reason
        )
    }
}
