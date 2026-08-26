package com.bncam.core.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FocusTrackingAccuracySourceContractTest {
    private fun source(path: String): String = File(path).readText()

    @Test
    fun trackingRunsAtAvailableAnalysisCadenceAndUsesAdaptiveAfRegions() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        assertTrue(manager.contains("val runTracking = wantsTrackingAnalysis"))
        assertFalse(manager.contains("wantsTrackingAnalysis && frameIndex % 2L"))
        assertTrue(manager.contains("FocusTrackingPolicy.adaptiveAfRegionPct"))
        assertTrue(manager.contains("FocusTrackingPolicy.reacquisitionSearchRadius"))
    }

    @Test
    fun trackingSurvivesPipelineRebuildAsSemanticTarget() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        assertTrue(manager.contains("restoreTrackingOwnershipAfterPipelineTransition(\"pipeline_hard_start\")"))
        assertTrue(manager.contains("restoreTrackingOwnershipAfterPipelineTransition(\"pipeline_reset\")"))
        assertTrue(manager.contains("FocusOwner.TRACK_ACQUIRING"))
    }

    @Test
    fun visualStateExposesReacquisitionInsteadOfPretendingSuccess() {
        val screen = source("src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt")
        assertTrue(screen.contains("FocusTrackingPhase.REACQUIRING"))
        assertTrue(screen.contains("FocusTrackingPhase.LOST"))
        assertTrue(screen.contains("PathEffect.dashPathEffect"))
        assertTrue(screen.contains("priorityFaceBounds"))
    }
}
