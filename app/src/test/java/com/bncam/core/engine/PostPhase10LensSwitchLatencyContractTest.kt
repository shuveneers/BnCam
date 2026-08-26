package com.bncam.core.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class PostPhase10LensSwitchLatencyContractTest {
    private fun read(relative: String): String {
        val root = requireNotNull(System.getProperty("user.dir"))
        return File(root, relative).readText()
    }

    @Test
    fun warmReuseAndPhysicalHandoverRejectImpossibleRoutesBeforeIdentityBuild() {
        val source = read("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val reattach = source.substringAfter("suspend fun reattachPreviewToWarmPipeline(")
            .substringBefore("private fun finalizeHardCloseResources")
        assertTrue(reattach.indexOf("activeLensId != cameraId") < reattach.indexOf("buildPipelineIdentity(cameraId"))

        val handover = source.substringAfter("suspend fun handoverToLensWithinOpenLogicalCamera(")
            .substringBefore("suspend fun reattachPreviewToWarmPipeline(")
        assertTrue(handover.indexOf("PHYSICAL_LENS_HANDOVER_FAST_REJECT") < handover.indexOf("buildPipelineIdentity(cameraId"))
    }

    @Test
    fun internalHardReplacementRetainsCameraWorkerAndNavigationPublishesBeforePersistence() {
        val manager = read("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        assertTrue(manager.contains("retainBackgroundThread = true"))
        assertTrue(manager.contains("CAMERA_BACKGROUND_THREAD_RETAINED"))

        val navigation = read("src/main/java/com/bncam/ui/navigation/AppNavigation.kt")
        val switchBlock = navigation.substringAfter("onLensSelected = { selectedLens ->")
        assertTrue(switchBlock.indexOf("activeLens = selectedLens") < switchBlock.indexOf("settingsRepo.setActiveLensAndProfile"))
        assertTrue(navigation.contains("profileCountByLens"))
    }
}
