package com.bncam.core.quality

object RawUnpackValidator {

    /**
     * Decodes a 5-byte block of MIPI RAW10 into four 10-bit integer pixels (0..1023).
     */
    fun unpackMipiRaw10Block(b0: Int, b1: Int, b2: Int, b3: Int, b4: Int): IntArray {
        val p0 = ((b0 and 0xFF) shl 2) or ((b4 and 0x03))
        val p1 = ((b1 and 0xFF) shl 2) or ((b4 and 0x0C) shr 2)
        val p2 = ((b2 and 0xFF) shl 2) or ((b4 and 0x30) shr 4)
        val p3 = ((b3 and 0xFF) shl 2) or ((b4 and 0xC0) shr 6)
        return intArrayOf(p0, p1, p2, p3)
    }

    /**
     * Performs positional 2x2 CFA black level subtraction and normalization.
     * Prevents green channel asymmetry between G_even and G_odd that causes RAW10 shadow-green cast.
     */
    fun subtractPositionalBlackLevel(
        rawPixel: Float,
        row: Int,
        col: Int,
        l1: Float, // R or G1
        l2: Float, // G1 or R
        l3: Float, // G2 or B
        l4: Float, // B or G2
        whiteLevel: Float
    ): Float {
        val blackLevel = when {
            row % 2 == 0 && col % 2 == 0 -> l1
            row % 2 == 0 && col % 2 == 1 -> l2
            row % 2 == 1 && col % 2 == 0 -> l3
            else -> l4
        }
        val range = (whiteLevel - blackLevel).coerceAtLeast(1.0f)
        return ((rawPixel - blackLevel) / range).coerceIn(0.0f, 1.0f)
    }

    /**
     * Calculates noise variance from parametric noise model A and B: variance = A * pixelValue + B.
     */
    fun calculateNoiseVariance(pixelValue: Float, noiseA: Float, noiseB: Float): Float {
        return (noiseA * pixelValue.coerceAtLeast(0.0f) + noiseB).coerceAtLeast(1e-6f)
    }
}
