package com.slay.workshopnative.core.storage

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.MediaStore
import com.slay.workshopnative.core.util.sanitizeFileName
import java.io.FilterOutputStream
import java.io.OutputStream

const val DEFAULT_DOWNLOAD_FOLDER_NAME = "steamapps"
const val MEDIASTORE_DOWNLOADS_URI_STRING = "content://media/external/downloads"
private val MEDIASTORE_DOWNLOADS_URI: Uri = Uri.parse(MEDIASTORE_DOWNLOADS_URI_STRING)

fun normalizeDownloadFolderName(raw: String?): String {
    return sanitizeFileName(
        raw = raw.orEmpty().ifBlank { DEFAULT_DOWNLOAD_FOLDER_NAME },
        fallback = DEFAULT_DOWNLOAD_FOLDER_NAME,
    )
}

fun buildDownloadDestinationLabel(
    treeLabel: String?,
    folderName: String?,
): String {
    val safeFolderName = normalizeDownloadFolderName(folderName)
    return if (treeLabel.isNullOrBlank()) {
        "系统下载/$safeFolderName"
    } else {
        "$treeLabel/$safeFolderName"
    }
}

fun uniqueMediaStoreRootName(
    context: Context,
    folderName: String?,
    baseRootName: String,
): String {
    val safeBaseRootName = sanitizeFileName(baseRootName, "workshop-item")
    if (!mediaStoreDirectoryExists(context, folderName, safeBaseRootName)) {
        return safeBaseRootName
    }

    var index = 1
    while (mediaStoreDirectoryExists(context, folderName, "$safeBaseRootName ($index)")) {
        index++
    }
    return "$safeBaseRootName ($index)"
}

fun createMediaStoreFileOutput(
    context: Context,
    folderName: String?,
    rootName: String?,
    relativePath: String,
    replaceExisting: Boolean,
): Pair<OutputStream, Uri> {
    val uri = createMediaStoreFileUri(
        context = context,
        folderName = folderName,
        rootName = rootName,
        relativePath = relativePath,
        replaceExisting = replaceExisting,
    )
    val resolver = context.contentResolver
    val stream = runCatching {
        resolver.openOutputStream(uri)
    }.getOrElse { throwable ->
        resolver.delete(uri, null, null)
        throw throwable
    } ?: run {
        resolver.delete(uri, null, null)
        error("无法打开目标文件")
    }

    return MediaStoreOutputStream(stream) {
        finalizeMediaStoreFile(context, uri)
    } to uri
}

fun createMediaStoreFileUri(
    context: Context,
    folderName: String?,
    rootName: String?,
    relativePath: String,
    replaceExisting: Boolean,
): Uri {
    requireMediaStoreDownloadsSupport()
    val segments = normalizeRelativeSegments(relativePath)
    val fileName = segments.lastOrNull() ?: error("无效的文件路径")
    val relativeDirectory = buildRelativeDirectory(
        folderName = folderName,
        rootName = rootName,
        subdirectories = segments.dropLast(1),
    )

    if (replaceExisting) {
        queryMatchingUris(context, relativeDirectory, fileName).forEach { uri ->
            context.contentResolver.delete(uri, null, null)
        }
    }

    val targetName = if (replaceExisting) {
        fileName
    } else {
        uniqueDisplayName(context, relativeDirectory, fileName)
    }

    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, targetName)
        put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
        put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDirectory)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }

    return context.contentResolver.insert(MEDIASTORE_DOWNLOADS_URI, values)
        ?: error("无法创建目标文件")
}

fun finalizeMediaStoreFile(
    context: Context,
    uri: Uri,
) {
    context.contentResolver.update(
        uri,
        ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
        null,
        null,
    )
}

fun findMediaStoreDownloadUri(
    context: Context,
    folderName: String?,
    rootName: String?,
    relativePath: String,
): Uri? {
    requireMediaStoreDownloadsSupport()
    val segments = normalizeRelativeSegments(relativePath)
    val fileName = segments.lastOrNull() ?: return null
    val relativeDirectory = buildRelativeDirectory(
        folderName = folderName,
        rootName = rootName,
        subdirectories = segments.dropLast(1),
    )
    return queryMatchingUris(context, relativeDirectory, fileName).firstOrNull()
}

fun queryUriSize(
    context: Context,
    uri: Uri,
): Long {
    return context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.SIZE, MediaStore.MediaColumns.SIZE),
        null,
        null,
        null,
    )?.use { cursor ->
        if (!cursor.moveToFirst()) return@use 0L
        val sizeIndex = cursor.columnNames.indexOfFirst { name ->
            name == OpenableColumns.SIZE || name == MediaStore.MediaColumns.SIZE
        }
        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else 0L
    } ?: 0L
}

fun openExistingMediaStoreFileOutput(
    context: Context,
    uri: Uri,
    append: Boolean,
): OutputStream {
    val mode = if (append) "wa" else "w"
    return context.contentResolver.openOutputStream(uri, mode)
        ?: error("无法打开目标文件")
}

private fun mediaStoreDirectoryExists(
    context: Context,
    folderName: String?,
    rootName: String,
): Boolean {
    requireMediaStoreDownloadsSupport()
    val relativeDirectory = buildRelativeDirectory(
        folderName = folderName,
        rootName = rootName,
        subdirectories = emptyList(),
    )
    val selection = buildString {
        append(MediaStore.MediaColumns.RELATIVE_PATH)
        append(" = ? OR ")
        append(MediaStore.MediaColumns.RELATIVE_PATH)
        append(" LIKE ?")
    }
    val args = arrayOf(relativeDirectory, "$relativeDirectory%")

    context.contentResolver.query(
        MEDIASTORE_DOWNLOADS_URI,
        arrayOf(MediaStore.MediaColumns._ID),
        selection,
        args,
        null,
    )?.use { cursor ->
        return cursor.moveToFirst()
    }
    return false
}

private fun uniqueDisplayName(
    context: Context,
    relativeDirectory: String,
    fileName: String,
): String {
    if (queryMatchingUris(context, relativeDirectory, fileName).isEmpty()) return fileName

    val dot = fileName.lastIndexOf('.')
    val baseName = if (dot >= 0) fileName.substring(0, dot) else fileName
    val extension = if (dot >= 0) fileName.substring(dot) else ""
    var index = 1
    while (queryMatchingUris(context, relativeDirectory, "$baseName ($index)$extension").isNotEmpty()) {
        index++
    }
    return "$baseName ($index)$extension"
}

private fun queryMatchingUris(
    context: Context,
    relativeDirectory: String,
    fileName: String,
): List<Uri> {
    requireMediaStoreDownloadsSupport()
    val collection = MEDIASTORE_DOWNLOADS_URI
    val projection = arrayOf(MediaStore.MediaColumns._ID)
    val selection = buildString {
        append(MediaStore.MediaColumns.RELATIVE_PATH)
        append(" = ? AND ")
        append(MediaStore.MediaColumns.DISPLAY_NAME)
        append(" = ?")
    }
    val args = arrayOf(relativeDirectory, fileName)

    context.contentResolver.query(collection, projection, selection, args, null)?.use { cursor ->
        val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        val results = mutableListOf<Uri>()
        while (cursor.moveToNext()) {
            results += ContentUris.withAppendedId(collection, cursor.getLong(idIndex))
        }
        return results
    }
    return emptyList()
}

private fun requireMediaStoreDownloadsSupport() {
    check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        "公共下载目录需要 Android 10 及以上"
    }
}

private fun buildRelativeDirectory(
    folderName: String?,
    rootName: String?,
    subdirectories: List<String>,
): String {
    val parts = buildList {
        add(Environment.DIRECTORY_DOWNLOADS)
        add(normalizeDownloadFolderName(folderName))
        rootName?.let { add(sanitizeFileName(it, "workshop-item")) }
        addAll(subdirectories.map { sanitizeFileName(it, "folder") })
    }
    return parts.joinToString("/") + "/"
}

private fun normalizeRelativeSegments(relativePath: String): List<String> {
    val cleanedSegments = relativePath
        .replace('\\', '/')
        .split('/')
        .filter { it.isNotBlank() && it != "." && it != ".." }
    return cleanedSegments.mapIndexed { index, segment ->
            sanitizeFileName(
                raw = segment,
                fallback = if (index == cleanedSegments.lastIndex) "file" else "folder",
            )
        }
}

private class MediaStoreOutputStream(
    output: OutputStream,
    private val onClose: () -> Unit,
) : FilterOutputStream(output) {
    override fun close() {
        try {
            super.close()
        } finally {
            onClose()
        }
    }
}
