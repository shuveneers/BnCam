package com.bncam.core.debug

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Public Documents publisher for persistent per-shot diagnostics.
 *
 * The app targets modern scoped storage. Persistent debug files are therefore published through
 * MediaStore under Documents/BnCamDebug/<shot>/ instead of writing raw filesystem paths outside the
 * app sandbox. Transient crash-recovery state remains private in [ShotLogger].
 */
internal object PublicShotDiagnosticsStorage {
    val ROOT_RELATIVE_PATH = "${Environment.DIRECTORY_DOCUMENTS}/BnCamDebug"
    private const val TAG = "ShotDebugStorage"
    private const val MAX_PENDING_WRITES = 64

    private val droppedWrites = AtomicLong(0L)
    private val uriCache = ConcurrentHashMap<String, Uri>()
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(MAX_PENDING_WRITES),
        ThreadFactory { runnable -> Thread(runnable, "BnCamShotDebugIO").apply { isDaemon = true } },
        { _, _ -> droppedWrites.incrementAndGet() }
    )

    @Volatile
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun displayRoot(): String = ROOT_RELATIVE_PATH

    fun publish(folderName: String, fileName: String, content: String) {
        val context = appContext ?: return
        val safeFolder = sanitizePathSegment(folderName)
        val safeFile = sanitizeFileName(fileName)
        val body = content.trimEnd() + "\n"
        executor.execute {
            runCatching {
                publishNow(context, safeFolder, safeFile, body)
                val dropped = droppedWrites.getAndSet(0L)
                if (dropped > 0L) {
                    Log.w(TAG, "Dropped $dropped pending shot-debug writes because the bounded queue was full")
                }
            }.onFailure { failure ->
                Log.e(TAG, "Unable to publish $safeFolder/$safeFile", failure)
            }
        }
    }

    private fun publishNow(context: Context, folderName: String, fileName: String, content: String) {
        val resolver = context.contentResolver
        val relativePath = "$ROOT_RELATIVE_PATH/$folderName/"
        val cacheKey = "$relativePath$fileName"
        var uri = uriCache[cacheKey]
        if (uri == null) {
            uri = findExisting(context, relativePath, fileName)
            if (uri == null) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                uri = resolver.insert(
                    MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    values
                ) ?: error("MediaStore insert returned null for $relativePath$fileName")
                resolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
                    writer.write(content)
                } ?: error("Unable to open MediaStore output stream for $uri")
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null
                )
                uriCache[cacheKey] = uri
                return
            }
            uriCache[cacheKey] = uri
        }

        resolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
            writer.write(content)
        } ?: error("Unable to overwrite MediaStore output stream for $uri")
    }

    private fun findExisting(context: Context, relativePath: String, fileName: String): Uri? {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?"
        val args = arrayOf(relativePath, fileName)
        return context.contentResolver.query(collection, projection, selection, args, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
            ContentUris.withAppendedId(collection, id)
        }
    }

    private fun sanitizePathSegment(value: String): String = value
        .trim()
        .ifBlank { "unknown_shot" }
        .replace(Regex("[\\/:*?\"<>|]"), "_")
        .replace(Regex("\\s+"), "_")
        .take(120)

    private fun sanitizeFileName(value: String): String {
        val base = value.trim().ifBlank { "DEBUG.txt" }
            .replace(Regex("[\\/:*?\"<>|]"), "_")
        return if (base.endsWith(".txt", ignoreCase = true)) base else "$base.txt"
    }
}
