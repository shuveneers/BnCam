package com.bncam.core.utils

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.File

object ThumbnailScanner {

    private const val TAG = "ThumbnailScanner"

    fun findLatestPublishedImageUri(context: Context): Uri? {
        // 1. Prefer MediaStore as source of truth for MediaStore-indexed BnCam exports
        try {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.DATA
            )
            val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Images.Media.IS_PENDING} = 0"
            } else {
                "${MediaStore.Images.Media.DATA} LIKE ?"
            }
            val selectionArgs = arrayOf("%DCIM/BnCam%")
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val id = cursor.getLong(idColumn)
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    Log.i(TAG, "MediaStore found latest published image uri=$uri")
                    return uri
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore query failed: ${e.message}")
        }

        // 2. Fall back to direct folder scanning under /sdcard/DCIM/BnCam/
        try {
            val dcimFolder = File("/sdcard/DCIM/BnCam")
            if (dcimFolder.exists() && dcimFolder.isDirectory) {
                val latestFile = dcimFolder.listFiles()
                    ?.filter { it.isFile && (it.extension.equals("jpg", true) || it.extension.equals("jpeg", true)) }
                    ?.maxByOrNull { it.lastModified() }
                if (latestFile != null) {
                    val uri = Uri.fromFile(latestFile)
                    Log.i(TAG, "Direct folder scan found latest published image uri=$uri")
                    return uri
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Direct folder scan failed: ${e.message}")
        }

        return null
    }
}
