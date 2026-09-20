package com.bncam.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionOutputCombinationRecoveryPolicyTest {
    @Test
    fun individualPreviewFailureIsNotMaskedByCaptureGeometryFallback() {
        val kind = SessionOutputCombinationRecoveryPolicy.classifyRegularSessionPreflight(
            previewSupported = false,
            captureSupported = true,
            combinedSupported = false
        )
        assertEquals(SessionOutputFailureKind.PREVIEW_OUTPUT_REJECTED, kind)
        assertEquals(
            SessionOutputRecoveryAction.FAIL_WITHOUT_GEOMETRY_RETRY,
            SessionOutputCombinationRecoveryPolicy.actionFor(kind)
        )
    }

    @Test
    fun individualCaptureFailureAdvancesCaptureGeometry() {
        val kind = SessionOutputCombinationRecoveryPolicy.classifyRegularSessionPreflight(
            previewSupported = true,
            captureSupported = false,
            combinedSupported = false
        )
        assertEquals(SessionOutputFailureKind.CAPTURE_OUTPUT_REJECTED, kind)
        assertEquals(
            SessionOutputRecoveryAction.ADVANCE_CAPTURE_GEOMETRY,
            SessionOutputCombinationRecoveryPolicy.actionFor(kind)
        )
    }

    @Test
    fun supportedIndividualsButRejectedPairMeansCombinationConflict() {
        val kind = SessionOutputCombinationRecoveryPolicy.classifyRegularSessionPreflight(
            previewSupported = true,
            captureSupported = true,
            combinedSupported = false
        )
        assertEquals(SessionOutputFailureKind.CORE_OUTPUT_COMBINATION_REJECTED, kind)
    }

    @Test
    fun runtimeFailureAfterPositivePreflightStillGetsOneGeometryFallback() {
        val diagnosis = SessionOutputFailureDiagnosis(
            kind = SessionOutputFailureKind.RUNTIME_REJECTED_DESPITE_PREFLIGHT,
            reason = "runtime rejected"
        )
        assertTrue(diagnosis.geometryRetryUseful)
        assertFalse(
            SessionOutputCombinationRecoveryPolicy.actionFor(diagnosis.kind) ==
                SessionOutputRecoveryAction.FAIL_WITHOUT_GEOMETRY_RETRY
        )
    }

    @Test
    fun vendorAndCustomOutputsRemainOwnedByTheirExistingRecoveryPaths() {
        assertEquals(
            SessionOutputRecoveryAction.RETRY_WITHOUT_OPTIONAL_CUSTOM_RAW,
            SessionOutputCombinationRecoveryPolicy.actionFor(SessionOutputFailureKind.OPTIONAL_CUSTOM_RAW_OUTPUT)
        )
        assertEquals(
            SessionOutputRecoveryAction.DEFER_TO_OPERATION_MODE_AUTHORITY,
            SessionOutputCombinationRecoveryPolicy.actionFor(SessionOutputFailureKind.NON_REGULAR_OPERATION_MODE)
        )
    }
}
