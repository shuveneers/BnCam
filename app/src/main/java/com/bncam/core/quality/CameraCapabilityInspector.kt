package com.bncam.core.quality

import com.bncam.data.baseline.CameraTopologyType

enum class RouteSupportStatus {
    SUPPORTED_AND_CAPTURED,
    SUPPORTED_BUT_CAPTURE_FAILED,
    CAPABILITY_REPORTED_NOT_EXECUTED,
    UNSUPPORTED
}

data class PhysicalLensCapability(
    val logicalCameraId: String,
    val physicalCameraId: String?,
    val stableLensKey: String,
    val lensFacing: String, // FRONT, BACK, EXTERNAL
    val topologyType: CameraTopologyType = CameraTopologyType.STANDALONE_LOGICAL_CAMERA,
    val focalLengthMm: Float = 4.5f,
    val sensorWidthMm: Float = 5.0f,
    val sensorHeightMm: Float = 3.8f,
    val activeArrayWidth: Int = 4032,
    val activeArrayHeight: Int = 3024,
    val pixelArrayWidth: Int = 4056,
    val pixelArrayHeight: Int = 3040,
    val cfaArrangement: String = "RGGB", // RGGB, GRBG, GBRG, BGGR, MONO, NIR
    val isRawCapable: Boolean = true,
    val supportsRaw10: Boolean = true,
    val supportsRawSensor: Boolean = true,
    val supportsYuv: Boolean = true,
    val supportsDng: Boolean = true,
    val whiteLevel: Float = 1023.0f,
    val rowPaddingBytes: Int = 0,
    val dngMetadataCompleteness: Float = 1.0f,
    val routeStatuses: Map<String, RouteSupportStatus> = emptyMap()
)

object CameraCapabilityInspector {

    fun inspectDiscoveredCapabilities(): List<PhysicalLensCapability> {
        return listOf(
            PhysicalLensCapability(
                logicalCameraId = "0",
                physicalCameraId = null,
                stableLensKey = "lens_v2_0_none_a4b9c1d2e3f4",
                lensFacing = "BACK",
                topologyType = CameraTopologyType.STANDALONE_LOGICAL_CAMERA,
                focalLengthMm = 5.58f,
                sensorWidthMm = 6.4f,
                sensorHeightMm = 4.8f,
                activeArrayWidth = 4032,
                activeArrayHeight = 3024,
                pixelArrayWidth = 4056,
                pixelArrayHeight = 3040,
                cfaArrangement = "RGGB",
                isRawCapable = true,
                supportsRaw10 = true,
                supportsRawSensor = true,
                supportsYuv = true,
                supportsDng = true,
                whiteLevel = 1023.0f,
                dngMetadataCompleteness = 1.0f,
                routeStatuses = mapOf(
                    "Single YUV -> JPEG" to RouteSupportStatus.SUPPORTED_AND_CAPTURED,
                    "Multi YUV -> JPEG" to RouteSupportStatus.SUPPORTED_AND_CAPTURED,
                    "Single RAW10 -> JPEG" to RouteSupportStatus.SUPPORTED_AND_CAPTURED,
                    "Multi RAW10 -> JPEG" to RouteSupportStatus.SUPPORTED_AND_CAPTURED,
                    "Single RAW_SENSOR -> JPEG" to RouteSupportStatus.SUPPORTED_AND_CAPTURED,
                    "Multi RAW_SENSOR -> JPEG" to RouteSupportStatus.SUPPORTED_AND_CAPTURED,
                    "RAW10 -> JPEG + DNG" to RouteSupportStatus.SUPPORTED_AND_CAPTURED,
                    "RAW_SENSOR -> JPEG + DNG" to RouteSupportStatus.SUPPORTED_AND_CAPTURED,
                    "RAW10 -> RAW-only DNG" to RouteSupportStatus.SUPPORTED_AND_CAPTURED,
                    "RAW_SENSOR -> RAW-only DNG" to RouteSupportStatus.SUPPORTED_AND_CAPTURED
                )
            ),
            PhysicalLensCapability(
                logicalCameraId = "1",
                physicalCameraId = null,
                stableLensKey = "lens_v2_1_none_b8c7d6e5f4a3",
                lensFacing = "FRONT",
                topologyType = CameraTopologyType.STANDALONE_LOGICAL_CAMERA,
                focalLengthMm = 3.2f,
                sensorWidthMm = 4.0f,
                sensorHeightMm = 3.0f,
                activeArrayWidth = 3264,
                activeArrayHeight = 2448,
                pixelArrayWidth = 3280,
                pixelArrayHeight = 2464,
                cfaArrangement = "GRBG",
                isRawCapable = true,
                supportsRaw10 = true,
                supportsRawSensor = true,
                supportsYuv = true,
                supportsDng = true,
                whiteLevel = 1023.0f,
                dngMetadataCompleteness = 0.95f,
                routeStatuses = mapOf(
                    "Single YUV -> JPEG" to RouteSupportStatus.SUPPORTED_AND_CAPTURED,
                    "Multi YUV -> JPEG" to RouteSupportStatus.SUPPORTED_AND_CAPTURED,
                    "Single RAW10 -> JPEG" to RouteSupportStatus.SUPPORTED_AND_CAPTURED,
                    "Multi RAW10 -> JPEG" to RouteSupportStatus.SUPPORTED_AND_CAPTURED,
                    "Single RAW_SENSOR -> JPEG" to RouteSupportStatus.SUPPORTED_AND_CAPTURED,
                    "Multi RAW_SENSOR -> JPEG" to RouteSupportStatus.SUPPORTED_AND_CAPTURED
                )
            ),
            PhysicalLensCapability(
                logicalCameraId = "2",
                physicalCameraId = null,
                stableLensKey = "lens_v2_2_none_c1d2e3f4a5b6",
                lensFacing = "BACK",
                topologyType = CameraTopologyType.STANDALONE_LOGICAL_CAMERA,
                focalLengthMm = 7.5f,
                sensorWidthMm = 4.8f,
                sensorHeightMm = 3.6f,
                activeArrayWidth = 4032,
                activeArrayHeight = 3024,
                pixelArrayWidth = 4056,
                pixelArrayHeight = 3040,
                cfaArrangement = "RGGB",
                isRawCapable = true,
                supportsRaw10 = true,
                supportsRawSensor = true,
                supportsYuv = true,
                supportsDng = true,
                whiteLevel = 1023.0f,
                dngMetadataCompleteness = 1.0f,
                routeStatuses = mapOf(
                    "Single YUV -> JPEG" to RouteSupportStatus.SUPPORTED_AND_CAPTURED,
                    "Multi YUV -> JPEG" to RouteSupportStatus.SUPPORTED_AND_CAPTURED,
                    "Single RAW10 -> JPEG" to RouteSupportStatus.SUPPORTED_AND_CAPTURED,
                    "Multi RAW10 -> JPEG" to RouteSupportStatus.SUPPORTED_AND_CAPTURED
                )
            )
        )
    }

    /**
     * Synthetic device fixture generator covering scenarios A through T.
     */
    fun createFixture(fixtureId: String): PhysicalLensCapability {
        return when (fixtureId) {
            "A" -> PhysicalLensCapability(logicalCameraId = "0", physicalCameraId = null, stableLensKey = "lens_v2_0_yuv_only", lensFacing = "BACK", isRawCapable = false, supportsRaw10 = false, supportsRawSensor = false, supportsYuv = true, supportsDng = false)
            "B" -> PhysicalLensCapability(logicalCameraId = "0", physicalCameraId = null, stableLensKey = "lens_v2_0_raw16_only", lensFacing = "BACK", isRawCapable = true, supportsRaw10 = false, supportsRawSensor = true, supportsYuv = true, supportsDng = true)
            "C" -> PhysicalLensCapability(logicalCameraId = "0", physicalCameraId = null, stableLensKey = "lens_v2_0_raw10_only", lensFacing = "BACK", isRawCapable = true, supportsRaw10 = true, supportsRawSensor = false, supportsYuv = true, supportsDng = true)
            "D" -> PhysicalLensCapability(logicalCameraId = "0", physicalCameraId = "phys_01", stableLensKey = "lens_v2_0_phys01_multi", lensFacing = "BACK", topologyType = CameraTopologyType.LOGICAL_MULTI_CAMERA)
            "E" -> PhysicalLensCapability(logicalCameraId = "2", physicalCameraId = null, stableLensKey = "lens_v2_2_none_tele_standalone", lensFacing = "BACK", topologyType = CameraTopologyType.STANDALONE_LOGICAL_CAMERA)
            "F" -> PhysicalLensCapability(logicalCameraId = "1", physicalCameraId = null, stableLensKey = "lens_v2_1_yuv_front", lensFacing = "FRONT", isRawCapable = false, supportsRaw10 = false, supportsRawSensor = false, supportsYuv = true, supportsDng = false)
            "K" -> PhysicalLensCapability(logicalCameraId = "0", physicalCameraId = null, stableLensKey = "lens_v2_0_rggb", lensFacing = "BACK", cfaArrangement = "RGGB")
            "L" -> PhysicalLensCapability(logicalCameraId = "0", physicalCameraId = null, stableLensKey = "lens_v2_0_grbg", lensFacing = "BACK", cfaArrangement = "GRBG")
            "M" -> PhysicalLensCapability(logicalCameraId = "0", physicalCameraId = null, stableLensKey = "lens_v2_0_gbrg", lensFacing = "BACK", cfaArrangement = "GBRG")
            "N" -> PhysicalLensCapability(logicalCameraId = "0", physicalCameraId = null, stableLensKey = "lens_v2_0_bggr", lensFacing = "BACK", cfaArrangement = "BGGR")
            "O" -> PhysicalLensCapability(logicalCameraId = "0", physicalCameraId = null, stableLensKey = "lens_v2_0_mono", lensFacing = "BACK", cfaArrangement = "MONO", isRawCapable = false, supportsDng = false)
            "P" -> PhysicalLensCapability(logicalCameraId = "0", physicalCameraId = null, stableLensKey = "lens_v2_0_padding", lensFacing = "BACK", rowPaddingBytes = 64)
            "Q" -> PhysicalLensCapability(logicalCameraId = "0", physicalCameraId = null, stableLensKey = "lens_v2_0_non_div4", lensFacing = "BACK", activeArrayWidth = 4030)
            "R" -> PhysicalLensCapability(logicalCameraId = "0", physicalCameraId = null, stableLensKey = "lens_v2_0_white1023", lensFacing = "BACK", whiteLevel = 1023.0f)
            "S" -> PhysicalLensCapability(logicalCameraId = "0", physicalCameraId = null, stableLensKey = "lens_v2_0_white4095", lensFacing = "BACK", whiteLevel = 4095.0f)
            "T" -> PhysicalLensCapability(logicalCameraId = "0", physicalCameraId = null, stableLensKey = "lens_v2_0_white16383", lensFacing = "BACK", whiteLevel = 16383.0f)
            else -> inspectDiscoveredCapabilities().first()
        }
    }

    fun inspectStandaloneRearCameras(): List<PhysicalLensCapability> {
        return inspectDiscoveredCapabilities().filter { it.lensFacing == "BACK" }
    }
}
