package com.slay.workshopnative

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.slay.workshopnative.data.local.DownloadStatus
import com.slay.workshopnative.data.local.DownloadTaskDao
import com.slay.workshopnative.data.local.DownloadTaskEntity
import com.slay.workshopnative.data.model.SessionStatus
import com.slay.workshopnative.data.model.WorkshopBrowseQuery
import com.slay.workshopnative.data.model.WorkshopItem
import com.slay.workshopnative.data.preferences.DownloadPerformanceMode
import com.slay.workshopnative.data.preferences.UserPreferencesStore
import com.slay.workshopnative.data.repository.DownloadsRepository
import com.slay.workshopnative.data.repository.SteamRepository
import dagger.hilt.android.EntryPointAccessors
import com.slay.workshopnative.testing.DownloadTestEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadPerformanceInstrumentedTest {

    private data class ObservedTaskResult(
        val task: DownloadTaskEntity,
        val observedEndpointLabel: String?,
    )

    private data class DownloadRunResult(
        val label: String,
        val title: String,
        val publishedFileId: Long,
        val status: DownloadStatus,
        val authMode: String,
        val runtimeChunkConcurrency: Int,
        val runtimeAttemptCount: Int,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val route: String?,
        val transport: String?,
        val endpointLabel: String?,
        val errorMessage: String?,
    )

    private val entryPoint: DownloadTestEntryPoint by lazy {
        EntryPointAccessors.fromApplication(
            ApplicationProvider.getApplicationContext<Context>(),
            DownloadTestEntryPoint::class.java,
        )
    }

    private val steamRepository: SteamRepository
        get() = entryPoint.steamRepository()

    private val downloadsRepository: DownloadsRepository
        get() = entryPoint.downloadsRepository()

    private val preferencesStore: UserPreferencesStore
        get() = entryPoint.preferencesStore()

    private val downloadTaskDao: DownloadTaskDao
        get() = entryPoint.downloadTaskDao()

    @Test
    fun compareAutoAndPrimordialForAnonymousDownload() = runBlocking {
        val originalPrefs = preferencesStore.snapshot()
        try {
            steamRepository.bootstrap()
            enableDownloadCapabilities()

            val anonymousAuto = runAnonymousScenario(
                mode = DownloadPerformanceMode.Auto,
                title = "谋黄忠角色模组",
            )
            val anonymousPrimordial = runAnonymousScenario(
                mode = DownloadPerformanceMode.Primordial,
                title = "谋黄忠角色模组",
            )

            logResult("anonymous-auto", anonymousAuto)
            logResult("anonymous-primordial", anonymousPrimordial)

            assertEquals(DownloadStatus.Success, anonymousAuto.status)
            assertEquals(DownloadStatus.Success, anonymousPrimordial.status)

            assertEquals("Anonymous", anonymousAuto.authMode)
            assertEquals("Anonymous", anonymousPrimordial.authMode)

            assertTrue(
                "匿名洪荒模式应高于自动高性能，实际 ${anonymousAuto.runtimeChunkConcurrency} -> ${anonymousPrimordial.runtimeChunkConcurrency}",
                anonymousPrimordial.runtimeChunkConcurrency > anonymousAuto.runtimeChunkConcurrency,
            )
        } finally {
            restorePreferences(originalPrefs)
        }
    }

    @Test
    fun compareAutoAndPrimordialForAuthenticatedDownload() = runBlocking {
        val originalPrefs = preferencesStore.snapshot()
        try {
            steamRepository.bootstrap()
            enableDownloadCapabilities()
            ensureAuthenticatedSession()

            val accountAuto = runAuthenticatedScenario(
                mode = DownloadPerformanceMode.Auto,
                title = "鸣潮-忧郁漂泊者",
            )
            val accountPrimordial = runAuthenticatedScenario(
                mode = DownloadPerformanceMode.Primordial,
                title = "鸣潮-忧郁漂泊者",
            )

            logResult("account-auto", accountAuto)
            logResult("account-primordial", accountPrimordial)

            assertEquals(DownloadStatus.Success, accountAuto.status)
            assertEquals(DownloadStatus.Success, accountPrimordial.status)
            assertEquals("Authenticated", accountAuto.authMode)
            assertEquals("Authenticated", accountPrimordial.authMode)
            assertTrue(
                "账号洪荒模式应高于自动高性能，实际 ${accountAuto.runtimeChunkConcurrency} -> ${accountPrimordial.runtimeChunkConcurrency}",
                accountPrimordial.runtimeChunkConcurrency > accountAuto.runtimeChunkConcurrency,
            )
        } finally {
            restorePreferences(originalPrefs)
        }
    }

    @Test
    fun compareAuthenticatedPrimordialWithAggressiveCdnDisabledAndEnabled() = runBlocking {
        val originalPrefs = preferencesStore.snapshot()
        try {
            steamRepository.bootstrap()
            enableDownloadCapabilities()
            ensureAuthenticatedSession()

            preferencesStore.saveDownloadPerformanceMode(DownloadPerformanceMode.Primordial)
            preferencesStore.savePrimordialReducedMemoryProtectionEnabled(false)

            val conservative = runAuthenticatedScenario(
                mode = DownloadPerformanceMode.Primordial,
                title = "鸣潮-忧郁漂泊者",
                aggressiveCdnEnabled = false,
            )
            val aggressive = runAuthenticatedScenario(
                mode = DownloadPerformanceMode.Primordial,
                title = "鸣潮-忧郁漂泊者",
                aggressiveCdnEnabled = true,
            )

            logResult("account-primordial-conservative", conservative)
            logResult("account-primordial-aggressive", aggressive)

            assertEquals(DownloadStatus.Success, conservative.status)
            assertEquals(DownloadStatus.Success, aggressive.status)
            assertEquals("Authenticated", conservative.authMode)
            assertEquals("Authenticated", aggressive.authMode)
            assertTrue(
                "开启账户激进 CDN 后，应扩大首批并发 CDN 节点数，实际 ${conservative.endpointLabel} -> ${aggressive.endpointLabel}",
                endpointRouteCount(aggressive.endpointLabel) > endpointRouteCount(conservative.endpointLabel),
            )
        } finally {
            restorePreferences(originalPrefs)
        }
    }

    private suspend fun runAnonymousScenario(
        mode: DownloadPerformanceMode,
        title: String,
    ): DownloadRunResult {
        preferencesStore.saveLoginFeatureEnabled(false)
        preferencesStore.saveLoggedInDownloadEnabled(false)
        preferencesStore.savePreferAnonymousDownloads(true)
        preferencesStore.saveAllowAuthenticatedDownloadFallback(false)
        preferencesStore.saveDownloadPerformanceMode(mode)

        val item = steamRepository.resolveWorkshopItemForDownload(ANONYMOUS_TEST_PUBLISHED_FILE_ID).getOrThrow()
        assertTrue("匿名测试对象不匹配：${item.title}", item.title.contains(title))
        return enqueueAndWait(label = "anonymous-${mode.name}", item = item)
    }

    private suspend fun runAuthenticatedScenario(
        mode: DownloadPerformanceMode,
        title: String,
        aggressiveCdnEnabled: Boolean = false,
    ): DownloadRunResult {
        ensureAuthenticatedSession()

        preferencesStore.savePreferAnonymousDownloads(false)
        preferencesStore.saveDownloadPerformanceMode(mode)
        preferencesStore.savePrimordialAuthenticatedAggressiveCdnEnabled(aggressiveCdnEnabled)

        runCatching {
            val publicPage = steamRepository.loadWorkshopBrowsePage(
                appId = 431960,
                query = WorkshopBrowseQuery(
                    searchText = title,
                    pageSize = 30,
                ),
                forceRefresh = true,
            ).getOrThrow()
            val publicVisible = publicPage.items.any { it.title.contains(title) }
            Log.i(TAG, "Authenticated scenario public visibility for \"$title\": $publicVisible")
        }.onFailure { error ->
            Log.w(TAG, "Authenticated scenario public visibility probe failed for \"$title\": ${error.message}")
        }

        val item = steamRepository.resolveWorkshopItemForDownload(AUTHENTICATED_TEST_PUBLISHED_FILE_ID).getOrThrow()
        assertTrue("账号测试对象不匹配：${item.title}", item.title.contains(title))
        return enqueueAndWait(label = "account-${mode.name}", item = item)
    }

    private suspend fun findPublicItem(appId: Int, title: String): WorkshopItem {
        val page = steamRepository.loadWorkshopBrowsePage(
            appId = appId,
            query = WorkshopBrowseQuery(
                searchText = title,
                pageSize = 30,
            ),
            forceRefresh = true,
        ).getOrThrow()
        val match = page.items.firstOrNull { it.title.contains(title) }
        assertNotNull("公开工坊里没有找到条目：$title", match)
        return steamRepository.resolveWorkshopItemForDownload(checkNotNull(match).publishedFileId).getOrThrow()
    }

    private suspend fun ensureAuthenticatedSession() {
        if (steamRepository.sessionState.value.status == SessionStatus.Authenticated &&
            steamRepository.isAuthenticatedDownloadReady()
        ) {
            Log.i(TAG, "Authenticated scenario will reuse current in-memory Steam session")
            return
        }
        val savedAccount = preferencesStore.snapshot().savedAccounts.firstOrNull()
        assertNotNull(
            "当前设备既没有在线的 Steam 会话，也没有已保存账号；请先在模拟器里登录并保持当前会话可用后再跑账号态下载测试",
            savedAccount,
        )
        steamRepository.switchSavedAccount(checkNotNull(savedAccount).stableKey()).getOrThrow()
        waitForSessionStatus(expectAuthenticated = true)
    }

    private suspend fun findAuthenticatedItem(appId: Int, title: String): WorkshopItem {
        val page = steamRepository.loadAuthenticatedWorkshopQueryPage(
            appId = appId,
            query = WorkshopBrowseQuery(
                searchText = title,
                pageSize = 30,
            ),
            forceRefresh = true,
        ).getOrThrow()
        val match = page.items.firstOrNull { it.title.contains(title) }
        assertNotNull("账号可见查询里没有找到条目：$title", match)
        return steamRepository.resolveWorkshopItemForDownload(checkNotNull(match).publishedFileId).getOrThrow()
    }

    private suspend fun enqueueAndWait(
        label: String,
        item: WorkshopItem,
    ): DownloadRunResult {
        val startAt = System.currentTimeMillis()
        downloadsRepository.enqueue(item).getOrThrow()
        val observed = waitForTask(item.publishedFileId, startAt)
        val task = observed.task
        return DownloadRunResult(
            label = label,
            title = task.title,
            publishedFileId = task.publishedFileId,
            status = task.status,
            authMode = task.downloadAuthMode.name,
            runtimeChunkConcurrency = task.runtimeChunkConcurrency,
            runtimeAttemptCount = task.runtimeAttemptCount,
            bytesDownloaded = task.bytesDownloaded,
            totalBytes = task.totalBytes,
            route = task.runtimeRouteLabel,
            transport = task.runtimeTransportLabel,
            endpointLabel = observed.observedEndpointLabel,
            errorMessage = task.errorMessage,
        )
    }

    private suspend fun waitForTask(
        publishedFileId: Long,
        createdAfterMs: Long,
    ): ObservedTaskResult {
        return withTimeout(12 * 60 * 1000L) {
            var observedEndpointLabel: String? = null
            while (true) {
                val task = downloadTaskDao.getAll()
                    .firstOrNull { it.publishedFileId == publishedFileId && it.createdAt >= createdAfterMs }
                if (task != null) {
                    val currentEndpointLabel = task.runtimeEndpointLabel
                    if (
                        !currentEndpointLabel.isNullOrBlank() &&
                        endpointRouteCount(currentEndpointLabel) >= endpointRouteCount(observedEndpointLabel)
                    ) {
                        observedEndpointLabel = currentEndpointLabel
                    }
                }
                if (task != null && task.status !in ACTIVE_STATUSES) {
                    return@withTimeout ObservedTaskResult(
                        task = task,
                        observedEndpointLabel = observedEndpointLabel,
                    )
                }
                delay(1_000L)
            }
            error("unreachable")
        }
    }

    private suspend fun waitForSessionStatus(expectAuthenticated: Boolean) {
        withTimeout(90_000L) {
            while (true) {
                val status = steamRepository.sessionState.value.status
                if (expectAuthenticated) {
                    if (status == SessionStatus.Authenticated) return@withTimeout
                } else if (status != SessionStatus.Authenticated && !steamRepository.isAuthenticatedDownloadReady()) {
                    return@withTimeout
                }
                delay(1_000L)
            }
        }
    }

    private suspend fun enableDownloadCapabilities() {
        preferencesStore.saveLoginFeatureEnabled(true)
        preferencesStore.saveLoggedInDownloadEnabled(true)
        preferencesStore.savePreferAnonymousDownloads(true)
        preferencesStore.saveAllowAuthenticatedDownloadFallback(true)
        preferencesStore.savePrimordialReducedMemoryProtectionEnabled(false)
        preferencesStore.savePrimordialAuthenticatedAggressiveCdnEnabled(false)
    }

    private suspend fun restorePreferences(original: com.slay.workshopnative.data.preferences.UserPreferences) {
        preferencesStore.saveLoginFeatureEnabled(original.isLoginFeatureEnabled)
        preferencesStore.saveLoggedInDownloadEnabled(original.isLoggedInDownloadEnabled)
        preferencesStore.savePreferAnonymousDownloads(original.preferAnonymousDownloads)
        preferencesStore.saveAllowAuthenticatedDownloadFallback(original.allowAuthenticatedDownloadFallback)
        preferencesStore.saveDownloadPerformanceMode(original.downloadPerformanceMode)
        preferencesStore.savePrimordialReducedMemoryProtectionEnabled(original.primordialReducedMemoryProtectionEnabled)
        preferencesStore.savePrimordialAuthenticatedAggressiveCdnEnabled(original.primordialAuthenticatedAggressiveCdnEnabled)
    }

    private fun logResult(prefix: String, result: DownloadRunResult) {
        val message = buildString {
            append(prefix)
            append(": title=")
            append(result.title)
            append(", publishedFileId=")
            append(result.publishedFileId)
            append(", status=")
            append(result.status)
            append(", authMode=")
            append(result.authMode)
            append(", chunkConcurrency=")
            append(result.runtimeChunkConcurrency)
            append(", attempts=")
            append(result.runtimeAttemptCount)
            append(", bytes=")
            append(result.bytesDownloaded)
            append("/")
            append(result.totalBytes)
            append(", route=")
            append(result.route)
            append(", transport=")
            append(result.transport)
            append(", endpoint=")
            append(result.endpointLabel)
            append(", error=")
            append(result.errorMessage)
        }
        Log.i(TAG, message)
        println(message)
    }

    private fun endpointRouteCount(endpointLabel: String?): Int {
        return endpointLabel
            ?.substringBefore(" 个 CDN 节点并发")
            ?.toIntOrNull()
            ?: 0
    }

    private companion object {
        const val TAG = "DownloadPerfTest"
        const val ANONYMOUS_TEST_PUBLISHED_FILE_ID = 3686133388L
        const val AUTHENTICATED_TEST_PUBLISHED_FILE_ID = 3686245041L
        val ACTIVE_STATUSES = setOf(
            DownloadStatus.Queued,
            DownloadStatus.Running,
            DownloadStatus.Paused,
        )
    }
}
