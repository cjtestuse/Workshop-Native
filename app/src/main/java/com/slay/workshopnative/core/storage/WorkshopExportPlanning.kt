package com.slay.workshopnative.core.storage

import com.slay.workshopnative.core.util.sanitizeFileName
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class PlannedWorkshopExportFile(
    val source: File,
    val relativePath: String,
)

data class WorkshopExportPlan(
    val files: List<PlannedWorkshopExportFile>,
    val renamedCount: Int,
)

private val EXPORT_TIMESTAMP_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US)
        .withZone(ZoneId.systemDefault())

fun buildWorkshopExportPlan(localRootDir: File): WorkshopExportPlan {
    check(localRootDir.exists() && localRootDir.isDirectory) { "导出计划需要有效的目录输入" }
    val sourceFiles = localRootDir.walkTopDown()
        .filter { it.isFile }
        .sortedBy { it.relativeTo(localRootDir).invariantSeparatorsPath }
        .toList()

    val exportedFilePaths = mutableSetOf<String>()
    val exportedDirectoryPaths = mutableSetOf<String>()
    var renamedCount = 0

    val plannedFiles = sourceFiles.mapIndexed { index, source ->
        val sourceSegments = source.relativeTo(localRootDir)
            .invariantSeparatorsPath
            .split('/')
            .filter(String::isNotBlank)
        val originalSegments = sourceSegments.mapIndexed { segmentIndex, segment ->
            sanitizeFileName(
                raw = segment,
                fallback = if (segmentIndex == sourceSegments.lastIndex) "file" else "folder",
            )
        }
        val resolvedPath = resolveUniqueRelativePath(
            originalSegments = originalSegments,
            exportedFilePaths = exportedFilePaths,
            exportedDirectoryPaths = exportedDirectoryPaths,
            fallbackIndex = index,
        )
        if (resolvedPath != originalSegments.joinToString("/")) {
            renamedCount++
        }
        val resolvedSegments = resolvedPath.split('/')
        exportedFilePaths += resolvedPath
        resolvedSegments.dropLast(1).runningReduceOrNull { acc, segment -> "$acc/$segment" }
            ?.let(exportedDirectoryPaths::addAll)
        PlannedWorkshopExportFile(
            source = source,
            relativePath = resolvedPath,
        )
    }

    return WorkshopExportPlan(
        files = plannedFiles,
        renamedCount = renamedCount,
    )
}

fun appendTimestampSuffix(
    name: String,
    timestampMs: Long = System.currentTimeMillis(),
): String {
    val dot = name.lastIndexOf('.')
    val base = if (dot > 0) name.substring(0, dot) else name
    val extension = if (dot > 0) name.substring(dot) else ""
    val timestamp = EXPORT_TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(timestampMs))
    return sanitizeFileName("${base}_$timestamp$extension", "workshop-item")
}

fun appendOrdinalSuffix(
    name: String,
    ordinal: Int,
): String {
    val dot = name.lastIndexOf('.')
    val base = if (dot > 0) name.substring(0, dot) else name
    val extension = if (dot > 0) name.substring(dot) else ""
    return sanitizeFileName("${base}_$ordinal$extension", "file")
}

private fun resolveUniqueRelativePath(
    originalSegments: List<String>,
    exportedFilePaths: Set<String>,
    exportedDirectoryPaths: Set<String>,
    fallbackIndex: Int,
): String {
    val segments = originalSegments.toMutableList()
    val baseSegments = originalSegments.toList()
    val renameCounters = IntArray(segments.size)

    while (true) {
        val prefixConflict = (1 until segments.size).firstOrNull { prefixLength ->
            exportedFilePaths.contains(segments.take(prefixLength).joinToString("/"))
        }
        if (prefixConflict != null) {
            val conflictIndex = prefixConflict - 1
            renameCounters[conflictIndex]++
            segments[conflictIndex] = appendSegmentSuffix(
                baseName = baseSegments[conflictIndex],
                ordinal = renameCounters[conflictIndex].coerceAtLeast(fallbackIndex + 1),
                isLeaf = conflictIndex == segments.lastIndex,
            )
            continue
        }

        val candidate = segments.joinToString("/")
        if (candidate in exportedFilePaths || candidate in exportedDirectoryPaths) {
            val leafIndex = segments.lastIndex
            renameCounters[leafIndex]++
            segments[leafIndex] = appendSegmentSuffix(
                baseName = baseSegments[leafIndex],
                ordinal = renameCounters[leafIndex].coerceAtLeast(fallbackIndex + 1),
                isLeaf = true,
            )
            continue
        }
        return candidate
    }
}

private fun appendSegmentSuffix(
    baseName: String,
    ordinal: Int,
    isLeaf: Boolean,
): String {
    return if (isLeaf) {
        appendOrdinalSuffix(baseName, ordinal)
    } else {
        sanitizeFileName("${baseName}_$ordinal", "folder")
    }
}

private fun <T> List<T>.runningReduceOrNull(operation: (acc: T, element: T) -> T): List<T>? {
    if (isEmpty()) return null
    val destination = ArrayList<T>(size)
    var accumulator = first()
    destination += accumulator
    for (index in 1 until size) {
        accumulator = operation(accumulator, this[index])
        destination += accumulator
    }
    return destination
}
