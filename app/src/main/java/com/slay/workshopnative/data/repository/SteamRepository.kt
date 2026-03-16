package com.slay.workshopnative.data.repository

import com.slay.workshopnative.data.model.GameDetails
import com.slay.workshopnative.data.model.OwnedGame
import com.slay.workshopnative.data.model.SteamSessionState
import com.slay.workshopnative.data.model.WorkshopBrowsePage
import com.slay.workshopnative.data.model.WorkshopBrowseQuery
import com.slay.workshopnative.data.model.WorkshopGameEntry
import com.slay.workshopnative.data.model.WorkshopGamePage
import com.slay.workshopnative.data.model.WorkshopItem
import kotlinx.coroutines.flow.StateFlow

interface SteamRepository {
    val sessionState: StateFlow<SteamSessionState>

    suspend fun bootstrap()

    fun retryRestore()

    fun onAppForegrounded()

    fun login(username: String, password: String, rememberSession: Boolean)

    fun submitAuthCode(code: String)

    fun prewarmAnonymousDownloadAccess()

    suspend fun logout()

    suspend fun clearOwnedGamesCache()

    suspend fun switchSavedAccount(accountKey: String): Result<Unit>

    suspend fun loadOwnedGames(forceRefresh: Boolean = false): Result<List<OwnedGame>>

    suspend fun loadOwnedGamesSnapshot(): Result<List<OwnedGame>>

    suspend fun loadGameDetails(appId: Int): Result<GameDetails>

    suspend fun loadWorkshopExplorePage(page: Int): Result<WorkshopGamePage>

    suspend fun searchWorkshopGames(query: String): Result<List<WorkshopGameEntry>>

    suspend fun loadWorkshopBrowsePage(
        appId: Int,
        query: WorkshopBrowseQuery,
        forceRefresh: Boolean = false,
    ): Result<WorkshopBrowsePage>

    suspend fun resolveWorkshopItems(publishedFileIds: Collection<Long>): Result<List<WorkshopItem>>

    suspend fun resolveWorkshopItem(publishedFileId: Long): Result<WorkshopItem>
}
