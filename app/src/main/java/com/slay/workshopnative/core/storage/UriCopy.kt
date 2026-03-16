package com.slay.workshopnative.core.storage

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

private const val DEFAULT_URI_COPY_BUFFER_SIZE = 1024 * 1024
private const val TRANSFER_CHUNK_SIZE = 8L * 1024L * 1024L

fun copyLocalFileToUri(
    context: Context,
    source: File,
    targetUri: Uri,
    bufferSize: Int = DEFAULT_URI_COPY_BUFFER_SIZE,
    onProgress: ((copiedBytes: Long, totalBytes: Long) -> Unit)? = null,
) {
    val totalBytes = source.length().coerceAtLeast(0L)
    val usedChannelCopy = runCatching {
        context.contentResolver.openFileDescriptor(targetUri, "rw")?.use { descriptor ->
            FileInputStream(source).channel.use { inputChannel ->
                FileOutputStream(descriptor.fileDescriptor).channel.use { outputChannel ->
                    outputChannel.truncate(0L)
                    var copiedBytes = 0L
                    while (copiedBytes < totalBytes) {
                        val transferred = inputChannel.transferTo(
                            copiedBytes,
                            minOf(TRANSFER_CHUNK_SIZE, totalBytes - copiedBytes),
                            outputChannel,
                        )
                        if (transferred <= 0L) break
                        copiedBytes += transferred
                        onProgress?.invoke(copiedBytes, totalBytes)
                    }
                    outputChannel.force(true)
                    copiedBytes >= totalBytes
                }
            }
        } ?: false
    }.getOrDefault(false)

    if (usedChannelCopy) return

    context.contentResolver.openOutputStream(targetUri, "w")
        ?.let { rawOutput ->
            BufferedInputStream(FileInputStream(source), bufferSize).use { input ->
                BufferedOutputStream(rawOutput, bufferSize).use { output ->
                    val buffer = ByteArray(bufferSize)
                    var copiedBytes = 0L
                    var read = input.read(buffer)
                    while (read >= 0) {
                        if (read > 0) {
                            output.write(buffer, 0, read)
                            copiedBytes += read
                            onProgress?.invoke(copiedBytes, totalBytes)
                        }
                        read = input.read(buffer)
                    }
                    output.flush()
                }
            }
            return
        }

    error("无法打开目标文件")
}
