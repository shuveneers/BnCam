package com.bncam.core.engine

/**
 * Runtime policy for Camera2 SessionConfiguration operation modes.
 *
 * The Android regular session is always mode 0. Vendor/custom operation modes are accepted only
 * from an explicit manual selection or from an evidence-backed known-device mapping supplied by
 * the caller. The resolver intentionally has no probing/search behavior: arbitrary vendor integers must never be walked at
 * session creation time or after configure failure.
 */
class SessionOperationModePolicyException(message: String) : IllegalArgumentException(message)

data class SessionOperationModePolicyInput(
    val explicitOperationModes: List<Int> = emptyList(),
    val knownDeviceOperationMode: Int? = null,
    val knownDeviceEvidence: String? = null
)

object SessionOperationModePolicy {
    const val VENDOR_OPERATION_MODE_START: Int = 0x8000

    fun resolve(input: SessionOperationModePolicyInput): ResolvedOperationMode {
        val explicit = input.explicitOperationModes.distinct()
        if (explicit.size > 1) {
            throw SessionOperationModePolicyException(
                "Conflicting explicit Camera2 operation modes are not allowed: ${explicit.sorted()}"
            )
        }

        explicit.singleOrNull()?.let { mode ->
            return when {
                mode == 0 -> ResolvedOperationMode.regular(
                    evidence = "Explicit manual selection requested Android regular session mode 0"
                )

                mode >= VENDOR_OPERATION_MODE_START -> ResolvedOperationMode.manual(
                    operationMode = mode,
                    evidence = "Explicit manual custom Camera2 operation mode"
                )

                else -> throw SessionOperationModePolicyException(
                    "Explicit Camera2 operation mode $mode is neither SESSION_REGULAR (0) nor " +
                        "a vendor/custom mode >= 0x${VENDOR_OPERATION_MODE_START.toString(16).uppercase()}"
                )
            }
        }

        input.knownDeviceOperationMode?.let { mode ->
            return when {
                mode == 0 -> ResolvedOperationMode.regular(
                    evidence = "Known-device mapping resolves to Android regular session mode 0"
                )

                mode >= VENDOR_OPERATION_MODE_START -> ResolvedOperationMode.knownDevice(
                    operationMode = mode,
                    evidence = input.knownDeviceEvidence?.trim()?.takeIf { it.isNotEmpty() }
                        ?: "Evidence-backed known-device Camera2 operation-mode mapping"
                )

                else -> throw SessionOperationModePolicyException(
                    "Known-device Camera2 operation mode $mode is outside the accepted vendor/custom range"
                )
            }
        }

        return ResolvedOperationMode.regular()
    }
}
