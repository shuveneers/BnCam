package com.bncam.core.runtime

/**
 * Exact RAW-viewfinder frame provenance.
 *
 * Sensor timestamp alone is not a unique frame key once PRIMARY_BUFFER and RAW_PREVIEW_SUPPORT
 * are both attached to the same Camera2 session: the two outputs may legitimately carry the same
 * SENSOR_TIMESTAMP. Producer identity therefore participates in every renderer/GL/EGL diagnostic
 * key and liveness hand-off.
 */
data class RawPreviewProducerFrameIdentity(
    val generation: Int,
    val sensorTimestampNs: Long,
    val producerKind: RawPreviewProducerKind
) {
    init {
        require(generation >= 0) { "generation must be non-negative" }
        require(sensorTimestampNs > 0L) { "sensorTimestampNs must be positive" }
    }

    fun summary(): String =
        "generation=$generation;sensorTimestampNs=$sensorTimestampNs;producer=${producerKind.name}"
}
