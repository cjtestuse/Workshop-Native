package com.slay.workshopnative.data.repository

import com.slay.workshopnative.data.model.GameDetails
import com.slay.workshopnative.data.model.OwnedGame
import com.slay.workshopnative.data.model.SteamSessionState
import com.slay.workshopnative.data.model.WorkshopBrowsePage
import com.slay.workshopnative.data.model.WorkshopBrowseQuery
import com.slay.workshopnative.data.model.WorkshopGameEntry
import com.slay.workshopnative.data.model.WorkshopGamePage
import com.slay.workshopnative.data.model.WorkshopItem
import com.slay.workshopnative.data.remote.SteamSessionManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.StateFlow

@Singleton
class SteamRepositoryImpl @Inject constructor(
    private val sessionManager: SteamSessionManager,
) : SteamRepository {

    override val sessionState: StateFlow<SteamSessionState> = sessionManager.sessionState

    override suspend fun bootstrap() {
        sessionManager.bootstrap()
    }

    override fun retryRestore() {
        sessionManager.retryRestore()
    }

    override fun onAppForegrounded() {
        sessionManager.onAppForegrounded()
    }

    override fun login(username: String, password: String, rememberSession: Boolean) {
        sessionManager.login(username, password, rememberSession)
    }

    override fun submitAuthCode(code: String) {
        sessionManager.submitAuthCode(code)
    }

    override fun prewarmAnonymousDownloadAccess() {
        sessionManager.prewarmAnonymousDownloadAccess()
    }

    override suspend fun logout() {
        sessionManager.logout()
    }

    override suspend fun clearOwnedGamesCache() {
        sessionManager.clearOwnedGamesCache()
    }

    override suspend fun switchSavedAccount(accountKey: String): Result<Unit> {
        return runCatching { sessionManager.switchSavedAccount(accountKey) }
    }

    override suspend fun loadOwnedGames(forceRefresh: Boolean): Result<List<OwnedGame>> {
        return runCatching { sessionManager.loadOwnedGames(forceRefresh) }
    }

    override suspend fun loadOwnedGamesSnapshot(): Result<List<OwnedGame>> {
        return runCatching { sessionManager.loadOwnedGamesSnapshot() }
    }

    override suspend fun loadGameDetails(appId: Int): Result<GameDetails> {
        return runCatching { sessionManager.loadGameDetails(appId) }
    }

    override suspend fun loadWorkshopExplorePage(page: Int): Result<WorkshopGamePage> {
        return runCatching { sessionManager.loadWorkshopExplorePage(page) }
    }

    override suspend fun searchWorkshopGames(query: String): Result<List<WorkshopGameEntry>> {
        return runCatching { sessionManager.searchWorkshopGames(query) }
    }

    override suspend fun loadWorkshopBrowsePage(
        appId: Int,
        query: WorkshopBrowseQuery,
        forceRefresh: Boolean,
    ): Result<WorkshopBrowsePage> {
        return runCatching { sessionManager.loadWorkshopBrowsePage(appId, query, forceRefresh) }
    }

    override suspend fun resolveWorkshopItems(publishedFileIds: Collection<Long>): Result<List<WorkshopItem>> {
        return runCatching { sessionManager.resolveWorkshopItems(publishedFileIds) }
    }

    override suspend fun resolveWorkshopItem(publishedFileId: Long): Result<WorkshopItem> {
        return runCatching { sessionManager.resolveWorkshopItem(publishedFileId) }
    }
}
