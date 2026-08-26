package com.bncam.core.tracing

data class CaptureTraceEvent(
    val captureId: String,
    val workId: String,
    val profileId: String,
    val format: String,
    val isMultiFrame: Boolean,
    val requestedFusionFrameCount: Int,
    val actualFrameCount: Int,
    val outputPolicy: String,
    val stageName: String,
    val monotonicTimestampNs: Long,
    val elapsedFromShutterMs: Double,
    val threadId: Long = Thread.currentThread().name.hashCode().toLong(),
    val threadName: String = Thread.currentThread().name,
    val queueDepth: Int = 0,
    val activeProcessingCount: Int = 0,
    val saveQueueCount: Int = 0,
    val freeMemoryBytes: Long = Runtime.getRuntime().freeMemory(),
    val status: String = "SUCCESS"
)

data class InvocationCounter(
    var rawUnpackCount: Int = 0,
    var alignmentGuideCount: Int = 0,
    var alignmentCount: Int = 0,
    var transformAppCount: Int = 0,
    var fusionCount: Int = 0,
    var masterCreationCount: Int = 0,
    var demosaicCount: Int = 0,
    var jpegRenderCount: Int = 0,
    var jpegEncodeCount: Int = 0,
    var dngConversionCount: Int = 0,
    var dngWriterCount: Int = 0,
    var mediaStorePublishCount: Int = 0,
    var thumbnailDecodeCount: Int = 0
)

data class CaptureTraceSummary(
    val captureId: String,
    val workId: String,
    val profileId: String,
    val format: String,
    val shutterToFrameReadyMs: Double,
    val frameReadyToQueueReservedMs: Double,
    val queueWaitMs: Double,
    val workerProcessingMs: Double,
    val jpegBranchMs: Double,
    val dngBranchMs: Double,
    val saveQueueWaitMs: Double,
    val jpegPublishMs: Double,
    val dngPublishMs: Double,
    val terminalStateUpdateMs: Double,
    val totalShutterToJpegMs: Double,
    val totalShutterToDngMs: Double,
    val totalShutterToAllCompleteMs: Double,
    val invocationCounts: InvocationCounter,
    val events: List<CaptureTraceEvent>
)
