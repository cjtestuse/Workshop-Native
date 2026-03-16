package com.slay.workshopnative.data.repository

import com.slay.workshopnative.data.model.FavoriteWorkshopGame
import com.slay.workshopnative.data.model.WorkshopGameEntry
import com.slay.workshopnative.data.preferences.UserPreferencesStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class WorkshopFavoritesRepository @Inject constructor(
    private val preferencesStore: UserPreferencesStore,
) {
    val favorites: Flow<List<FavoriteWorkshopGame>> = preferencesStore.preferences
        .map { it.favoriteWorkshopGames }

    suspend fun toggleFavorite(game: WorkshopGameEntry) {
        if (preferencesStore.isFavoriteWorkshopGame(game.appId)) {
            preferencesStore.removeFavoriteWorkshopGame(game.appId)
        } else {
            preferencesStore.addFavoriteWorkshopGame(game)
        }
    }

    suspend fun addFavorite(game: WorkshopGameEntry) {
        preferencesStore.addFavoriteWorkshopGame(game)
    }

    suspend fun removeFavorite(appId: Int) {
        preferencesStore.removeFavoriteWorkshopGame(appId)
    }

    suspend fun markOpened(appId: Int) {
        preferencesStore.markFavoriteWorkshopGameOpened(appId)
    }
}
