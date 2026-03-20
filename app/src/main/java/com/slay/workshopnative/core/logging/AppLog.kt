package com.slay.workshopnative.core.logging

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import com.slay.workshopnative.BuildConfig
import com.slay.workshopnative.core.storage.copyLocalFileToUri
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppLogSummary(
    val runtimeLogCount: Int = 0,
    val crashLogCount: Int = 0,
    val totalBytes: Long = 0L,
    val latestRuntimeAtMillis: Long? = null,
    val latestCrashAtMillis: Long? = null,
) {
    val totalLogCount: Int
        get() = runtimeLogCount + crashLogCount

    val hasLogs: Boolean
        get() = totalLogCount > 0
}

data class AppLogExportResult(
    val fileName: String,
    val exportedEntryCount: Int,
    val totalBytes: Long,
)

data class AppLogDeletionResult(
    val deletedFileCount: Int,
    val reclaimedBytes: Long,
)

object AppLog {
    private const val LOGGER_TAG = "AppLog"
    private const val LOGS_DIR_NAME = "logs"
    private const val RUNTIME_DIR_NAME = "runtime"
    private const val CRASH_DIR_NAME = "crash"
    private const val EXPORT_DIR_NAME = "export-cache"
    private const val LOG_FILE_PREFIX = "session-"
    private const val CRASH_FILE_PREFIX = "crash-"
    private const val LOG_FILE_EXTENSION = ".log"
    private const val EXPORT_FILE_PREFIX = "workshop-native-logs-"
    private const val EXPORT_FILE_EXTENSION = ".zip"
    private const val MAX_RUNTIME_LOG_FILES = 8
    private const val MAX_CRASH_LOG_FILES = 5
    private const val MAX_RUNTIME_LOG_BYTES = 8L * 1024L * 1024L
    private const val MAX_CRASH_LOG_BYTES = 4L * 1024L * 1024L
    private const val PRUNE_INTERVAL_MS = 60_000L

    private val lock = Any()
    private val zoneId: ZoneId = ZoneId.systemDefault()
    private val fileTimestampFormatter: DateTimeFormatter = DateTimeFormatter
        .ofPattern("yyyyMMdd-HHmmss", Locale.US)
        .withZone(zoneId)
    private val lineTimestampFormatter: DateTimeFormatter = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        .withZone(zoneId)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var sessionFile: File? = null

    @Volatile
    private var previousExceptionHandler: Thread.UncaughtExceptionHandler? = null

    @Volatile
    private var uncaughtExceptionHandlerInstalled = false

    @Volatile
    private var lastPruneAtMillis = 0L

    fun initialize(context: Context) {
        val applicationContext = context.applicationContext
        synchronized(lock) {
            appContext = applicationContext
            ensureDirectoriesLocked(applicationContext)
            pruneLogsLocked(applicationContext, force = true)
            openSessionLocked(applicationContext, reason = "process_start")
            installUncaughtExceptionHandlerLocked()
        }
        i(LOGGER_TAG, "logger initialized version=${BuildConfig.VERSION_NAME} debug=${BuildConfig.DEBUG}")
    }

    fun retentionPolicySummary(): String {
        return "自动保留最近 $MAX_RUNTIME_LOG_FILES 份运行日志和 $MAX_CRASH_LOG_FILES 份崩溃日志，总容量分别不超过 " +
            "${formatBytes(MAX_RUNTIME_LOG_BYTES)} / ${formatBytes(MAX_CRASH_LOG_BYTES)}，超出后会清理最旧文件。"
    }

    fun summary(): AppLogSummary {
        val context = appContext ?: return AppLogSummary()
        synchronized(lock) {
            val runtimeFiles = listLogFiles(runtimeDir(context))
            val crashFiles = listLogFiles(crashDir(context))
            return AppLogSummary(
                runtimeLogCount = runtimeFiles.size,
                crashLogCount = crashFiles.size,
                totalBytes = runtimeFiles.sumOf(File::length) + crashFiles.sumOf(File::length),
                latestRuntimeAtMillis = runtimeFiles.maxOfOrNull(File::lastModified),
                latestCrashAtMillis = crashFiles.maxOfOrNull(File::lastModified),
            )
        }
    }

    suspend fun exportToUri(targetUri: Uri): Result<AppLogExportResult> = exportSupportBundle(
        targetUri = targetUri,
        extraEntries = emptyList(),
    )

    suspend fun exportSupportBundle(
        targetUri: Uri,
        extraEntries: List<SupportBundleTextEntry>,
    ): Result<AppLogExportResult> = withContext(Dispatchers.IO) {
        runCatching {
            val context = appContext ?: error("Logger is not initialized")
            val snapshot = synchronized(lock) {
                val runtimeFiles = listLogFiles(runtimeDir(context))
                val crashFiles = listLogFiles(crashDir(context))
                ExportSnapshot(
                    createdAtMillis = System.currentTimeMillis(),
                    runtimeFiles = runtimeFiles,
                    crashFiles = crashFiles,
                    summary = AppLogSummary(
                        runtimeLogCount = runtimeFiles.size,
                        crashLogCount = crashFiles.size,
                        totalBytes = runtimeFiles.sumOf(File::length) + crashFiles.sumOf(File::length),
                        latestRuntimeAtMillis = runtimeFiles.maxOfOrNull(File::lastModified),
                        latestCrashAtMillis = crashFiles.maxOfOrNull(File::lastModified),
                    ),
                )
            }

            val exportFile = File(
                exportDir(context),
                EXPORT_FILE_PREFIX + fileTimestamp(snapshot.createdAtMillis) + EXPORT_FILE_EXTENSION,
            )
            buildExportZip(exportFile, snapshot, extraEntries)
            try {
                copyLocalFileToUri(context, exportFile, targetUri)
                AppLogExportResult(
                    fileName = exportFile.name,
                    exportedEntryCount = snapshot.runtimeFiles.size + snapshot.crashFiles.size + 1 + extraEntries.size,
                    totalBytes = exportFile.length(),
                )
            } finally {
                exportFile.delete()
            }
        }
    }

    fun clearRuntimeLogs(): AppLogDeletionResult {
        val context = appContext ?: return AppLogDeletionResult(0, 0L)
        synchronized(lock) {
            val runtimeFiles = listLogFiles(runtimeDir(context))
            val bytes = runtimeFiles.sumOf(File::length)
            deleteFiles(runtimeFiles)
            sessionFile = null
            return AppLogDeletionResult(
                deletedFileCount = runtimeFiles.size,
                reclaimedBytes = bytes,
            )
        }
    }

    fun clearCrashLogs(): AppLogDeletionResult {
        val context = appContext ?: return AppLogDeletionResult(0, 0L)
        synchronized(lock) {
            val crashFiles = listLogFiles(crashDir(context))
            val bytes = crashFiles.sumOf(File::length)
            deleteFiles(crashFiles)
            return AppLogDeletionResult(
                deletedFileCount = crashFiles.size,
                reclaimedBytes = bytes,
            )
        }
    }

    fun clearAllLogs(): AppLogDeletionResult {
        val context = appContext ?: return AppLogDeletionResult(0, 0L)
        synchronized(lock) {
            val runtimeFiles = listLogFiles(runtimeDir(context))
            val crashFiles = listLogFiles(crashDir(context))
            val files = runtimeFiles + crashFiles
            val bytes = files.sumOf(File::length)
            deleteFiles(files)
            sessionFile = null
            exportDir(context).listFiles()?.forEach(File::delete)
            return AppLogDeletionResult(
                deletedFileCount = files.size,
                reclaimedBytes = bytes,
            )
        }
    }

    fun d(tag: String, message: String, throwable: Throwable? = null): Int =
        write(Log.DEBUG, tag, message, throwable)

    fun i(tag: String, message: String, throwable: Throwable? = null): Int =
        write(Log.INFO, tag, message, throwable)

    fun w(tag: String, message: String, throwable: Throwable? = null): Int =
        write(Log.WARN, tag, message, throwable)

    fun e(tag: String, message: String, throwable: Throwable? = null): Int =
        write(Log.ERROR, tag, message, throwable)

    private fun write(
        priority: Int,
        tag: String,
        message: String,
        throwable: Throwable?,
    ): Int {
        val logResult = when (priority) {
            Log.DEBUG -> if (throwable == null) Log.d(tag, message) else Log.d(tag, message, throwable)
            Log.INFO -> if (throwable == null) Log.i(tag, message) else Log.i(tag, message, throwable)
            Log.WARN -> if (throwable == null) Log.w(tag, message) else Log.w(tag, message, throwable)
            Log.ERROR -> if (throwable == null) Log.e(tag, message) else Log.e(tag, message, throwable)
            else -> Log.println(priority, tag, message)
        }

        val context = appContext ?: return logResult
        synchronized(lock) {
            val file = sessionFile ?: openSessionLocked(context, reason = "lazy_resume")
            appendLineLocked(
                file = file,
                line = buildLine(
                    priority = priority,
                    tag = tag,
                    message = message,
                    threadName = Thread.currentThread().name,
                ),
            )
            throwable?.let { appendTextBlockLocked(file, stackTraceOf(it)) }
            pruneLogsLocked(context)
        }
        return logResult
    }

    private fun installUncaughtExceptionHandlerLocked() {
        if (uncaughtExceptionHandlerInstalled) return
        previousExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                recordCrash(thread, throwable)
            }.onFailure { failure ->
                Log.e(LOGGER_TAG, "failed to persist crash report", failure)
            }
            previousExceptionHandler?.uncaughtException(thread, throwable)
        }
        uncaughtExceptionHandlerInstalled = true
    }

    private fun recordCrash(thread: Thread, throwable: Throwable) {
        val context = appContext ?: return
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val crashFile = File(
                crashDir(context),
                CRASH_FILE_PREFIX + fileTimestamp(now) + LOG_FILE_EXTENSION,
            )
            crashFile.parentFile?.mkdirs()
            val runtimeFileName = sessionFile?.name ?: "none"
            val payload = buildString {
                appendLine("Workshop Native crash report")
                appendLine("generated_at=${instantText(now)}")
                appendLine("app_version=${BuildConfig.VERSION_NAME}")
                appendLine("version_code=${BuildConfig.VERSION_CODE}")
                appendLine("debug_build=${BuildConfig.DEBUG}")
                appendLine("android_sdk=${Build.VERSION.SDK_INT}")
                appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("brand=${Build.BRAND}")
                appendLine("thread=${thread.name}")
                appendLine("runtime_session=$runtimeFileName")
                appendLine()
                append(stackTraceOf(throwable))
            }
            crashFile.writeText(payload, Charsets.UTF_8)

            val runtimeFile = sessionFile ?: openSessionLocked(context, reason = "crash_capture")
            appendLineLocked(
                runtimeFile,
                buildLine(
                    priority = Log.ERROR,
                    tag = LOGGER_TAG,
                    message = "uncaught exception recorded to ${crashFile.name}",
                    threadName = thread.name,
                ),
            )
            appendTextBlockLocked(runtimeFile, stackTraceOf(throwable))
            pruneLogsLocked(context, force = true)
        }
    }

    private fun buildExportZip(
        exportFile: File,
        snapshot: ExportSnapshot,
        extraEntries: List<SupportBundleTextEntry>,
    ) {
        exportFile.parentFile?.mkdirs()
        ZipOutputStream(exportFile.outputStream().buffered()).use { zip ->
            addTextEntry(
                zip = zip,
                name = "manifest.txt",
                payload = buildManifestText(snapshot),
            )
            snapshot.runtimeFiles.forEach { file ->
                addFileEntry(zip, "runtime/${file.name}", file)
            }
            snapshot.crashFiles.forEach { file ->
                addFileEntry(zip, "crash/${file.name}", file)
            }
            extraEntries.forEach { entry ->
                addTextEntry(
                    zip = zip,
                    name = entry.name,
                    payload = entry.payload,
                )
            }
        }
    }

    private fun buildManifestText(snapshot: ExportSnapshot): String {
        return buildString {
            appendLine("Workshop Native log export")
            appendLine("exported_at=${instantText(snapshot.createdAtMillis)}")
            appendLine("app_version=${BuildConfig.VERSION_NAME}")
            appendLine("version_code=${BuildConfig.VERSION_CODE}")
            appendLine("debug_build=${BuildConfig.DEBUG}")
            appendLine("android_sdk=${Build.VERSION.SDK_INT}")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("brand=${Build.BRAND}")
            appendLine("runtime_log_count=${snapshot.summary.runtimeLogCount}")
            appendLine("crash_log_count=${snapshot.summary.crashLogCount}")
            appendLine("total_bytes=${snapshot.summary.totalBytes}")
            appendLine("total_size=${formatBytes(snapshot.summary.totalBytes)}")
            appendLine("latest_runtime_at=${snapshot.summary.latestRuntimeAtMillis?.let(::instantText) ?: "none"}")
            appendLine("latest_crash_at=${snapshot.summary.latestCrashAtMillis?.let(::instantText) ?: "none"}")
            appendLine("retention=${
                retentionPolicySummary()
            }")
        }
    }

    private fun addTextEntry(
        zip: ZipOutputStream,
        name: String,
        payload: String,
    ) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(payload.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun addFileEntry(
        zip: ZipOutputStream,
        entryName: String,
        file: File,
    ) {
        zip.putNextEntry(ZipEntry(entryName))
        file.inputStream().buffered().use { input ->
            input.copyTo(zip)
        }
        zip.closeEntry()
    }

    private fun buildLine(
        priority: Int,
        tag: String,
        message: String,
        threadName: String,
    ): String {
        return "${instantText(System.currentTimeMillis())} ${priorityLabel(priority)}/$tag [$threadName] $message"
    }

    private fun openSessionLocked(
        context: Context,
        reason: String,
    ): File {
        val file = File(
            runtimeDir(context),
            LOG_FILE_PREFIX + fileTimestamp(System.currentTimeMillis()) + LOG_FILE_EXTENSION,
        )
        file.parentFile?.mkdirs()
        appendLineLocked(file, "Workshop Native runtime log")
        appendLineLocked(file, "opened_at=${instantText(System.currentTimeMillis())}")
        appendLineLocked(file, "reason=$reason")
        appendLineLocked(file, "app_version=${BuildConfig.VERSION_NAME}")
        appendLineLocked(file, "version_code=${BuildConfig.VERSION_CODE}")
        appendLineLocked(file, "debug_build=${BuildConfig.DEBUG}")
        appendLineLocked(file, "android_sdk=${Build.VERSION.SDK_INT}")
        appendLineLocked(file, "device=${Build.MANUFACTURER} ${Build.MODEL}")
        appendLineLocked(file, "")
        sessionFile = file
        return file
    }

    private fun ensureDirectoriesLocked(context: Context) {
        runtimeDir(context).mkdirs()
        crashDir(context).mkdirs()
        exportDir(context).mkdirs()
    }

    private fun runtimeDir(context: Context): File = File(File(context.filesDir, LOGS_DIR_NAME), RUNTIME_DIR_NAME)

    private fun crashDir(context: Context): File = File(File(context.filesDir, LOGS_DIR_NAME), CRASH_DIR_NAME)

    private fun exportDir(context: Context): File = File(File(context.cacheDir, LOGS_DIR_NAME), EXPORT_DIR_NAME)

    private fun listLogFiles(directory: File): List<File> {
        return directory.listFiles()
            ?.filter(File::isFile)
            ?.sortedBy(File::lastModified)
            .orEmpty()
    }

    private fun appendLineLocked(
        file: File,
        line: String,
    ) {
        file.appendText(line + "\n", Charsets.UTF_8)
    }

    private fun appendTextBlockLocked(
        file: File,
        block: String,
    ) {
        block.lineSequence().forEach { line ->
            appendLineLocked(file, line)
        }
    }

    private fun pruneLogsLocked(
        context: Context,
        force: Boolean = false,
    ) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPruneAtMillis < PRUNE_INTERVAL_MS) return
        trimDirectory(runtimeDir(context), MAX_RUNTIME_LOG_FILES, MAX_RUNTIME_LOG_BYTES)
        trimDirectory(crashDir(context), MAX_CRASH_LOG_FILES, MAX_CRASH_LOG_BYTES)
        lastPruneAtMillis = now
    }

    private fun trimDirectory(
        directory: File,
        maxFiles: Int,
        maxBytes: Long,
    ) {
        val files = listLogFiles(directory).toMutableList()
        var totalBytes = files.sumOf(File::length)
        while (files.size > maxFiles || totalBytes > maxBytes) {
            val oldest = files.removeFirstOrNull() ?: break
            totalBytes -= oldest.length()
            if (oldest == sessionFile) {
                sessionFile = null
            }
            oldest.delete()
        }
    }

    private fun deleteFiles(files: List<File>) {
        files.forEach { file ->
            if (file == sessionFile) {
                sessionFile = null
            }
            file.delete()
        }
    }

    private fun fileTimestamp(millis: Long): String {
        return fileTimestampFormatter.format(Instant.ofEpochMilli(millis))
    }

    private fun instantText(millis: Long): String {
        return lineTimestampFormatter.format(Instant.ofEpochMilli(millis))
    }

    private fun priorityLabel(priority: Int): String {
        return when (priority) {
            Log.DEBUG -> "D"
            Log.INFO -> "I"
            Log.WARN -> "W"
            Log.ERROR -> "E"
            else -> priority.toString()
        }
    }

    private fun stackTraceOf(throwable: Throwable): String {
        val writer = StringWriter()
        PrintWriter(writer).use { printWriter ->
            throwable.printStackTrace(printWriter)
        }
        return writer.toString().trimEnd()
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val kb = 1024.0
        val mb = kb * 1024.0
        return when {
            bytes >= mb -> String.format(Locale.US, "%.1f MB", bytes / mb)
            bytes >= kb -> String.format(Locale.US, "%.1f KB", bytes / kb)
            else -> "$bytes B"
        }
    }

    private data class ExportSnapshot(
        val createdAtMillis: Long,
        val runtimeFiles: List<File>,
        val crashFiles: List<File>,
        val summary: AppLogSummary,
    )
}
