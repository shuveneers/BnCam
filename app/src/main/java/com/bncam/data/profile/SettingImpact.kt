package com.bncam.data.profile

enum class SettingImpact(
    val englishBadge: String,
    val dutchBadge: String,
    val description: String
) {
    RAW_BEFORE_DEMOSAIC(
        englishBadge = "RAW Before Demosaic",
        dutchBadge = "RAW vóór demosaic",
        description = "Affects Bayer RAW mosaic data before demosaicing."
    ),
    MASTER_DNG_PIXELS(
        englishBadge = "Master DNG Pixels",
        dutchBadge = "Master DNG-pixels",
        description = "Alters fused master-RAW DNG pixel values."
    ),
    DNG_METADATA(
        englishBadge = "DNG Metadata",
        dutchBadge = "DNG-metadata",
        description = "Written to DNG header/metadata tags without altering RAW pixels."
    ),
    JPEG_LINEAR_PIPELINE(
        englishBadge = "Linear JPEG Pipeline",
        dutchBadge = "Lineaire JPEG-pipeline",
        description = "Affects scene-linear processing before gamma/tone rendering."
    ),
    JPEG_NONLINEAR_PIPELINE(
        englishBadge = "Rendered JPEG",
        dutchBadge = "Gerenderde JPEG",
        description = "Affects nonlinear tone mapping, gamma, sharpening, and color rendering."
    ),
    JPEG_8BIT_OUTPUT(
        englishBadge = "8-Bit JPEG Only",
        dutchBadge = "Alleen 8-bit JPEG",
        description = "Affects only the final 8-bit encoded JPEG output file."
    ),
    JPEG_FRAME_SELECTION(
        englishBadge = "JPEG Frame Selection",
        dutchBadge = "JPEG-framekeuze",
        description = "Affects which and how many frames feed the JPEG-processing pipeline."
    ),
    MASTER_DNG_FRAME_SELECTION(
        englishBadge = "Master DNG Frame Selection",
        dutchBadge = "Master DNG-framekeuze",
        description = "Affects which and how many frames feed the DNG fusion engine."
    )
}
