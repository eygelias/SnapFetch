package com.snapfetch.app.utils

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream

object StorageHelper {

    private const val FOLDER_VIDEOS = "SnapFetch"
    private const val FOLDER_AUDIOS = "SnapFetch"

    /**
     * Prepares an entry in MediaStore for streaming download.
     * Uses Scoped Storage without requiring legacy permissions on Android 10+.
     */
    fun createMediaStoreEntry(
        context: Context,
        displayName: String,
        mimeType: String,
        isAudio: Boolean = false
    ): Uri? {
        val resolver: ContentResolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val relativePath = if (isAudio) {
                    "${Environment.DIRECTORY_MUSIC}/$FOLDER_AUDIOS"
                } else {
                    "${Environment.DIRECTORY_MOVIES}/$FOLDER_VIDEOS"
                }
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val collectionUri: Uri = if (isAudio) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
        }

        return resolver.insert(collectionUri, contentValues)
    }

    /**
     * Marks the download as completed in MediaStore (IS_PENDING = 0).
     */
    fun finalizeMediaStoreEntry(context: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            context.contentResolver.update(uri, contentValues, null, null)
        }
    }

    /**
     * Opens an output stream to write the downloaded bytes.
     */
    fun openOutputStream(context: Context, uri: Uri): OutputStream? {
        return context.contentResolver.openOutputStream(uri)
    }

    /**
     * Deletes the pending entry if download was cancelled or failed.
     */
    fun deletePendingEntry(context: Context, uri: Uri?) {
        if (uri != null) {
            try {
                context.contentResolver.delete(uri, null, null)
            } catch (_: Exception) {
            }
        }
    }
}
