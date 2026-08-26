package com.bncam.ui.screens.capture

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

object TemporaryPreviewCapture {
    private const val TAG = "TempPreviewCapture"
    const val PREVIEW_TIMEOUT_MS = 150L
    const val EXISTING_PREVIEW_QUALITY = 75

    suspend fun awaitTemporaryPreviewBitmap(
        previewView: FocusPeakingView?,
        timeoutMs: Long = PREVIEW_TIMEOUT_MS
    ): Bitmap? {
        if (previewView == null) return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                try {
                    previewView.captureSnapshot(128, 128) { bitmap ->
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
        bitmap: Bitmap
    ): String? = withContext(Dispatchers.IO) {
        val file = try {
            File.createTempFile("thumb_", ".jpg", context.cacheDir)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to create temp file for preview", e)
            return@withContext null
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
        val bitmap = awaitTemporaryPreviewBitmap(previewView, timeoutMs) ?: return null
        return try {
            writeTemporaryPreview(context, bitmap)
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }
}
