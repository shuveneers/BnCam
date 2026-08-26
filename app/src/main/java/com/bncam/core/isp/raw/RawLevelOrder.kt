package com.bncam.core.isp.raw

/**
 * RAW level-order utilities shared by RAW10 and RAW_SENSOR contracts.
 *
 * Canonical order is [R, Gr, Gb, B]. Mosaic order is sensor-origin
 * [row0col0, row0col1, row1col0, row1col1].
 */
object RawLevelOrder {
    private fun mosaicToCanonicalMap(cfaPattern: Int): IntArray = when (cfaPattern) {
        0 -> intArrayOf(0, 1, 2, 3) // RGGB
        1 -> intArrayOf(1, 0, 3, 2) // GRBG
        2 -> intArrayOf(2, 3, 0, 1) // GBRG
        3 -> intArrayOf(3, 2, 1, 0) // BGGR
        else -> intArrayOf(0, 1, 2, 3)
    }

    fun <T> canonicalToMosaic(canonical: List<T>, cfaPattern: Int): List<T> {
        require(canonical.size >= 4) { "canonical RAW levels require R/Gr/Gb/B" }
        val map = mosaicToCanonicalMap(cfaPattern)
        return List(4) { site -> canonical[map[site]] }
    }

    fun <T> mosaicToCanonical(mosaic: List<T>, cfaPattern: Int): List<T> {
        require(mosaic.size >= 4) { "mosaic RAW levels require four 2x2 sites" }
        val map = mosaicToCanonicalMap(cfaPattern)
        val out = MutableList(4) { mosaic[0] }
        for (site in 0 until 4) out[map[site]] = mosaic[site]
        return out
    }
}
