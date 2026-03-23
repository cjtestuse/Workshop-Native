package com.slay.workshopnative.data.postprocess

import android.util.Log
import com.slay.workshopnative.core.util.sanitizeFileName
import com.slay.workshopnative.data.preferences.UserPreferences
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class WorkshopDownloadPostProcessorSelection(
    val id: String,
    val displayName: String,
    val config: WorkshopDownloadPostProcessorConfig,
    val processor: WorkshopDownloadPostProcessor,
)

data class WorkshopDownloadPostProcessorConfig(
    val keepOriginal: Boolean,
    val wallpaperEngineTexConversionEnabled: Boolean = false,
    val wallpaperEngineKeepConvertedTexOriginal: Boolean = true,
)

data class WorkshopDownloadPostProcessorResult(
    val artifact: File,
    val summary: String? = null,
)

interface WorkshopDownloadPostProcessor {
    val id: String
    val displayName: String

    suspend fun process(
        inputRoot: File,
        outputBaseName: String,
        config: WorkshopDownloadPostProcessorConfig,
        onPhaseChanged: suspend (String?) -> Unit,
    ): WorkshopDownloadPostProcessorResult
}

@Singleton
class WorkshopDownloadPostProcessorRegistry @Inject constructor() {
    private val terrariaArchivePostProcessor = TerrariaArchivePostProcessor()
    private val wallpaperEnginePkgExtractPostProcessor = WallpaperEnginePkgExtractPostProcessor()

    fun resolveSelection(
        appId: Int,
        prefs: UserPreferences,
    ): WorkshopDownloadPostProcessorSelection? {
        return when {
            prefs.terrariaArchivePostProcessorEnabled && appId == TERRARIA_APP_ID -> {
                WorkshopDownloadPostProcessorSelection(
                    id = terrariaArchivePostProcessor.id,
                    displayName = terrariaArchivePostProcessor.displayName,
                    config = WorkshopDownloadPostProcessorConfig(
                        keepOriginal = prefs.terrariaArchiveKeepOriginal,
                    ),
                    processor = terrariaArchivePostProcessor,
                )
            }
            prefs.wallpaperEnginePkgExtractEnabled && appId == WALLPAPER_ENGINE_APP_ID -> {
                WorkshopDownloadPostProcessorSelection(
                    id = wallpaperEnginePkgExtractPostProcessor.id,
                    displayName = wallpaperEnginePkgExtractPostProcessor.displayName,
                    config = WorkshopDownloadPostProcessorConfig(
                        keepOriginal = prefs.wallpaperEnginePkgKeepOriginal,
                        wallpaperEngineTexConversionEnabled = prefs.wallpaperEngineTexConversionEnabled,
                        wallpaperEngineKeepConvertedTexOriginal =
                            prefs.wallpaperEngineKeepConvertedTexOriginal,
                    ),
                    processor = wallpaperEnginePkgExtractPostProcessor,
                )
            }
            else -> null
        }
    }

    companion object {
        const val TERRARIA_APP_ID = 105600
        const val WALLPAPER_ENGINE_APP_ID = 431960
    }
}

private class TerrariaArchivePostProcessor : WorkshopDownloadPostProcessor {
    override val id: String = "terraria_archive"
    override val displayName: String = "Terraria 压缩导出"

    override suspend fun process(
        inputRoot: File,
        outputBaseName: String,
        config: WorkshopDownloadPostProcessorConfig,
        onPhaseChanged: suspend (String?) -> Unit,
    ): WorkshopDownloadPostProcessorResult = withContext(Dispatchers.IO) {
        check(inputRoot.exists() && inputRoot.isDirectory) { "Terraria 压缩导出需要目录结果" }
        val parentDir = inputRoot.parentFile ?: error("无法访问下载暂存目录")
        val safeOutputBaseName = sanitizeFileName("${outputBaseName}_Post", "${outputBaseName}_Post")
        val archiveFileName = "$safeOutputBaseName.zip"
        onPhaseChanged("正在执行 Terraria 压缩导出…")

        if (config.keepOriginal) {
            val outputDir = File(parentDir, safeOutputBaseName)
            recreatePath(outputDir)
            inputRoot.copyRecursively(
                target = File(outputDir, inputRoot.name),
                overwrite = true,
            )
            val archiveFile = File(outputDir, archiveFileName)
            createArchive(inputRoot, archiveFile)
            WorkshopDownloadPostProcessorResult(
                artifact = outputDir,
                summary = "已生成 Terraria 压缩结果",
            )
        } else {
            val archiveFile = File(parentDir, archiveFileName)
            recreatePath(archiveFile)
            createArchive(inputRoot, archiveFile)
            WorkshopDownloadPostProcessorResult(
                artifact = archiveFile,
                summary = "已生成 Terraria 压缩结果",
            )
        }
    }

    private suspend fun createArchive(
        sourceRoot: File,
        archiveFile: File,
    ) {
        archiveFile.parentFile?.mkdirs()
        val baseParent = sourceRoot.parentFile ?: sourceRoot
        ZipOutputStream(
            BufferedOutputStream(FileOutputStream(archiveFile)),
        ).use { zipOutput ->
            zipOutput.setLevel(Deflater.DEFAULT_COMPRESSION)
            sourceRoot.walkTopDown().forEach { entry ->
                currentCoroutineContext().ensureActive()
                val relativePath = baseParent.toPath().relativize(entry.toPath()).toString()
                    .replace(File.separatorChar, '/')
                    .trim('/')
                if (relativePath.isBlank()) return@forEach

                if (entry.isDirectory) {
                    zipOutput.putNextEntry(ZipEntry("$relativePath/"))
                    zipOutput.closeEntry()
                } else {
                    zipOutput.putNextEntry(ZipEntry(relativePath))
                    BufferedInputStream(FileInputStream(entry)).use { input ->
                        copyStreamWithCancellation(input, zipOutput)
                    }
                    zipOutput.closeEntry()
                }
            }
        }
    }

    private fun recreatePath(target: File) {
        if (target.exists()) {
            target.deleteRecursively()
        }
        target.parentFile?.mkdirs()
    }
}

internal class WallpaperEnginePkgExtractPostProcessor : WorkshopDownloadPostProcessor {
    override val id: String = "wallpaper_engine_pkg_extract"
    override val displayName: String = "Wallpaper Engine PKG 提取"

    override suspend fun process(
        inputRoot: File,
        outputBaseName: String,
        config: WorkshopDownloadPostProcessorConfig,
        onPhaseChanged: suspend (String?) -> Unit,
    ): WorkshopDownloadPostProcessorResult = withContext(Dispatchers.IO) {
        check(inputRoot.exists() && inputRoot.isDirectory) { "Wallpaper Engine PKG 提取需要目录结果" }
        val coroutineContext = currentCoroutineContext()
        onPhaseChanged("正在扫描 Wallpaper Engine PKG…")
        val pkgFiles = inputRoot.walkTopDown()
            .filter { it.isFile && it.extension.equals("pkg", ignoreCase = true) }
            .toList()
        if (pkgFiles.isEmpty()) {
            onPhaseChanged(null)
            return@withContext WorkshopDownloadPostProcessorResult(
                artifact = inputRoot,
                summary = "未发现 PKG 文件，已保留原始结果",
            )
        }

        val parentDir = inputRoot.parentFile ?: error("无法访问下载暂存目录")
        val safeOutputBaseName = sanitizeFileName("${outputBaseName}_Post", "${outputBaseName}_Post")
        val outputDir = File(parentDir, safeOutputBaseName)
        recreatePath(outputDir)

        if (config.keepOriginal) {
            onPhaseChanged("正在复制原始结果…")
            inputRoot.copyRecursively(
                target = File(outputDir, "Original"),
                overwrite = true,
            )
        }

        val extractionRoot = if (config.keepOriginal) {
            File(outputDir, "Extracted")
        } else {
            outputDir
        }.also { it.mkdirs() }

        pkgFiles.forEachIndexed { index, pkgFile ->
            currentCoroutineContext().ensureActive()
            onPhaseChanged("正在提取 PKG（${index + 1}/${pkgFiles.size}）…")
            val relativeParent = pkgFile.parentFile
                ?.relativeTo(inputRoot)
                ?.invariantSeparatorsPath
                ?.takeIf { it.isNotBlank() }
            val pkgStem = sanitizeFileName(pkgFile.nameWithoutExtension, "pkg")
            val pkgOutputDir = sequenceOf(relativeParent, pkgStem)
                .filterNotNull()
                .filter { it.isNotBlank() }
                .fold(extractionRoot) { current, segment -> File(current, segment) }
            WallpaperEnginePkgExtractor.extract(
                pkgFile = pkgFile,
                outputDir = pkgOutputDir,
                ensureActive = { coroutineContext.ensureActive() },
            )
        }
        val texSummary = if (config.wallpaperEngineTexConversionEnabled) {
            convertExtractedTexFiles(
                extractionRoot = extractionRoot,
                keepOriginalTex = config.wallpaperEngineKeepConvertedTexOriginal,
                onPhaseChanged = onPhaseChanged,
            )
        } else {
            null
        }
        onPhaseChanged("正在整理 PKG 提取结果…")
        WorkshopDownloadPostProcessorResult(
            artifact = outputDir,
            summary = buildList {
                add("已提取 ${pkgFiles.size} 个 PKG")
                texSummary?.takeIf { it.isNotBlank() }?.let(::add)
            }.joinToString("；"),
        )
    }

    private suspend fun convertExtractedTexFiles(
        extractionRoot: File,
        keepOriginalTex: Boolean,
        onPhaseChanged: suspend (String?) -> Unit,
    ): String? {
        onPhaseChanged("正在扫描 TEX 资源…")
        val texFiles = extractionRoot.walkTopDown()
            .filter { it.isFile && it.extension.equals("tex", ignoreCase = true) }
            .toList()
        if (texFiles.isEmpty()) return "未发现 TEX 资源"
        val coroutineContext = currentCoroutineContext()

        var convertedCount = 0
        var skippedCount = 0
        var failedCount = 0

        texFiles.forEachIndexed { index, texFile ->
            currentCoroutineContext().ensureActive()
            onPhaseChanged("正在转换 TEX（${index + 1}/${texFiles.size}）：${texFile.name}…")
            val header = runCatching {
                WallpaperEngineTexFastPathInspector.inspectHeader(texFile)
            }.getOrNull()
            val fastPath = runCatching {
                WallpaperEngineTexFastPathInspector.inspect(texFile)
            }.onFailure { error ->
                safeLogWarn("inspect raw TEX fast path failed file=${texFile.name}", error)
            }.getOrNull()
            if (fastPath != null) {
                val outputFile = createUniqueConvertedSibling(texFile, fastPath.extension)
                runCatching {
                    WallpaperEngineTexFastPathInspector.copyPayload(
                        sourceFile = texFile,
                        targetFile = outputFile,
                        fastPath = fastPath,
                        ensureActive = { coroutineContext.ensureActive() },
                    )
                }.onFailure { error ->
                    safeLogWarn("copy raw TEX fast path failed file=${texFile.name}", error)
                }.getOrNull() ?: run {
                    failedCount++
                    return@forEachIndexed
                }
                convertedCount++
                if (!keepOriginalTex) {
                    texFile.delete()
                }
                return@forEachIndexed
            }
            if (header != null && header.isCompressedRaw) {
                skippedCount++
                safeLogWarn("skip compressed raw TEX conversion file=${texFile.name} payloadSize=${header.payloadSize}")
                return@forEachIndexed
            }
            if (texFile.length() > MAX_IN_MEMORY_TEX_SIZE_BYTES) {
                skippedCount++
                safeLogWarn("skip oversized TEX conversion file=${texFile.name} size=${texFile.length()}")
                return@forEachIndexed
            }

            val conversion = runCatching {
                WallpaperEngineTexConverter.convert(texFile.readBytes())
            }.onFailure { error ->
                safeLogWarn("convert TEX failed file=${texFile.name}", error)
            }.getOrNull()
            if (conversion == null) {
                skippedCount++
                return@forEachIndexed
            }
            val outputFile = createUniqueConvertedSibling(texFile, conversion.extension)
            outputFile.writeBytes(conversion.bytes)
            convertedCount++
            if (!keepOriginalTex) {
                texFile.delete()
            }
        }

        return buildTexSummary(
            totalCount = texFiles.size,
            convertedCount = convertedCount,
            skippedCount = skippedCount,
            failedCount = failedCount,
            keepOriginalTex = keepOriginalTex,
        )
    }

    private fun createUniqueConvertedSibling(
        texFile: File,
        extension: String,
    ): File {
        val parent = texFile.parentFile ?: return texFile
        val baseName = sanitizeFileName(texFile.nameWithoutExtension, "converted")
        var candidate = File(parent, "$baseName.$extension")
        var suffix = 2
        while (candidate.exists()) {
            candidate = File(parent, "${baseName}_$suffix.$extension")
            suffix++
        }
        return candidate
    }

    private fun recreatePath(target: File) {
        if (target.exists()) {
            target.deleteRecursively()
        }
        target.parentFile?.mkdirs()
    }

    companion object {
        private const val TAG = "WEPkgPostProcessor"
        private const val MAX_IN_MEMORY_TEX_SIZE_BYTES = 64L * 1024L * 1024L
    }
}

private fun buildTexSummary(
    totalCount: Int,
    convertedCount: Int,
    skippedCount: Int,
    failedCount: Int,
    keepOriginalTex: Boolean,
): String {
    if (totalCount <= 0) return "未发现 TEX 资源"
    return buildList {
        if (convertedCount > 0) {
            add(
                if (keepOriginalTex) {
                    "TEX 已转换 $convertedCount 项，并保留原 TEX"
                } else {
                    "TEX 已转换 $convertedCount 项"
                },
            )
        }
        if (skippedCount > 0) {
            add("保留原 TEX $skippedCount 项")
        }
        if (failedCount > 0) {
            add("转换失败 $failedCount 项")
        }
    }.ifEmpty {
        listOf("未发现可转换的 TEX 资源")
    }.joinToString("，")
}

private suspend fun copyStreamWithCancellation(
    input: BufferedInputStream,
    output: ZipOutputStream,
) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        currentCoroutineContext().ensureActive()
        val read = input.read(buffer)
        if (read < 0) break
        if (read > 0) {
            output.write(buffer, 0, read)
        }
    }
}

private fun safeLogWarn(
    message: String,
    throwable: Throwable? = null,
) {
    runCatching { Log.w("WEPkgPostProcessor", message, throwable) }
}

internal object WallpaperEnginePkgExtractor {
    private const val MAX_HEADER_LENGTH = 256L
    private const val MAX_FILE_COUNT = 200_000L
    private const val MAX_PATH_LENGTH = 16_384L
    private const val COPY_BUFFER_SIZE = 1024 * 1024

    private data class PkgEntry(
        val path: String,
        val offset: Long,
        val size: Long,
    )

    suspend fun extract(
        pkgFile: File,
        outputDir: File,
        ensureActive: () -> Unit = {},
    ) {
        RandomAccessFile(pkgFile, "r").use { file ->
            val versionLength = file.readUInt32Le()
            require(versionLength in 1..MAX_HEADER_LENGTH) { "无效的 PKG 头长度: $versionLength" }
            val versionBytes = ByteArray(versionLength.toInt())
            file.readFully(versionBytes)
            val version = versionBytes.toString(Charsets.UTF_8)
            require(version.startsWith("PKGV")) { "不支持的 PKG 版本: $version" }

            val fileCount = file.readUInt32Le()
            require(fileCount in 0..MAX_FILE_COUNT) { "无效的 PKG 文件数: $fileCount" }
            val entries = ArrayList<PkgEntry>(fileCount.toInt())

            repeat(fileCount.toInt()) { index ->
                ensureActive()
                val pathLength = file.readUInt32Le()
                require(pathLength in 1..MAX_PATH_LENGTH) { "无效的 PKG 路径长度: $pathLength" }
                val pathBytes = ByteArray(pathLength.toInt())
                file.readFully(pathBytes)
                val rawPath = pathBytes.toString(Charsets.UTF_8)
                val offset = file.readUInt32Le()
                val size = file.readUInt32Le()
                entries += PkgEntry(
                    path = sanitizePkgRelativePath(rawPath, index),
                    offset = offset,
                    size = size,
                )
            }

            val dataStart = file.filePointer
            val totalSize = file.length()
            val buffer = ByteArray(COPY_BUFFER_SIZE)

            entries.forEachIndexed { index, entry ->
                ensureActive()
                val entryStart = dataStart + entry.offset
                val entryEnd = entryStart + entry.size
                require(entryStart in dataStart..totalSize) { "PKG 条目偏移超出范围: ${entry.path}" }
                require(entryEnd in dataStart..totalSize) { "PKG 条目大小超出范围: ${entry.path}" }

                val targetFile = ensureUniqueTarget(File(outputDir, entry.path), index)
                targetFile.parentFile?.mkdirs()
                file.seek(entryStart)
                FileOutputStream(targetFile).use { output ->
                    file.copyFixedBytesTo(output, entry.size, buffer, ensureActive)
                }
            }
        }
    }

    private fun sanitizePkgRelativePath(
        rawPath: String,
        index: Int,
    ): String {
        val normalized = rawPath.replace('\\', '/')
        val segments = normalized.split('/')
            .filter { it.isNotBlank() && it != "." && it != ".." }
            .mapIndexed { segmentIndex, segment ->
                sanitizeFileName(
                    raw = segment,
                    fallback = if (segmentIndex == 0) "entry_$index" else "file",
                )
            }
        return segments.joinToString("/").ifBlank { "entry_$index.bin" }
    }

    private fun ensureUniqueTarget(
        targetFile: File,
        index: Int,
    ): File {
        if (!targetFile.exists()) return targetFile
        val parent = targetFile.parentFile ?: return targetFile
        val baseName = targetFile.nameWithoutExtension.ifBlank { "entry_$index" }
        val extension = targetFile.extension.takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
        var suffix = 1
        var candidate = File(parent, "${baseName}_$suffix$extension")
        while (candidate.exists()) {
            suffix++
            candidate = File(parent, "${baseName}_$suffix$extension")
        }
        return candidate
    }

    private fun RandomAccessFile.readUInt32Le(): Long {
        return Integer.toUnsignedLong(java.lang.Integer.reverseBytes(readInt()))
    }

    private fun RandomAccessFile.copyFixedBytesTo(
        output: FileOutputStream,
        byteCount: Long,
        buffer: ByteArray,
        ensureActive: () -> Unit,
    ) {
        var remaining = byteCount
        while (remaining > 0L) {
            ensureActive()
            val readSize = minOf(buffer.size.toLong(), remaining).toInt()
            val actualRead = read(buffer, 0, readSize)
            check(actualRead >= 0) { "PKG 内容读取中断" }
            output.write(buffer, 0, actualRead)
            remaining -= actualRead.toLong()
        }
    }
}
