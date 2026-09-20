package com.bncam.core.quality

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase11HRegressionHardeningSourceContractTest {
    private val appDir: File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun source(relative: String): String = File(appDir, relative).readText()

    @Test
    fun `stream graph remains sole Camera2 target authority for normal preview and still requests`() {
        val graph = source("src/main/java/com/bncam/core/engine/CurrentBnCamStreamGraph.kt")
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        assertTrue(graph.contains("repeating += BnCamStreamRoleIds.PRIMARY_BUFFER"))
        assertTrue(graph.contains("capture += BnCamStreamRoleIds.PRIMARY_BUFFER"))
        assertTrue(graph.contains("repeating += BnCamStreamRoleIds.RAW_PREVIEW_SUPPORT"))
        assertFalse(graph.contains("capture += BnCamStreamRoleIds.RAW_PREVIEW_SUPPORT"))
        assertTrue(manager.contains("boundStreamConfiguration.repeatingBindings().forEach { binding ->"))
        assertTrue(manager.contains("addResolvedCaptureTargets("))
        assertTrue(manager.contains("CurrentBnCamRequestTargetPolicy.resolve("))
        assertTrue(manager.contains("withRequestGraph(nextRequestGraph)"))
    }

    @Test
    fun `optional RAW preview support never replaces canonical primary fallback or capture ownership`() {
        val graph = source("src/main/java/com/bncam/core/engine/CurrentBnCamStreamGraph.kt")
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        assertTrue(graph.contains("BUFFERED_PRIMARY viewfinder route requires PRIMARY_BUFFER"))
        assertTrue(graph.contains("rawPreviewSupportRepeatingEnabled"))
        assertTrue(manager.contains("customRawPreviewDisabledGeneration"))
        assertTrue(manager.contains("PRIMARY_BUFFER"))
        assertTrue(manager.contains("RAW_PREVIEW_SUPPORT"))
        assertTrue(manager.contains("revokeRawPreviewSupportDisplayAuthority"))
        assertTrue(manager.contains("canonical PRIMARY_BUFFER"))
    }

    @Test
    fun `legacy ETTR production runtime is absent and SensorExposureAuthority is the acquisition owner`() {
        assertFalse(File(appDir, "src/main/java/com/bncam/core/capture/EttrExposureStrategy.kt").exists())
        val authority = source("src/main/java/com/bncam/core/capture/SensorExposureAuthority.kt")
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        assertFalse(authority.contains("estimatedShiftEv"))
        assertFalse(authority.contains("maxShiftEv * 0.65"))
        assertTrue(authority.contains("object SensorExposureObserver"))
        assertTrue(authority.contains("object SensorExposurePolicy"))
        assertTrue(authority.contains("object SensorExposureAllocator"))
        assertTrue(manager.contains("applySensorExposureAuthority(builder, characteristics, explicitManual)"))
        assertTrue(manager.contains("CaptureRequest.SENSOR_EXPOSURE_TIME"))
        assertTrue(manager.contains("CaptureRequest.SENSOR_SENSITIVITY"))
    }

    @Test
    fun `preview has no second active global exposure tone or retired profile NR authority`() {
        val full = source("src/main/cpp/vulkan/shaders/raw_preview.comp")
        val fast = source("src/main/cpp/vulkan/shaders/raw_preview_image.comp")
        val backend = source("src/main/cpp/vulkan/VulkanRawPreviewBackend.cpp")
        val combined = full + "\n" + fast + "\n" + backend

        assertFalse(combined.contains("applyPreviewBroadShadowPlacement"))
        assertFalse(combined.contains("logSceneShape"))
        assertFalse(combined.contains("DETAIL_NR_LUMINANCE"))
        assertFalse(combined.contains("DETAIL_NR_COLOR"))
        assertEquals(0, Regex("spatialExposureEvAtPreview\\s*\\(").findAll(full).count()) // retired implementation removed
        assertFalse(backend.contains("push.mode = 5"))
        assertFalse(backend.contains("push.mode = 6"))
        assertFalse(backend.contains("push.mode=5"))
        assertFalse(backend.contains("push.mode=6"))
    }

    @Test
    fun `RAW preview completion remains asynchronous and only shutdown may block`() {
        val backend = source("src/main/cpp/vulkan/VulkanRawPreviewBackend.cpp")
        assertTrue(backend.contains("vkGetFenceStatus"))
        val actualWaits = Regex("const VkResult completion = vkWaitForFences\\s*\\(").findAll(backend).count()
        assertEquals(1, actualWaits)
        val waitForIdle = backend.indexOf("bool VulkanRawPreviewBackend::waitForIdle(")
        val actualWait = backend.indexOf("const VkResult completion = vkWaitForFences", startIndex = waitForIdle)
        assertTrue(waitForIdle >= 0 && actualWait > waitForIdle)
    }

    @Test
    fun `lens and profile switching preserve the hardened session lifecycle`() {
        val manager = source("src/main/java/com/bncam/core/engine/BnCameraManager.kt")
        val cameraScreen = source("src/main/java/com/bncam/ui/screens/capture/CameraScreen.kt")
        val focusView = source("src/main/java/com/bncam/ui/screens/capture/FocusPeakingView.kt")

        val hardClose = manager.substringAfter("private suspend fun closeCameraOwned(")
            .substringBefore("private fun enqueueCameraClose(")
        assertTrue(hardClose.indexOf("awaitCameraDeviceClosed") < hardClose.indexOf("awaitCaptureSessionClosed"))
        assertTrue(hardClose.contains("CAPTURE_SESSION_CLOSE_IMPLIED_BY_DEVICE_ACK"))

        assertTrue(cameraScreen.contains("if (!bufferChanged)"))
        assertTrue(cameraScreen.contains("Viewfinder stream changes (YUV <-> Selected buffer) are handled atomically"))
        assertTrue(cameraScreen.contains("forceSessionRebuild = false"))
        assertTrue(cameraScreen.contains("reason = \"PROFILE_BUFFER_CHANGED_"))
        assertTrue(focusView.contains("if (pending.targetAuthorityAccepted)"))
        assertTrue(cameraScreen.contains("reason=target_frame_presented"))
    }

    @Test
    fun `capture and preview retain one development owner order`() {
        val isp = source("src/main/cpp/IspCore.cpp")
        val full = source("src/main/cpp/vulkan/shaders/raw_preview.comp")
        val fast = source("src/main/cpp/vulkan/shaders/raw_preview_image.comp")
        assertTrue(isp.contains("resolveGlobalSceneExposurePlan"))
        assertTrue(isp.contains("resolveGlobalToneMappingPlan"))
        assertTrue(full.contains("resolvePreviewSceneExposurePolicy"))
        assertTrue(fast.contains("resolvePreviewSceneExposurePolicy"))
        assertTrue(full.contains("applyPreviewGtm"))
        assertTrue(fast.contains("applyPreviewGtm"))
        assertTrue(full.contains("pbrNeutralToneMapping"))
        assertTrue(fast.contains("khronosNeutral"))
    }
    @Test
    fun `multi frame registry resolution keeps nullable candidate compile safe`() {
        val registries = source("src/main/java/com/bncam/core/capture/MultiFrameRegistries.kt")
        assertTrue(registries.contains("requested == auto -> MethodResolution("))
        assertTrue(registries.contains("requestedAvailability = auto.availability"))
        assertTrue(registries.contains("requested != null &&"))
        assertFalse(registries.contains("val autoRequested = requested == auto"))
        assertFalse(registries.contains("val explicitlySelectable = requested?.isSelectableInPhase5A == true"))
    }

}
