package com.slay.workshopnative.core.storage

import android.content.Context
import com.slay.workshopnative.core.util.sanitizeFileName
import java.io.File

private const val STAGING_ROOT_DIR_NAME = "download-staging"

fun directDownloadStagingFile(
    context: Context,
    taskId: String,
    fileName: String,
): File {
    val directory = File(context.filesDir, "$STAGING_ROOT_DIR_NAME/direct/$taskId").apply {
        mkdirs()
    }
    return File(directory, sanitizeFileName(fileName, "download.bin"))
}

fun workshopDownloadStagingRoot(
    context: Context,
    taskId: String,
    rootName: String,
): File {
    return File(
        context.filesDir,
        "$STAGING_ROOT_DIR_NAME/workshop/$taskId/${sanitizeFileName(rootName, "workshop-item")}",
    ).apply {
        mkdirs()
    }
}

fun clearDownloadStaging(
    context: Context,
    taskId: String,
) {
    File(context.filesDir, "$STAGING_ROOT_DIR_NAME/direct/$taskId").deleteRecursively()
    File(context.filesDir, "$STAGING_ROOT_DIR_NAME/workshop/$taskId").deleteRecursively()
}
