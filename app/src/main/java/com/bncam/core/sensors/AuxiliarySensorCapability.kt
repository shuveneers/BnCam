package com.bncam.core.sensors

/**
 * Universal capability classification for auxiliary phone sensors and illuminant sources.
 * Does not depend on any specific device model, manufacturer, or vendor name.
 */
enum class AuxiliarySensorCapability {
    UNSUPPORTED,
    ILLUMINANCE_ONLY,
    CCT,
    RGB,
    RGBC,
    RGBW,
    XYZ,
    SPECTRAL,
    CAMERA2_VENDOR_ILLUMINANT,
    UNKNOWN_LAYOUT
}
