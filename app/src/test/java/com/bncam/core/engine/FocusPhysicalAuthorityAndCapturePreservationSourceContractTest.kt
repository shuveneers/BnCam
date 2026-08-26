package com.bncam.core.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FocusPhysicalAuthorityAndCapturePreservationSourceContractTest {
    private fun source(path: String): String = File(path).readText()

    @Test
    fun trackingUsesPhysicalCameraGeometryWhenPhysicalRouteOwnsTheStream() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        assertTrue(manager.contains("val physicalChars = physicalId?.let"))
        assertTrue(manager.contains("activePhysicalAfRequestBounds = Rect(mapped.requestBounds)"))
        assertTrue(manager.contains("request.setPhysicalCameraKey(\n                        CaptureRequest.CONTROL_AF_REGIONS"))
        assertTrue(manager.contains("physicalMetadata = if ("))
        assertTrue(manager.contains("val resultForGeometry: CaptureResult = physicalMetadata ?: metadata"))
    }

    @Test
    fun flashAndHdrCopyTheAuthoritativePreviewFocusInsteadOfResettingAf() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        assertTrue(manager.contains("applyAuthoritativeFocusToStillBuilder("))
        assertTrue(manager.contains("reason = \"FLASH_STILL_CAPTURE\""))
        assertTrue(manager.contains("reason = \"FLASH_STILL_POST_ZOOM\""))
        assertTrue(manager.contains("reason = \"HDR_BRACKET_${'$'}{framePlan.role.name}\""))
        assertTrue(manager.contains("focusCaptureContext = focusCaptureContextAtShutter"))
        assertFalse(manager.contains("framePlan.index"))
    }

    @Test
    fun physicalAfStateIsRetiredAcrossPipelineOwnershipChanges() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        assertTrue(manager.contains("clearPhysicalAfRegionState()\n        if (!_focusTrackingActive.value)"))
        assertTrue(manager.contains("clearPhysicalAfRegionState()\n        transitionFocusOwner(FocusOwner.AUTO, \"pipeline_close\")"))
        assertTrue(manager.contains("Physical still AF key rejected; logical mapped fallback applied"))
    }
}
