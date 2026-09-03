package com.bncam.core.isp.raw10

/** JNI transport only. Calibration validation/selection remains fail-closed on native side. */
object RawCameraColorProfileNativeBridge {
    @JvmStatic
    external fun nativeInstallProfile(
        profileId: String,
        sourcePriority: Int,
        calibrationIlluminant1: Int,
        calibrationIlluminant2: Int,
        colorMatrix1: FloatArray,
        colorMatrix2: FloatArray?,
        cameraCalibration1: FloatArray?,
        cameraCalibration2: FloatArray?,
        forwardMatrix1: FloatArray?,
        forwardMatrix2: FloatArray?,
        analogBalance: FloatArray,
        discoveryEffectiveCcm: FloatArray,
        hueDivisions: Int,
        saturationDivisions: Int,
        valueDivisions: Int,
        encoding: Int,
        hueSatData1: FloatArray?,
        hueSatData2: FloatArray?
    ): Boolean
}
