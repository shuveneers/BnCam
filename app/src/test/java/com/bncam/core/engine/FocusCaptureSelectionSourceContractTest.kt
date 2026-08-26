package com.bncam.core.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class FocusCaptureSelectionSourceContractTest {
    private fun source(path: String): String = File(path).readText()

    @Test
    fun cameraRequestProvenanceCarriesFocusOwner() {
        val provenance = source("src/main/java/com/bncam/core/capture/ControlRequestProvenance.kt")
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        assertTrue(provenance.contains("val focusOwner: String = \"UNKNOWN\""))
        assertTrue(manager.contains("focusOwner = _focusOwnership.value.owner.name"))
        assertTrue(manager.contains("focusCaptureContext = snapshotFocusCaptureContext()"))
    }

    @Test
    fun singleAndMultiFrameSelectionUseTrackedSubjectRoiEvidence() {
        val single = source("src/main/java/com/bncam/core/runners/SingleFrameRunner.kt")
        val multi = source("src/main/java/com/bncam/core/runners/MultiFrameRunner.kt")
        assertTrue(single.contains("TrackedSubjectSelectionPolicy.adjustWeights"))
        assertTrue(single.contains("trackedSubjectEvidence"))
        assertTrue(single.contains("genuine_near_zsl_tracked_subject_roi_focus_weighted"))
        assertTrue(multi.contains("TrackedSubjectAnchorPolicy.selectIndex"))
        assertTrue(multi.contains("tracked_subject_roi_focus"))
    }
}
