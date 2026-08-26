package com.bncam.core.tracing

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

object CaptureTraceCollector {

    private val activeTraces = ConcurrentHashMap<String, CopyOnWriteArrayList<CaptureTraceEvent>>()
    private val captureStartTimes = ConcurrentHashMap<String, Long>()
    private val invocationCounters = ConcurrentHashMap<String, InvocationCounter>()

    private fun monotonicNanos(): Long {
        return try {
            SystemClock.elapsedRealtimeNanos()
        } catch (t: Throwable) {
            System.nanoTime()
        }
    }

    fun startTrace(
        captureId: String,
        workId: String,
        profileId: String,
        format: String,
        isMultiFrame: Boolean,
        requestedFusionFrameCount: Int,
        actualFrameCount: Int,
        outputPolicy: String
    ) {
        val startNs = monotonicNanos()
        captureStartTimes[captureId] = startNs
        activeTraces[captureId] = CopyOnWriteArrayList()
        invocationCounters[captureId] = InvocationCounter()

        recordEvent(
            captureId = captureId,
            workId = workId,
            profileId = profileId,
            format = format,
            isMultiFrame = isMultiFrame,
            requestedFusionFrameCount = requestedFusionFrameCount,
            actualFrameCount = actualFrameCount,
            outputPolicy = outputPolicy,
            stageName = "SHUTTER_PRESSED",
            status = "START"
        )
    }

    fun recordEvent(
        captureId: String,
        workId: String,
        profileId: String,
        format: String,
        isMultiFrame: Boolean,
        requestedFusionFrameCount: Int,
        actualFrameCount: Int,
        outputPolicy: String,
        stageName: String,
        queueDepth: Int = 0,
        activeProcessingCount: Int = 0,
        saveQueueCount: Int = 0,
        status: String = "SUCCESS"
    ) {
        val events = activeTraces[captureId] ?: return
        val startNs = captureStartTimes[captureId] ?: monotonicNanos()
        val nowNs = monotonicNanos()
        val elapsedMs = (nowNs - startNs) / 1_000_000.0

        events.add(
            CaptureTraceEvent(
                captureId = captureId,
                workId = workId,
                profileId = profileId,
                format = format,
                isMultiFrame = isMultiFrame,
                requestedFusionFrameCount = requestedFusionFrameCount,
                actualFrameCount = actualFrameCount,
                outputPolicy = outputPolicy,
                stageName = stageName,
                monotonicTimestampNs = nowNs,
                elapsedFromShutterMs = elapsedMs,
                queueDepth = queueDepth,
                activeProcessingCount = activeProcessingCount,
                saveQueueCount = saveQueueCount,
                status = status
            )
        )
    }

    fun incrementCounter(captureId: String, counterName: String) {
        val counter = invocationCounters[captureId] ?: return
        when (counterName) {
            "rawUnpack" -> counter.rawUnpackCount++
            "alignmentGuide" -> counter.alignmentGuideCount++
            "alignment" -> counter.alignmentCount++
            "transformApp" -> counter.transformAppCount++
            "fusion" -> counter.fusionCount++
            "masterCreation" -> counter.masterCreationCount++
            "demosaic" -> counter.demosaicCount++
            "jpegRender" -> counter.jpegRenderCount++
            "jpegEncode" -> counter.jpegEncodeCount++
            "dngConversion" -> counter.dngConversionCount++
            "dngWriter" -> counter.dngWriterCount++
            "mediaStorePublish" -> counter.mediaStorePublishCount++
            "thumbnailDecode" -> counter.thumbnailDecodeCount++
        }
    }

    fun getInvocationCounter(captureId: String): InvocationCounter {
        return invocationCounters[captureId] ?: InvocationCounter()
    }

    fun summarizeTrace(captureId: String): CaptureTraceSummary? {
        val events = activeTraces[captureId] ?: return null
        val counter = invocationCounters[captureId] ?: InvocationCounter()
        if (events.isEmpty()) return null

        val first = events.first()
        val shutterNs = first.monotonicTimestampNs

        fun timeForStage(stage: String): Double {
            val ev = events.find { it.stageName == stage }
            return if (ev != null) (ev.monotonicTimestampNs - shutterNs) / 1_000_000.0 else 0.0
        }

        val totalAllMs = (events.last().monotonicTimestampNs - shutterNs) / 1_000_000.0

        return CaptureTraceSummary(
            captureId = captureId,
            workId = first.workId,
            profileId = first.profileId,
            format = first.format,
            shutterToFrameReadyMs = timeForStage("FRAME_ACQUISITION_COMPLETE"),
            frameReadyToQueueReservedMs = timeForStage("QUEUE_RESERVED") - timeForStage("FRAME_ACQUISITION_COMPLETE"),
            queueWaitMs = timeForStage("WORKER_START") - timeForStage("QUEUE_RESERVED"),
            workerProcessingMs = timeForStage("FUSION_COMPLETE") - timeForStage("WORKER_START"),
            jpegBranchMs = timeForStage("JPEG_ENCODE_COMPLETE") - timeForStage("FUSION_COMPLETE"),
            dngBranchMs = timeForStage("DNG_PAYLOAD_COMPLETE") - timeForStage("FUSION_COMPLETE"),
            saveQueueWaitMs = timeForStage("SAVE_QUEUE_SUBMITTED") - timeForStage("JPEG_ENCODE_COMPLETE"),
            jpegPublishMs = timeForStage("JPEG_WRITE_COMPLETE") - timeForStage("SAVE_QUEUE_SUBMITTED"),
            dngPublishMs = timeForStage("DNG_WRITE_COMPLETE") - timeForStage("JPEG_WRITE_COMPLETE"),
            terminalStateUpdateMs = timeForStage("MEDIASTORE_FINALIZED") - timeForStage("DNG_WRITE_COMPLETE"),
            totalShutterToJpegMs = timeForStage("JPEG_WRITE_COMPLETE"),
            totalShutterToDngMs = timeForStage("DNG_WRITE_COMPLETE"),
            totalShutterToAllCompleteMs = totalAllMs,
            invocationCounts = counter,
            events = events.toList()
        )
    }

    fun clearTrace(captureId: String) {
        activeTraces.remove(captureId)
        captureStartTimes.remove(captureId)
        invocationCounters.remove(captureId)
    }
}
