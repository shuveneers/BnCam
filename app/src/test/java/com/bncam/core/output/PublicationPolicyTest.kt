package com.bncam.core.output

import com.bncam.core.capture.OutputPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicationPolicyTest {

    @Test(expected = IllegalArgumentException::class)
    fun dngCannotBeConstructedAsThumbnail() {
        StringCaptureArtifacts(
            dngUri = "content://media/dng/1",
            thumbnailUri = "content://media/dng/1",
            publicationResult = CapturePublicationResult.FULL_SUCCESS
        )
    }

    @Test
    fun test1_jpegRequested_jpegSucceeds() {
        val outputs = PublicationPolicyResolver.resolveStrings(
            outputPolicy = OutputPolicy.JPEG,
            jpegSucceeded = true,
            jpegUri = "content://media/external/images/media/101",
            dngSucceeded = false,
            dngUri = null
        )
        assertEquals(CapturePublicationResult.FULL_SUCCESS, outputs.publicationResult)
        assertEquals("content://media/external/images/media/101", outputs.jpegUri)
        assertNull(outputs.dngUri)
        assertEquals("content://media/external/images/media/101", outputs.thumbnailUri)
    }

    @Test
    fun test2_jpegRequested_jpegFails() {
        val outputs = PublicationPolicyResolver.resolveStrings(
            outputPolicy = OutputPolicy.JPEG,
            jpegSucceeded = false,
            jpegUri = "content://media/external/images/media/101_pending",
            dngSucceeded = false,
            dngUri = null,
            jpegFailureReason = "encode_failed"
        )
        assertEquals(CapturePublicationResult.FAILURE, outputs.publicationResult)
        assertNull(outputs.jpegUri)
        assertNull(outputs.dngUri)
        assertNull(outputs.thumbnailUri)
        assertEquals("encode_failed", outputs.reason)
    }

    @Test
    fun test3_jpegPlusRaw_bothSucceed() {
        val outputs = PublicationPolicyResolver.resolveStrings(
            outputPolicy = OutputPolicy.JPEG_PLUS_RAW,
            jpegSucceeded = true,
            jpegUri = "content://media/external/images/media/101",
            dngSucceeded = true,
            dngUri = "content://media/external/images/media/102"
        )
        assertEquals(CapturePublicationResult.FULL_SUCCESS, outputs.publicationResult)
        assertEquals("content://media/external/images/media/101", outputs.jpegUri)
        assertEquals("content://media/external/images/media/102", outputs.dngUri)
        assertEquals("content://media/external/images/media/101", outputs.thumbnailUri)
    }

    @Test
    fun test4_jpegPlusRaw_jpegSucceeds_dngFails() {
        val outputs = PublicationPolicyResolver.resolveStrings(
            outputPolicy = OutputPolicy.JPEG_PLUS_RAW,
            jpegSucceeded = true,
            jpegUri = "content://media/external/images/media/101",
            dngSucceeded = false,
            dngUri = null,
            dngFailureReason = "DngCreator failed"
        )
        assertEquals(CapturePublicationResult.PARTIAL_SUCCESS, outputs.publicationResult)
        assertEquals("content://media/external/images/media/101", outputs.jpegUri)
        assertNull(outputs.dngUri)
        assertEquals("content://media/external/images/media/101", outputs.thumbnailUri)
        assertEquals("DngCreator failed", outputs.reason)
    }

    @Test
    fun test5_jpegPlusRaw_jpegFails_dngSucceeds() {
        val outputs = PublicationPolicyResolver.resolveStrings(
            outputPolicy = OutputPolicy.JPEG_PLUS_RAW,
            jpegSucceeded = false,
            jpegUri = null,
            dngSucceeded = true,
            dngUri = "content://media/external/images/media/102",
            jpegFailureReason = "JPEG encode failed"
        )
        assertEquals(CapturePublicationResult.PARTIAL_SUCCESS, outputs.publicationResult)
        assertNull(outputs.jpegUri)
        assertEquals("content://media/external/images/media/102", outputs.dngUri)
        assertNull(outputs.thumbnailUri)
        assertEquals("JPEG encode failed", outputs.reason)
    }

    @Test
    fun test6_jpegPlusRaw_bothFail() {
        val outputs = PublicationPolicyResolver.resolveStrings(
            outputPolicy = OutputPolicy.JPEG_PLUS_RAW,
            jpegSucceeded = false,
            jpegUri = null,
            dngSucceeded = false,
            dngUri = null,
            jpegFailureReason = "JPEG error",
            dngFailureReason = "DNG error"
        )
        assertEquals(CapturePublicationResult.FAILURE, outputs.publicationResult)
        assertNull(outputs.jpegUri)
        assertNull(outputs.dngUri)
        assertNull(outputs.thumbnailUri)
        assertEquals("Both JPEG and DNG output failed", outputs.reason)
    }

    @Test
    fun test7_rawOnly_dngSucceeds() {
        val outputs = PublicationPolicyResolver.resolveStrings(
            outputPolicy = OutputPolicy.RAW_ONLY,
            jpegSucceeded = false,
            jpegUri = null,
            dngSucceeded = true,
            dngUri = "content://media/external/images/media/102"
        )
        assertEquals(CapturePublicationResult.FULL_SUCCESS, outputs.publicationResult)
        assertNull(outputs.jpegUri)
        assertEquals("content://media/external/images/media/102", outputs.dngUri)
        assertNull(outputs.thumbnailUri)
    }

    @Test
    fun test8_rawOnly_dngFails() {
        val outputs = PublicationPolicyResolver.resolveStrings(
            outputPolicy = OutputPolicy.RAW_ONLY,
            jpegSucceeded = false,
            jpegUri = null,
            dngSucceeded = false,
            dngUri = null,
            dngFailureReason = "Disk full"
        )
        assertEquals(CapturePublicationResult.FAILURE, outputs.publicationResult)
        assertNull(outputs.jpegUri)
        assertNull(outputs.dngUri)
        assertNull(outputs.thumbnailUri)
        assertEquals("Disk full", outputs.reason)
    }

    @Test
    fun test9_dngUriIsNeverSelectedAsThumbnailUri() {
        OutputPolicy.entries.forEach { policy ->
            val dngUri = "content://media/external/images/media/dng_999"
            val outputs = PublicationPolicyResolver.resolveStrings(
                outputPolicy = policy,
                jpegSucceeded = false,
                jpegUri = null,
                dngSucceeded = true,
                dngUri = dngUri
            )
            assertNotEquals(dngUri, outputs.thumbnailUri)
            assertNull(outputs.thumbnailUri)
        }
    }

    @Test
    fun test10_pendingOrReservedJpegUriNeverSelectedWhenJpegNotSucceeded() {
        val reservedPendingUri = "content://media/external/images/media/pending_123"
        val outputs = PublicationPolicyResolver.resolveStrings(
            outputPolicy = OutputPolicy.JPEG_PLUS_RAW,
            jpegSucceeded = false,
            jpegUri = reservedPendingUri,
            dngSucceeded = true,
            dngUri = "content://media/external/images/media/dng_456"
        )
        assertNull(outputs.jpegUri)
        assertNull(outputs.thumbnailUri)
    }

    @Test
    fun test11_partialSuccessIsNotRepresentedAsFullSuccess() {
        val outputsCase4 = PublicationPolicyResolver.resolveStrings(
            outputPolicy = OutputPolicy.JPEG_PLUS_RAW,
            jpegSucceeded = true,
            jpegUri = "content://media/external/images/media/jpeg_1",
            dngSucceeded = false,
            dngUri = null
        )
        assertEquals(CapturePublicationResult.PARTIAL_SUCCESS, outputsCase4.publicationResult)
        assertNotEquals(CapturePublicationResult.FULL_SUCCESS, outputsCase4.publicationResult)

        val outputsCase5 = PublicationPolicyResolver.resolveStrings(
            outputPolicy = OutputPolicy.JPEG_PLUS_RAW,
            jpegSucceeded = false,
            jpegUri = null,
            dngSucceeded = true,
            dngUri = "content://media/external/images/media/dng_1"
        )
        assertEquals(CapturePublicationResult.PARTIAL_SUCCESS, outputsCase5.publicationResult)
        assertNotEquals(CapturePublicationResult.FULL_SUCCESS, outputsCase5.publicationResult)
    }

    @Test
    fun test12_terminalFailureCannotLaterBecomeFullSuccess() {
        var clockNs = 1000L
        val tracker = CaptureWorkStateTracker(clockNs = { clockNs++ })
        val snapshot = tracker.begin("TEST_ROUTE", 500L)

        // Fail work
        val failedSnapshot = tracker.transition(
            workId = snapshot.workId,
            state = CaptureWorkState.FAILED,
            failureReason = "initial_failure"
        )
        assertEquals(CaptureWorkState.FAILED, failedSnapshot?.state)

        // Attempting to publish after terminal failure must be rejected by state tracker
        val retransitionAttempt = tracker.transition(
            workId = snapshot.workId,
            state = CaptureWorkState.PUBLISHED,
            publishedOutputs = StringPublicationOutputs(
                jpegUri = "content://media/external/images/media/late_jpeg",
                dngUri = null,
                thumbnailUri = "content://media/external/images/media/late_jpeg",
                publicationResult = CapturePublicationResult.FULL_SUCCESS
            )
        )

        // Must remain FAILED and not transition to PUBLISHED
        assertEquals(CaptureWorkState.FAILED, retransitionAttempt?.state)
        assertEquals("initial_failure", retransitionAttempt?.failureReason)
        assertNull(retransitionAttempt?.publishedUri)
    }
}
