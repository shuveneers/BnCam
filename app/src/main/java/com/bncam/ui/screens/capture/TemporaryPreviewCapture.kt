package com.bncam.ui.screens.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.Size
import com.bncam.core.debug.Phase0PerformanceTrace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

object TemporaryPreviewCapture {
    private const val TAG = "TempPreviewCapture"
    const val PREVIEW_TIMEOUT_MS = 150L
    const val EXISTING_PREVIEW_QUALITY = 88
    const val PREVIEW_EDGE_PX = 256

    suspend fun awaitTemporaryPreviewBitmap(
        previewView: FocusPeakingView?,
        timeoutMs: Long = PREVIEW_TIMEOUT_MS
    ): Bitmap? {
        if (previewView == null) return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                try {
                    previewView.captureSnapshot(PREVIEW_EDGE_PX, PREVIEW_EDGE_PX) { bitmap ->
                        if (continuation.isActive) {
                            continuation.resume(bitmap)
                        } else {
                            bitmap?.recycle()
                        }
                    }
                } catch (e: Throwable) {
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }
            }
        }
    }

    suspend fun writeTemporaryPreview(
        context: Context,
        bitmap: Bitmap,
        phase0TraceToken: Long? = null
    ): String? = withContext(Dispatchers.IO) {
        val file = try {
            File.createTempFile("thumb_", ".jpg", context.cacheDir)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to create temp file for preview", e)
            return@withContext null
        }

        phase0TraceToken?.let {
            Phase0PerformanceTrace.previewPersistStarted(it, file.absolutePath)
        }
        try {
            val compressed = file.outputStream().buffered().use { output ->
                bitmap.compress(
                    Bitmap.CompressFormat.JPEG,
                    EXISTING_PREVIEW_QUALITY,
                    output
                )
            }

            if (!compressed || !file.exists() || file.length() <= 0L) {
                file.delete()
                null
            } else {
                phase0TraceToken?.let { Phase0PerformanceTrace.previewPersistDone(it) }
                file.absolutePath
            }
        } catch (error: Throwable) {
            file.delete()
            null
        }
    }

    suspend fun captureTemporaryPreview(
        context: Context,
        previewView: FocusPeakingView?,
        timeoutMs: Long = PREVIEW_TIMEOUT_MS
    ): String? {
        val phase0TraceToken = Phase0PerformanceTrace.beginPreviewSnapshot()
        val bitmap = awaitTemporaryPreviewBitmap(previewView, timeoutMs) ?: return null
        Phase0PerformanceTrace.previewFrameFrozen(phase0TraceToken)
        return try {
            writeTemporaryPreview(context, bitmap, phase0TraceToken)
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    /** Creates a bitmap-compatible thumbnail from a completed DNG without changing the DNG. */
    suspend fun createPublishedDngThumbnail(context: Context, dngUri: Uri): String? =
        withContext(Dispatchers.IO) {
            val bitmap = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.loadThumbnail(
                        dngUri,
                        Size(PREVIEW_EDGE_PX, PREVIEW_EDGE_PX),
                        null
                    )
                } else {
                    context.contentResolver.openInputStream(dngUri)?.use(BitmapFactory::decodeStream)
                }
            }.getOrNull() ?: return@withContext null

            val cacheFile = runCatching {
                File.createTempFile("dng_thumb_", ".jpg", context.cacheDir)
            }.getOrNull()
            if (cacheFile == null) {
                bitmap.recycle()
                return@withContext null
            }
            try {
                val written = cacheFile.outputStream().buffered().use { output ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, EXISTING_PREVIEW_QUALITY, output)
                }
                cacheFile.absolutePath.takeIf { written && cacheFile.length() > 0L }
                    ?: run {
                        cacheFile.delete()
                        null
                    }
            } finally {
                bitmap.recycle()
            }
        }
}
