package com.bncam.core.engine

/**
 * Evidence source for a Camera2 session operation mode.
 *
 * Operation modes are intentionally separated from stream topology. A non-zero mode may only
 * enter the runtime through explicit manual policy or an evidence-backed known-device mapping.
 */
enum class OperationModePolicyKind {
    REGULAR,
    KNOWN_DEVICE_MODE,
    MANUAL_CUSTOM
}

/**
 * Resolved operation-mode decision for one Camera2 session.
 *
 * Android's regular session uses operation mode 0. Non-zero values are vendor/private contracts.
 * Production policy therefore requires either a known device mapping or an explicit manual choice.
 */
data class ResolvedOperationMode(
    val policy: OperationModePolicyKind,
    val operationMode: Int,
    val evidence: String? = null
) {
    init {
        require(operationMode >= 0) { "operationMode must be non-negative" }
        when (policy) {
            OperationModePolicyKind.REGULAR ->
                require(operationMode == 0) { "REGULAR must use operation mode 0" }
            OperationModePolicyKind.KNOWN_DEVICE_MODE,
            OperationModePolicyKind.MANUAL_CUSTOM ->
                require(operationMode != 0) { "$policy requires a non-zero operation mode" }
        }
    }

    val streamMode: StreamSessionMode
        get() = if (operationMode == 0) {
            StreamSessionMode.REGULAR_SESSION
        } else {
            StreamSessionMode.CUSTOM_OPERATION_MODE_SESSION
        }

    companion object {
        fun regular(
            evidence: String = "Android regular Camera2 session"
        ): ResolvedOperationMode = ResolvedOperationMode(
            policy = OperationModePolicyKind.REGULAR,
            operationMode = 0,
            evidence = evidence.trim().takeIf { it.isNotEmpty() }
                ?: "Android regular Camera2 session"
        )

        fun knownDevice(operationMode: Int, evidence: String): ResolvedOperationMode =
            ResolvedOperationMode(
                policy = OperationModePolicyKind.KNOWN_DEVICE_MODE,
                operationMode = operationMode,
                evidence = evidence.trim().takeIf { it.isNotEmpty() }
                    ?: error("KNOWN_DEVICE_MODE requires evidence")
            )

        fun manual(
            operationMode: Int,
            evidence: String = "Explicit manual custom operation mode"
        ): ResolvedOperationMode = ResolvedOperationMode(
            policy = OperationModePolicyKind.MANUAL_CUSTOM,
            operationMode = operationMode,
            evidence = evidence.trim().takeIf { it.isNotEmpty() }
                ?: "Explicit manual custom operation mode"
        )
    }
}
