package com.bncam.core.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ColdRawSingleCaptureContractTest {
    private val manager = File("src/main/java/com/bncam/core/engine/BnCameraManager.kt").readText()
    private val policy = File("src/main/java/com/bncam/core/capture/NearZslAnchorAdmissionPolicy.kt").readText()

    @Test
    fun rawSingleUsesExistingRepeatingProducerInsteadOfDedicatedColdStill() {
        assertFalse(manager.contains("private suspend fun acquireColdRawSingle("))
        assertFalse(manager.contains("RAW_SINGLE_SOURCE=COLD_DIRECT_STILL"))
        assertFalse(manager.contains("Cold RAW still failed:"))
        assertTrue(manager.contains("rawShutterTicket = RawShutterTicket("))
        assertTrue(manager.contains("rawShutterTicket = rawShutterTicket"))
        assertTrue(manager.contains("FIRST_VALID_RAW_REPEATING_FRAME"))
    }

    @Test
    fun rawSingleBypassesWarmReadinessAndAcceptsEmptyRing() {
        val gate = manager.substringAfter("val pipelineReady = if (rawSingle)")
            .substringBefore("else if (pinnedAnchorCanOwnAdmission)")
        assertTrue(gate.contains("requireWarmBuffer = false"))
        assertFalse(gate.contains("waitForPipelineReadyForCapture"))

        val rawRoute = manager.substringAfter(
            "check(pipelineGeneration == singleProducerGeneration) { \"RAW single producer changed\" }"
        ).substringBefore(
            "if (!rawSingle && effectiveCaptureStrategy == CaptureStrategy.SINGLE_FRAME_ZSL"
        )
        assertTrue(rawRoute.contains("healthAtEntry.completeFrames == 0"))
        assertTrue(rawRoute.contains("awaitSingleNearZslAnchor("))
        assertTrue(rawRoute.contains("artificialWarmBufferWaitMs=0"))
        assertFalse(rawRoute.contains("4200L"))
        assertFalse(rawRoute.contains("session.capture("))
    }

    @Test
    fun rawShutterTicketFreezesProducerIdentityWithoutRebuildingTopology() {
        val ticket = manager.substringAfter("private data class RawShutterTicket(")
            .substringBefore("private data class VendorOperationModeProbeState")
        assertTrue(ticket.contains("pipelineGeneration"))
        assertTrue(ticket.contains("sessionConfiguredGeneration"))
        assertTrue(ticket.contains("sessionEpoch"))
        assertTrue(ticket.contains("logicalCameraId"))
        assertTrue(ticket.contains("physicalCameraId"))
        assertTrue(ticket.contains("format"))
        assertTrue(ticket.contains("ringEventSequenceAtShutter"))
        assertTrue(ticket.contains("controlRequestEpochAtShutter"))
        assertTrue(ticket.contains("pendingPairsAtShutter"))

        val await = manager.substringAfter("private suspend fun awaitSingleNearZslAnchor(")
            .substringBefore("private fun shouldRequestDefaultRawShutterMotionAnalysis")
        assertTrue(await.contains("rawTicketStillValid()"))
        assertTrue(await.contains("FIRST_VALID_RAW_REPEATING_FRAME"))
        for (forbidden in listOf("createCaptureSession", "ImageReader.newInstance", "TEMPLATE_STILL_CAPTURE")) {
            assertFalse(forbidden, await.contains(forbidden))
        }
    }

    @Test
    fun warmAndColdSourcesRemainTruthfullyDistinguished() {
        assertTrue(manager.contains("RAW_SINGLE_SOURCE=NEAR_ZSL_PRE_SHUTTER"))
        assertTrue(manager.contains("\"NEAR_ZSL_PRE_SHUTTER_LATE_PAIR\""))
        assertTrue(manager.contains("\"DEGRADED_PRE_SHUTTER\""))
        assertTrue(manager.contains("\"FIRST_VALID_RAW_REPEATING_FRAME\""))
        assertTrue(manager.contains("RAW_SHUTTER_ARTIFICIAL_WARM_BUFFER_WAIT_MS=0"))
    }

    @Test
    fun selectedRawAnchorIsReservedThroughRunnerDispatch() {
        assertTrue(manager.contains("reservedAnchor = preleasedSingleAnchor?.lease?.takeIf { rawSingle }"))
        assertTrue(manager.contains("preleasedSingleAnchor!!.frame.controlRequestEpoch"))
        assertFalse(manager.contains("reservedAnchor = coldSingleAnchor"))
        assertFalse(manager.contains("coldSingleAnchor?.release()"))
    }

    @Test
    fun ordinaryColdRawDeadlineIsSubSecondAndLongExposureIsPhysicalNotWarmup() {
        assertTrue(policy.contains("nominalFirstValidWaitMs"))
        assertTrue(policy.contains("coerceIn(300L, 900L)"))
        assertTrue(policy.contains("physicalFrameMs + 900.0"))
        assertTrue(policy.contains("artificialWarmBufferWaitMs = 0L"))
    }

    @Test
    fun rawShutterFeedbackIsAcceptedBeforeFramePairCompletes() {
        val rawRoute = manager.substringAfter("RAW_SHUTTER_ACCEPTED")
            .substringBefore("if (!rawSingle && effectiveCaptureStrategy == CaptureStrategy.SINGLE_FRAME_ZSL")
        assertTrue(rawRoute.contains("mediaActionSound.play(MediaActionSound.SHUTTER_CLICK)"))
        assertTrue(rawRoute.indexOf("mediaActionSound.play(MediaActionSound.SHUTTER_CLICK)") <
            rawRoute.indexOf("awaitSingleNearZslAnchor("))
    }
}
