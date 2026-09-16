package com.bncam.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlackLevelLockPolicyTest {
    @Test
    fun `only still and still-zsl repeating requests ask for lock`() {
        assertTrue(BlackLevelLockPolicy.shouldRequest(BlackLevelLockRequestStage.STILL_CAPTURE, false))
        assertTrue(BlackLevelLockPolicy.shouldRequest(BlackLevelLockRequestStage.REPEATING_REQUEST, true))
        assertFalse(BlackLevelLockPolicy.shouldRequest(BlackLevelLockRequestStage.REPEATING_REQUEST, false))
        assertFalse(BlackLevelLockPolicy.shouldRequest(BlackLevelLockRequestStage.SESSION, true))
    }

    @Test
    fun `warm repeating frame is locked only when its own result reports true`() {
        val decision = BlackLevelLockPolicy.resolve(
            requestState = BlackLevelLockRequestState.REQUESTED,
            resultReportedLocked = true,
            submissionType = BlackLevelLockPolicy.SUBMISSION_REPEATING
        )
        assertEquals(BlackLevelLockFrameCoverage.WARM_REPEATING_FRAME, decision.coverage)
        assertEquals(BlackLevelLockResultState.REPORTED_TRUE, decision.resultState)
        assertTrue(decision.actuallyLocked)
    }

    @Test
    fun `requested lock with false result is never presented as locked`() {
        val decision = BlackLevelLockPolicy.resolve(
            requestState = BlackLevelLockRequestState.REQUESTED,
            resultReportedLocked = false,
            submissionType = BlackLevelLockPolicy.SUBMISSION_BURST
        )
        assertEquals(BlackLevelLockResultState.REPORTED_FALSE, decision.resultState)
        assertEquals(BlackLevelLockFrameCoverage.NEW_CAPTURE_REQUEST_FRAME, decision.coverage)
        assertFalse(decision.actuallyLocked)
    }

    @Test
    fun `requested lock without result remains unverified`() {
        val decision = BlackLevelLockPolicy.resolve(
            requestState = BlackLevelLockRequestState.REQUESTED,
            resultReportedLocked = null,
            submissionType = BlackLevelLockPolicy.SUBMISSION_ONE_SHOT
        )
        assertEquals(BlackLevelLockResultState.NOT_REPORTED, decision.resultState)
        assertFalse(decision.actuallyLocked)
    }

    @Test
    fun `old warm frame is not retroactively covered by later capture request`() {
        val oldWarmFrame = BlackLevelLockPolicy.resolve(
            requestState = BlackLevelLockRequestState.NOT_REQUESTED,
            resultReportedLocked = null,
            submissionType = BlackLevelLockPolicy.SUBMISSION_REPEATING
        )
        val laterStillFrame = BlackLevelLockPolicy.resolve(
            requestState = BlackLevelLockRequestState.REQUESTED,
            resultReportedLocked = true,
            submissionType = BlackLevelLockPolicy.SUBMISSION_ONE_SHOT
        )
        assertEquals(BlackLevelLockFrameCoverage.NOT_REQUESTED_FRAME, oldWarmFrame.coverage)
        assertFalse(oldWarmFrame.actuallyLocked)
        assertEquals(BlackLevelLockFrameCoverage.NEW_CAPTURE_REQUEST_FRAME, laterStillFrame.coverage)
        assertTrue(laterStillFrame.actuallyLocked)
    }

    @Test
    fun `unsupported request stays separate from result truth`() {
        val decision = BlackLevelLockPolicy.resolve(
            requestState = BlackLevelLockRequestState.UNSUPPORTED,
            resultReportedLocked = null,
            submissionType = BlackLevelLockPolicy.SUBMISSION_REPEATING
        )
        assertEquals(BlackLevelLockRequestState.UNSUPPORTED, decision.requestState)
        assertEquals(BlackLevelLockResultState.NOT_REPORTED, decision.resultState)
        assertFalse(decision.actuallyLocked)
    }
}
