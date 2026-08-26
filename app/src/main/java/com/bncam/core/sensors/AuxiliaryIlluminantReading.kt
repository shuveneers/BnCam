package com.bncam.core.sensors

/**
 * Device-independent, normalized immutable model representing an auxiliary illuminant reading.
 */
data class AuxiliaryIlluminantReading(
    val sourceId: String = "none",
    val capabilityType: AuxiliarySensorCapability = AuxiliarySensorCapability.UNSUPPORTED,
    val timestampNs: Long = 0L,
    val cctKelvin: Float? = null,
    val tintEstimate: Float? = null,
    val illuminanceLux: Float? = null,
    val confidence: Float = 0.0f,
    val stable: Boolean = false,
    val obstructed: Boolean = false,
    val rawValues: FloatArray = FloatArray(0)
) {
    val isValid: Boolean
        get() = capabilityType != AuxiliarySensorCapability.UNSUPPORTED &&
                capabilityType != AuxiliarySensorCapability.ILLUMINANCE_ONLY &&
                capabilityType != AuxiliarySensorCapability.UNKNOWN_LAYOUT &&
                !obstructed &&
                confidence > 0.0f &&
                cctKelvin != null &&
                cctKelvin >= 1500.0f &&
                cctKelvin <= 12000.0f

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AuxiliaryIlluminantReading
        if (sourceId != other.sourceId) return false
        if (capabilityType != other.capabilityType) return false
        if (timestampNs != other.timestampNs) return false
        if (cctKelvin != other.cctKelvin) return false
        if (confidence != other.confidence) return false
        return true
    }

    override fun hashCode(): Int {
        var result = sourceId.hashCode()
        result = 31 * result + capabilityType.hashCode()
        result = 31 * result + timestampNs.hashCode()
        result = 31 * result + (cctKelvin?.hashCode() ?: 0)
        result = 31 * result + confidence.hashCode()
        return result
    }
}
