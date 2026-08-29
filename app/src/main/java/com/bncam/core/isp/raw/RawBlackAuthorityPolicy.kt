package com.bncam.core.isp.raw

data class RawBlackAuthorityDecision(
    val canonicalLevels: List<Float>,
    val source: String,
    val metadataAuthoritative: Boolean,
    val fallbackReason: String
)

/**
 * One ownership decision for developed RAW black.
 *
 * Camera2 dynamic/static black metadata is authoritative whenever four finite,
 * non-negative mosaic-site values are available. Lens/profile-derived values may
 * only act as the explicit fallback when metadata is absent or invalid.
 *
 * Input metadata is sensor-origin mosaic order [00, 10, 01, 11].
 * Output is canonical [R, Gr, Gb, B].
 */
object RawBlackAuthorityPolicy {
    fun resolve(
        metadataMosaicLevels: List<Float>?,
        metadataSource: String,
        cfaPattern: Int,
        fallbackCanonicalLevels: List<Float>,
        fallbackSource: String
    ): RawBlackAuthorityDecision {
        val metadata = metadataMosaicLevels
            ?.takeIf { it.size >= 4 }
            ?.take(4)
            ?.takeIf { levels -> levels.all { it.isFinite() && it >= 0f } }

        if (metadata != null) {
            return RawBlackAuthorityDecision(
                canonicalLevels = RawLevelOrder.mosaicToCanonical(metadata, cfaPattern),
                source = "METADATA_FIRST: $metadataSource",
                metadataAuthoritative = true,
                fallbackReason = "none"
            )
        }

        val fallback = if (
            fallbackCanonicalLevels.size >= 4 &&
            fallbackCanonicalLevels.take(4).all { it.isFinite() && it >= 0f }
        ) {
            fallbackCanonicalLevels.take(4)
        } else {
            List(4) { 0f }
        }

        return RawBlackAuthorityDecision(
            canonicalLevels = fallback,
            source = "CONTROLLED_FALLBACK: $fallbackSource",
            metadataAuthoritative = false,
            fallbackReason = when {
                metadataMosaicLevels == null -> "camera2_black_metadata_unavailable"
                metadataMosaicLevels.size < 4 -> "camera2_black_metadata_incomplete"
                else -> "camera2_black_metadata_invalid"
            }
        )
    }
}
