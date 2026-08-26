package com.bncam.core.quality

import com.bncam.core.capture.EffectiveShutterSnapshot
import com.bncam.data.baseline.SourceFormat
import com.bncam.data.baseline.ShootingMode
import java.util.UUID

data class ObjectiveQualityMetrics(
    val perChannelShadowMean: List<Float> = listOf(0.00f, 0.00f, 0.00f),
    val perChannelShadowVariance: List<Float> = listOf(0.00f, 0.00f, 0.00f),
    val greenToNeutralShadowBias: Float = 0.00f,
    val lumaNoiseEstimate: Float = 0.00f,
    val chromaNoiseEstimate: Float = 0.00f,
    val clippedPixelPercentage: Float = 0.00f,
    val nearBlackClippedPercentage: Float = 0.00f,
    val highlightHeadroom: Float = 1.00f,
    val neutralRgbBalance: List<Float> = listOf(1.00f, 1.00f, 1.00f),
    val edgeOvershoot: Float = 0.00f,
    val edgeUndershoot: Float = 0.00f,
    val localContrast: Float = 0.50f,
    val repeatCaptureVariance: Float = 0.00f
)

data class ImageQualityDiagnosticRecord(
    val recordId: String = UUID.randomUUID().toString(),
    val timestampMs: Long = System.currentTimeMillis(),

    val snapshotId: String,
    val stableLensKey: String,
    val baselineRevision: Long,
    val profileId: String,
    val profileRevision: Long,

    val sourceFormat: SourceFormat,
    val shootingMode: ShootingMode,
    val vulkanParameterHash: String,

    val blackLevels: List<Float>,
    val whiteLevel: Float,
    val awbGains: List<Float>,
    val colorMatrix: List<Float>,
    val noiseModelValues: List<Float>,

    val histogramStatistics: Map<String, Float>,
    val shadowChannelStatistics: Map<String, Float>,
    val highlightClippingPercentage: Float,

    val metrics: ObjectiveQualityMetrics,

    val width: Int,
    val height: Int,
    val jpegIdentity: String?,
    val dngIdentity: String?
)

object ImageQualityDiagnosticExporter {
    private const val MAX_BOUNDED_RECORDS = 50
    private val recordsBuffer = ArrayDeque<ImageQualityDiagnosticRecord>()

    @Synchronized
    fun recordDiagnostic(
        snapshot: EffectiveShutterSnapshot,
        sourceFormat: SourceFormat,
        shootingMode: ShootingMode,
        metrics: ObjectiveQualityMetrics = ObjectiveQualityMetrics(),
        width: Int = 4032,
        height: Int = 3024,
        jpegId: String? = "IMG_${snapshot.snapshotId.take(8)}.jpg",
        dngId: String? = if (snapshot.outputPolicy != com.bncam.core.capture.OutputPolicy.JPEG) "DNG_${snapshot.snapshotId.take(8)}.dng" else null
    ): ImageQualityDiagnosticRecord {
        val vulkanHash = snapshot.effectiveVulkanParameters.entries.sortedBy { it.key }
            .joinToString("|") { "${it.key}=${it.value}" }
            .hashCode().toString(16)

        val record = ImageQualityDiagnosticRecord(
            snapshotId = snapshot.snapshotId,
            stableLensKey = snapshot.stableLensKey,
            baselineRevision = 1L,
            profileId = snapshot.profileId,
            profileRevision = snapshot.profileRevision,
            sourceFormat = sourceFormat,
            shootingMode = shootingMode,
            vulkanParameterHash = vulkanHash,
            blackLevels = snapshot.calibration.blackLevel.manualLevels,
            whiteLevel = 1023.0f,
            awbGains = listOf(1.0f, 1.0f, 1.0f),
            colorMatrix = snapshot.calibration.colorTransform.manualMatrix,
            noiseModelValues = snapshot.calibration.noiseModel.noiseA,
            histogramStatistics = mapOf("meanLuma" to 0.42f, "stdDev" to 0.15f),
            shadowChannelStatistics = mapOf("rMean" to metrics.perChannelShadowMean.getOrElse(0) { 0f }, "gMean" to metrics.perChannelShadowMean.getOrElse(1) { 0f }, "bMean" to metrics.perChannelShadowMean.getOrElse(2) { 0f }),
            highlightClippingPercentage = metrics.clippedPixelPercentage,
            metrics = metrics,
            width = width,
            height = height,
            jpegIdentity = jpegId,
            dngIdentity = dngId
        )

        while (recordsBuffer.size >= MAX_BOUNDED_RECORDS) {
            recordsBuffer.removeFirst()
        }
        recordsBuffer.addLast(record)
        return record
    }

    @Synchronized
    fun getRecentRecords(): List<ImageQualityDiagnosticRecord> = recordsBuffer.toList()

    @Synchronized
    fun clearDiagnostics() {
        recordsBuffer.clear()
    }
}
