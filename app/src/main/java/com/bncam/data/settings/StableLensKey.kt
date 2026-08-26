package com.bncam.data.settings

import java.security.MessageDigest

/**
 * Type-safe wrapper around a normalized stable physical lens key.
 * Guarantees that raw Camera2 camera IDs are converted exactly once at the system boundary.
 */
@JvmInline
value class StableLensKey(val value: String) {
    init {
        require(value.isNotBlank()) { "StableLensKey value cannot be blank" }
    }

    companion object {
        fun fromRawPhysicalId(logicalCameraId: String, physicalCameraId: String? = null): StableLensKey {
            val cleanLogical = logicalCameraId.trim().replace(Regex("[^A-Za-z0-9_.-]"), "_").ifBlank { "0" }
            val normPhysical = physicalCameraId?.trim()?.takeIf { it.isNotBlank() && !it.equals("none", ignoreCase = true) }
            val cleanPhysical = normPhysical?.replace(Regex("[^A-Za-z0-9_.-]"), "_") ?: "none"
            val tuple = "v2#logical=$logicalCameraId#physical=${normPhysical ?: ""}"
            val digest = MessageDigest.getInstance("SHA-256").digest(tuple.toByteArray(Charsets.UTF_8))
            val hash32 = digest.take(16).joinToString("") { "%02x".format(it) }
            return StableLensKey("lens_v2_${cleanLogical}_${cleanPhysical}_$hash32")
        }

        fun fromString(key: String): StableLensKey {
            val trimmed = key.trim()
            if (trimmed.startsWith("lens_v2_")) {
                return StableLensKey(trimmed)
            }
            return fromRawPhysicalId(trimmed)
        }
    }

    override fun toString(): String = value
}
