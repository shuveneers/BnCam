package com.bncam.core.quality

/** Testable mirror of the native Camera2 YUV identity-luma contract. RAW rendering never uses this. */
object NeutralYuvToneMapper {
    const val TONE_MODE = "CAMERA2_YUV_IDENTITY_LUMA"

    fun map(input: Float): Float = input.coerceIn(0f, 1f)

    fun buildLut(): IntArray = IntArray(256) { it }

    fun toneModeDescription(): String =
        "$TONE_MODE;rawToneDefaultsUsed=false;lumaOnly=true;camera2VendorTonePreserved=true"
}
