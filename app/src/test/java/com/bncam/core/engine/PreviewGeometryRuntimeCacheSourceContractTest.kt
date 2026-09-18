package com.bncam.core.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewGeometryRuntimeCacheSourceContractTest {
    private fun appRoot(): File = sequenceOf(File("."), File("app"))
        .firstOrNull { File(it, "src/main/java/com/bncam/core/engine/BnCameraManager.kt").isFile }
        ?: error("Cannot locate app module from ${File(".").absolutePath}")

    private fun manager(): String =
        File(appRoot(), "src/main/java/com/bncam/core/engine/BnCameraManager.kt").readText()

    private fun cache(): String =
        File(appRoot(), "src/main/java/com/bncam/core/engine/PreviewGeometryRuntimeCache.kt").readText()

    @Test
    fun `cache is runtime only and stream plan scoped`() {
        val source = cache()
        assertTrue(source.contains("selectedLensId"))
        assertTrue(source.contains("logicalCameraId"))
        assertTrue(source.contains("physicalCameraId"))
        assertTrue(source.contains("cameraRouteKind"))
        assertTrue(source.contains("backendRoute"))
        assertTrue(source.contains("captureFormat"))
        assertTrue(source.contains("captureWidth"))
        assertTrue(source.contains("captureHeight"))
        assertTrue(source.contains("vendorConfigSignature"))
        assertTrue(source.contains("rawPreviewBindingSignature"))
        assertFalse(source.contains("DataStore"))
        assertFalse(source.contains("SharedPreferences"))
        assertFalse(source.contains("SettingsRepository"))
    }

    @Test
    fun `recovered geometry is promoted only from first producer readiness`() {
        val source = manager()
        val ready = source.indexOf("if (imageAdvanced && metadataAdvanced)")
        val promotion = source.indexOf("promotePendingPreviewGeometryIfReady(generation, identity)", ready)
        val configured = source.indexOf("override fun onConfigured(", 0)
        assertTrue(ready >= 0)
        assertTrue(promotion > ready)
        assertTrue(configured >= 0)
        val configuredBlock = source.substring(configured, minOf(source.length, configured + 9000))
        assertFalse(configuredBlock.contains("previewGeometryRuntimeCache.promote("))
    }

    @Test
    fun `cache hit is capability and HAL revalidated before reuse`() {
        val source = manager()
        val start = source.indexOf("private fun validatedCachedPreviewGeometry(")
        val end = source.indexOf("private suspend fun applyCachedPreviewGeometryIfAvailable(", start)
        val helper = source.substring(start, end)
        assertTrue(helper.contains("CameraStreamGeometryPolicy.fullFovCandidates("))
        assertTrue(helper.contains("CameraSessionPreflight.validateRegularSession("))
        assertTrue(helper.contains("StreamCandidateValidationStatus.SESSION_VALIDATED"))
        assertTrue(helper.contains("previewGeometryRuntimeCache.invalidate(key)"))
    }

    @Test
    fun `hard start consults cache before camera session creation`() {
        val source = manager()
        val hardStart = source.indexOf("private suspend fun startCameraAndZslOwned(")
        val cacheApply = source.indexOf("applyCachedPreviewGeometryIfAvailable(", hardStart)
        val sessionCreate = source.indexOf("createCaptureSession(", cacheApply)
        assertTrue(hardStart >= 0)
        assertTrue(cacheApply > hardStart)
        assertTrue(sessionCreate > cacheApply)
    }

    @Test
    fun `producer reset closes old session before cached surface resize`() {
        val source = manager()
        val reset = source.indexOf("private suspend fun performSoftResetPipeline(")
        val close = source.indexOf("requestCaptureSessionClose(", reset)
        val await = source.indexOf("awaitCaptureSessionClosed(it)", close)
        val resize = source.indexOf("\"RUNTIME_CACHE_REUSE:PIPELINE_PRODUCER_RESET\"", await)
        assertTrue(reset >= 0)
        assertTrue(close > reset)
        assertTrue(await > close)
        assertTrue(resize > await)
    }

    @Test
    fun `runtime rejection invalidates previously cached geometry`() {
        val source = manager()
        val retry = source.indexOf("private suspend fun retryConfigureFailureWithPreviewGeometry(")
        val invalidate = source.indexOf("reason=runtime_preview_output_rejected", retry)
        val candidate = source.indexOf("nextPreviewGeometryRecoveryCandidate(", retry)
        assertTrue(retry >= 0)
        assertTrue(invalidate > retry)
        assertTrue(candidate > invalidate)
    }
}
