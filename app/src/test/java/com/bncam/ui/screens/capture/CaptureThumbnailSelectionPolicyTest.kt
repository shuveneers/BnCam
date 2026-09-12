package com.bncam.ui.screens.capture

import com.bncam.core.output.CaptureWorkSnapshot
import com.bncam.core.output.CaptureWorkState
import kotlin.test.Test
import kotlin.test.assertEquals

class CaptureThumbnailSelectionPolicyTest {
    private fun snapshot(
        startedNs: Long,
        state: CaptureWorkState,
        jpegUri: String? = null,
        dngUri: String? = null,
        temporaryPreviewPath: String? = null
    ) = CaptureWorkSnapshot(
        workId = startedNs,
        shotSequenceId = startedNs,
        route = "test",
        state = state,
        progress = if (state == CaptureWorkState.PUBLISHED) 1f else 0.5f,
        captureStartedNs = startedNs,
        stateChangedNs = startedNs,
        temporaryPreviewPath = temporaryPreviewPath,
        jpegUri = jpegUri,
        dngUri = dngUri,
        thumbnailUri = jpegUri
    )

    @Test
    fun `new shutter preview outranks an older completed capture`() {
        val model = resolveCaptureThumbnailModel(
            latestSnapshot = snapshot(100L, CaptureWorkState.PUBLISHED, jpegUri = "old.jpg"),
            publishedModel = "old.jpg",
            publishedCaptureStartedNs = 100L,
            immediateShutterPreviewPath = "new-shutter.jpg",
            immediateShutterStartedNs = 200L
        )
        assertEquals("new-shutter.jpg", model)
    }

    @Test
    fun `published jpeg replaces the matching shutter preview`() {
        val model = resolveCaptureThumbnailModel(
            latestSnapshot = snapshot(200L, CaptureWorkState.PUBLISHED, jpegUri = "final.jpg"),
            publishedModel = "old.jpg",
            publishedCaptureStartedNs = 100L,
            immediateShutterPreviewPath = "shutter.jpg",
            immediateShutterStartedNs = 200L
        )
        assertEquals("final.jpg", model)
    }

    @Test
    fun `dng only retains shutter frame until decoded dng thumbnail is ready`() {
        val dng = snapshot(300L, CaptureWorkState.PUBLISHED, dngUri = "final.dng")
        assertEquals(
            "shutter.jpg",
            resolveCaptureThumbnailModel(dng, "old.jpg", 100L, "shutter.jpg", 300L)
        )
        assertEquals(
            "decoded-dng.jpg",
            resolveCaptureThumbnailModel(dng, "decoded-dng.jpg", 300L, null, 300L)
        )
    }
}
