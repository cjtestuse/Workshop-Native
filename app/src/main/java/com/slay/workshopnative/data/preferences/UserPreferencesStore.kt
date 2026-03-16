package com.slay.workshopnative.data.preferences

import android.net.Uri
import com.slay.workshopnative.core.storage.DEFAULT_DOWNLOAD_FOLDER_NAME
import com.slay.workshopnative.data.model.FavoriteWorkshopGame
import com.slay.workshopnative.data.model.WorkshopGameEntry
import com.slay.workshopnative.data.model.WorkshopBrowseQuery
import com.slay.workshopnative.update.AppUpdateSource
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

const val DEFAULT_DOWNLOAD_CHUNK_CONCURRENCY = 8
const val MIN_DOWNLOAD_CHUNK_CONCURRENCY = 1
const val MAX_DOWNLOAD_CHUNK_CONCURRENCY = 12
val DOWNLOAD_CHUNK_CONCURRENCY_OPTIONS = listOf(1, 2, 4, 6, 8, 12)

enum class CdnTransportPreference {
    Auto,
    PreferSystem,
    PreferDirect,
}

enum class CdnPoolPreference {
    Auto,
    TrustedOnly,
    PreferGoogle2,
    PreferFastly,
    PreferAkamai,
}

fun normalizeDownloadChunkConcurrency(value: Int): Int {
    return value.coerceIn(MIN_DOWNLOAD_CHUNK_CONCURRENCY, MAX_DOWNLOAD_CHUNK_CONCURRENCY)
}

@Serializable
data class SavedSteamAccount(
    val accountName: String,
    val refreshToken: String,
    val clientId: Long,
    val steamId64: Long,
    val rememberSession: Boolean,
    val lastUsedAtMs: Long,
) {
    fun stableKey(): String {
        return if (steamId64 > 0L) {
            "steam:$steamId64"
        } else {
            "name:${accountName.trim().lowercase()}"
        }
    }
}

data class UserPreferences(
    val accountName: String = "",
    val refreshToken: String = "",
    val clientId: Long = 0,
    val steamId64: Long = 0,
    val rememberSession: Boolean = true,
    val savedAccounts: List<SavedSteamAccount> = emptyList(),
    val defaultGuestMode: Boolean = true,
    val lastConnectionProfileLabel: String? = null,
    val lastCdnHost: String? = null,
    val lastCdnTransportDirect: Boolean? = null,
    val cdnTransportPreference: CdnTransportPreference = CdnTransportPreference.Auto,
    val cdnPoolPreference: CdnPoolPreference = CdnPoolPreference.Auto,
    val downloadFolderName: String = DEFAULT_DOWNLOAD_FOLDER_NAME,
    val downloadTreeUri: String? = null,
    val downloadTreeLabel: String? = null,
    val downloadChunkConcurrency: Int = DEFAULT_DOWNLOAD_CHUNK_CONCURRENCY,
    val preferAnonymousDownloads: Boolean = true,
    val allowAuthenticatedDownloadFallback: Boolean = true,
    val workshopPageSize: Int = WorkshopBrowseQuery.DEFAULT_PAGE_SIZE,
    val workshopAutoResolveVisibleItems: Boolean = true,
    val favoriteWorkshopGames: List<FavoriteWorkshopGame> = emptyList(),
    val preferredUpdateSource: AppUpdateSource = AppUpdateSource.DEFAULT,
)

data class OwnedGamesSnapshot(
    val steamId64: Long = 0,
    val savedAtMs: Long = 0,
    val payloadJson: String = "",
)

@Singleton
class UserPreferencesStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val json: Json,
) {
    private companion object {
        val ACCOUNT_NAME = stringPreferencesKey("account_name")
        val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        val CLIENT_ID = longPreferencesKey("client_id")
        val STEAM_ID64 = longPreferencesKey("steam_id64")
        val REMEMBER_SESSION = booleanPreferencesKey("remember_session")
        val SAVED_ACCOUNTS_JSON = stringPreferencesKey("saved_accounts_json")
        val DEFAULT_GUEST_MODE = booleanPreferencesKey("default_guest_mode")
        val LAST_CONNECTION_PROFILE = stringPreferencesKey("last_connection_profile")
        val LAST_CDN_HOST = stringPreferencesKey("last_cdn_host")
        val LAST_CDN_TRANSPORT_DIRECT = booleanPreferencesKey("last_cdn_transport_direct")
        val CDN_TRANSPORT_PREFERENCE = stringPreferencesKey("cdn_transport_preference")
        val CDN_POOL_PREFERENCE = stringPreferencesKey("cdn_pool_preference")
        val DOWNLOAD_FOLDER_NAME = stringPreferencesKey("download_folder_name")
        val DOWNLOAD_TREE_URI = stringPreferencesKey("download_tree_uri")
        val DOWNLOAD_TREE_LABEL = stringPreferencesKey("download_tree_label")
        val DOWNLOAD_CHUNK_CONCURRENCY = intPreferencesKey("download_chunk_concurrency")
        val PREFER_ANONYMOUS_DOWNLOADS = booleanPreferencesKey("prefer_anonymous_downloads")
        val ALLOW_AUTHENTICATED_DOWNLOAD_FALLBACK = booleanPreferencesKey("allow_authenticated_download_fallback")
        val WORKSHOP_PAGE_SIZE = intPreferencesKey("workshop_page_size")
        val WORKSHOP_AUTO_RESOLVE_VISIBLE_ITEMS = booleanPreferencesKey("workshop_auto_resolve_visible_items")
        val FAVORITE_WORKSHOP_GAMES_JSON = stringPreferencesKey("favorite_workshop_games_json")
        val PREFERRED_UPDATE_SOURCE = stringPreferencesKey("preferred_update_source")
        val OWNED_GAMES_SNAPSHOT_STEAM_ID64 = longPreferencesKey("owned_games_snapshot_steam_id64")
        val OWNED_GAMES_SNAPSHOT_SAVED_AT_MS = longPreferencesKey("owned_games_snapshot_saved_at_ms")
        val OWNED_GAMES_SNAPSHOT_JSON = stringPreferencesKey("owned_games_snapshot_json")
    }

    val preferences: Flow<UserPreferences> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { prefs ->
            val sanitizedDownloadTree = sanitizeDownloadTree(
                uri = prefs[DOWNLOAD_TREE_URI],
                label = prefs[DOWNLOAD_TREE_LABEL],
            )
            val savedAccounts = buildSavedAccounts(
                accountName = prefs[ACCOUNT_NAME].orEmpty(),
                refreshToken = prefs[REFRESH_TOKEN].orEmpty(),
                clientId = prefs[CLIENT_ID] ?: 0,
                steamId64 = prefs[STEAM_ID64] ?: 0,
                rememberSession = prefs[REMEMBER_SESSION] ?: true,
                encodedAccounts = prefs[SAVED_ACCOUNTS_JSON],
            )
            UserPreferences(
                accountName = prefs[ACCOUNT_NAME].orEmpty(),
                refreshToken = prefs[REFRESH_TOKEN].orEmpty(),
                clientId = prefs[CLIENT_ID] ?: 0,
                steamId64 = prefs[STEAM_ID64] ?: 0,
                rememberSession = prefs[REMEMBER_SESSION] ?: true,
                savedAccounts = savedAccounts,
                defaultGuestMode = prefs[DEFAULT_GUEST_MODE] ?: true,
                lastConnectionProfileLabel = prefs[LAST_CONNECTION_PROFILE],
                lastCdnHost = prefs[LAST_CDN_HOST],
                lastCdnTransportDirect = prefs[LAST_CDN_TRANSPORT_DIRECT],
                cdnTransportPreference = prefs[CDN_TRANSPORT_PREFERENCE]
                    ?.let { value -> runCatching { CdnTransportPreference.valueOf(value) }.getOrNull() }
                    ?: CdnTransportPreference.Auto,
                cdnPoolPreference = prefs[CDN_POOL_PREFERENCE]
                    ?.let { value -> runCatching { CdnPoolPreference.valueOf(value) }.getOrNull() }
                    ?: CdnPoolPreference.Auto,
                downloadFolderName = prefs[DOWNLOAD_FOLDER_NAME] ?: DEFAULT_DOWNLOAD_FOLDER_NAME,
                downloadTreeUri = sanitizedDownloadTree.first,
                downloadTreeLabel = sanitizedDownloadTree.second,
                downloadChunkConcurrency = normalizeDownloadChunkConcurrency(
                    prefs[DOWNLOAD_CHUNK_CONCURRENCY] ?: DEFAULT_DOWNLOAD_CHUNK_CONCURRENCY,
                ),
                preferAnonymousDownloads = prefs[PREFER_ANONYMOUS_DOWNLOADS] ?: true,
                allowAuthenticatedDownloadFallback = prefs[ALLOW_AUTHENTICATED_DOWNLOAD_FALLBACK] ?: true,
                workshopPageSize = WorkshopBrowseQuery.normalizePageSize(
                    prefs[WORKSHOP_PAGE_SIZE] ?: WorkshopBrowseQuery.DEFAULT_PAGE_SIZE,
                ),
                workshopAutoResolveVisibleItems = prefs[WORKSHOP_AUTO_RESOLVE_VISIBLE_ITEMS] ?: true,
                favoriteWorkshopGames = decodeFavoriteWorkshopGames(prefs[FAVORITE_WORKSHOP_GAMES_JSON]),
                preferredUpdateSource = AppUpdateSource.normalizePreferredSource(prefs[PREFERRED_UPDATE_SOURCE]),
            )
        }

    suspend fun snapshot(): UserPreferences = preferences.first()

    suspend fun saveSession(
        accountName: String,
        refreshToken: String,
        clientId: Long,
        steamId64: Long,
        rememberSession: Boolean,
    ) {
        dataStore.edit { prefs ->
            val now = System.currentTimeMillis()
            val currentAccounts = decodeSavedAccounts(prefs[SAVED_ACCOUNTS_JSON])
            prefs[ACCOUNT_NAME] = accountName
            prefs[CLIENT_ID] = clientId
            prefs[STEAM_ID64] = steamId64
            prefs[REMEMBER_SESSION] = rememberSession
            if (rememberSession) {
                prefs[REFRESH_TOKEN] = refreshToken
                val updatedAccount = SavedSteamAccount(
                    accountName = accountName,
                    refreshToken = refreshToken,
                    clientId = clientId,
                    steamId64 = steamId64,
                    rememberSession = true,
                    lastUsedAtMs = now,
                )
                prefs[SAVED_ACCOUNTS_JSON] = encodeSavedAccounts(
                    currentAccounts.upsert(updatedAccount),
                )
            } else {
                prefs.remove(REFRESH_TOKEN)
                prefs[SAVED_ACCOUNTS_JSON] = encodeSavedAccounts(
                    currentAccounts.filterNot { it.matches(accountName, steamId64) },
                )
            }
        }
    }

    suspend fun clearSession() {
        dataStore.edit { prefs ->
            prefs.remove(ACCOUNT_NAME)
            prefs.remove(REFRESH_TOKEN)
            prefs.remove(CLIENT_ID)
            prefs.remove(STEAM_ID64)
            prefs.remove(OWNED_GAMES_SNAPSHOT_STEAM_ID64)
            prefs.remove(OWNED_GAMES_SNAPSHOT_SAVED_AT_MS)
            prefs.remove(OWNED_GAMES_SNAPSHOT_JSON)
        }
    }

    suspend fun saveLastAccountName(accountName: String) {
        dataStore.edit { prefs ->
            prefs[ACCOUNT_NAME] = accountName
        }
    }

    suspend fun saveLastConnectionProfile(label: String) {
        dataStore.edit { prefs ->
            prefs[LAST_CONNECTION_PROFILE] = label
        }
    }

    suspend fun saveLastCdnSelection(
        host: String,
        forceDirect: Boolean,
    ) {
        dataStore.edit { prefs ->
            prefs[LAST_CDN_HOST] = host
            prefs[LAST_CDN_TRANSPORT_DIRECT] = forceDirect
        }
    }

    suspend fun saveCdnTransportPreference(preference: CdnTransportPreference) {
        dataStore.edit { prefs ->
            prefs[CDN_TRANSPORT_PREFERENCE] = preference.name
        }
    }

    suspend fun saveCdnPoolPreference(preference: CdnPoolPreference) {
        dataStore.edit { prefs ->
            prefs[CDN_POOL_PREFERENCE] = preference.name
        }
    }

    suspend fun activateSavedAccount(accountKey: String): SavedSteamAccount? {
        var selectedAccount: SavedSteamAccount? = null
        dataStore.edit { prefs ->
            val savedAccounts = buildSavedAccounts(
                accountName = prefs[ACCOUNT_NAME].orEmpty(),
                refreshToken = prefs[REFRESH_TOKEN].orEmpty(),
                clientId = prefs[CLIENT_ID] ?: 0,
                steamId64 = prefs[STEAM_ID64] ?: 0,
                rememberSession = prefs[REMEMBER_SESSION] ?: true,
                encodedAccounts = prefs[SAVED_ACCOUNTS_JSON],
            )
            val target = savedAccounts.firstOrNull { it.stableKey() == accountKey } ?: return@edit
            selectedAccount = target.copy(lastUsedAtMs = System.currentTimeMillis())
            prefs[ACCOUNT_NAME] = target.accountName
            prefs[REFRESH_TOKEN] = target.refreshToken
            prefs[CLIENT_ID] = target.clientId
            prefs[STEAM_ID64] = target.steamId64
            prefs[REMEMBER_SESSION] = target.rememberSession
            prefs[SAVED_ACCOUNTS_JSON] = encodeSavedAccounts(savedAccounts.upsert(selectedAccount!!))
        }
        return selectedAccount
    }

    suspend fun removeSavedAccount(accountKey: String) {
        dataStore.edit { prefs ->
            val savedAccounts = buildSavedAccounts(
                accountName = prefs[ACCOUNT_NAME].orEmpty(),
                refreshToken = prefs[REFRESH_TOKEN].orEmpty(),
                clientId = prefs[CLIENT_ID] ?: 0,
                steamId64 = prefs[STEAM_ID64] ?: 0,
                rememberSession = prefs[REMEMBER_SESSION] ?: true,
                encodedAccounts = prefs[SAVED_ACCOUNTS_JSON],
            )
            val retainedAccounts = savedAccounts.filterNot { it.stableKey() == accountKey }
            prefs[SAVED_ACCOUNTS_JSON] = encodeSavedAccounts(retainedAccounts)
            val activeAccountKey = SavedSteamAccount(
                accountName = prefs[ACCOUNT_NAME].orEmpty(),
                refreshToken = prefs[REFRESH_TOKEN].orEmpty(),
                clientId = prefs[CLIENT_ID] ?: 0L,
                steamId64 = prefs[STEAM_ID64] ?: 0L,
                rememberSession = prefs[REMEMBER_SESSION] ?: true,
                lastUsedAtMs = 0L,
            ).stableKey()
            if (accountKey == activeAccountKey) {
                prefs.remove(ACCOUNT_NAME)
                prefs.remove(REFRESH_TOKEN)
                prefs.remove(CLIENT_ID)
                prefs.remove(STEAM_ID64)
                prefs.remove(OWNED_GAMES_SNAPSHOT_STEAM_ID64)
                prefs.remove(OWNED_GAMES_SNAPSHOT_SAVED_AT_MS)
                prefs.remove(OWNED_GAMES_SNAPSHOT_JSON)
            }
        }
    }

    suspend fun saveDefaultGuestMode(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[DEFAULT_GUEST_MODE] = enabled
        }
    }

    suspend fun saveDownloadTree(uri: String, label: String) {
        dataStore.edit { prefs ->
            prefs[DOWNLOAD_TREE_URI] = uri
            prefs[DOWNLOAD_TREE_LABEL] = label
        }
    }

    suspend fun saveDownloadFolderName(folderName: String) {
        dataStore.edit { prefs ->
            prefs[DOWNLOAD_FOLDER_NAME] = folderName
        }
    }

    suspend fun clearDownloadTree() {
        dataStore.edit { prefs ->
            prefs.remove(DOWNLOAD_TREE_URI)
            prefs.remove(DOWNLOAD_TREE_LABEL)
        }
    }

    suspend fun saveDownloadChunkConcurrency(concurrency: Int) {
        dataStore.edit { prefs ->
            prefs[DOWNLOAD_CHUNK_CONCURRENCY] = normalizeDownloadChunkConcurrency(concurrency)
        }
    }

    suspend fun savePreferAnonymousDownloads(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[PREFER_ANONYMOUS_DOWNLOADS] = enabled
        }
    }

    suspend fun saveAllowAuthenticatedDownloadFallback(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[ALLOW_AUTHENTICATED_DOWNLOAD_FALLBACK] = enabled
        }
    }

    suspend fun saveWorkshopPageSize(pageSize: Int) {
        dataStore.edit { prefs ->
            prefs[WORKSHOP_PAGE_SIZE] = WorkshopBrowseQuery.normalizePageSize(pageSize)
        }
    }

    suspend fun saveWorkshopAutoResolveVisibleItems(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[WORKSHOP_AUTO_RESOLVE_VISIBLE_ITEMS] = enabled
        }
    }

    suspend fun savePreferredUpdateSource(source: AppUpdateSource) {
        dataStore.edit { prefs ->
            prefs[PREFERRED_UPDATE_SOURCE] = source.id
        }
    }

    suspend fun addFavoriteWorkshopGame(game: WorkshopGameEntry) {
        if (game.appId <= 0) return
        dataStore.edit { prefs ->
            val now = System.currentTimeMillis()
            val current = decodeFavoriteWorkshopGames(prefs[FAVORITE_WORKSHOP_GAMES_JSON])
            val updated = current.upsert(
                FavoriteWorkshopGame(
                    appId = game.appId,
                    name = game.name,
                    capsuleUrl = game.capsuleUrl,
                    previewUrl = game.previewUrl,
                    workshopItemCount = game.workshopItemCount,
                    addedAtMs = now,
                    lastOpenedAtMs = now,
                ),
            )
            prefs[FAVORITE_WORKSHOP_GAMES_JSON] = encodeFavoriteWorkshopGames(updated)
        }
    }

    suspend fun removeFavoriteWorkshopGame(appId: Int) {
        if (appId <= 0) return
        dataStore.edit { prefs ->
            val current = decodeFavoriteWorkshopGames(prefs[FAVORITE_WORKSHOP_GAMES_JSON])
            prefs[FAVORITE_WORKSHOP_GAMES_JSON] = encodeFavoriteWorkshopGames(
                current.filterNot { it.appId == appId },
            )
        }
    }

    suspend fun markFavoriteWorkshopGameOpened(appId: Int) {
        if (appId <= 0) return
        dataStore.edit { prefs ->
            val current = decodeFavoriteWorkshopGames(prefs[FAVORITE_WORKSHOP_GAMES_JSON])
            if (current.none { it.appId == appId }) return@edit
            val now = System.currentTimeMillis()
            prefs[FAVORITE_WORKSHOP_GAMES_JSON] = encodeFavoriteWorkshopGames(
                current.map { favorite ->
                    if (favorite.appId == appId) {
                        favorite.copy(lastOpenedAtMs = now)
                    } else {
                        favorite
                    }
                },
            )
        }
    }

    suspend fun isFavoriteWorkshopGame(appId: Int): Boolean {
        return preferences.first().favoriteWorkshopGames.any { it.appId == appId }
    }

    suspend fun saveOwnedGamesSnapshot(
        steamId64: Long,
        payloadJson: String,
        savedAtMs: Long = System.currentTimeMillis(),
    ) {
        dataStore.edit { prefs ->
            prefs[OWNED_GAMES_SNAPSHOT_STEAM_ID64] = steamId64
            prefs[OWNED_GAMES_SNAPSHOT_SAVED_AT_MS] = savedAtMs
            prefs[OWNED_GAMES_SNAPSHOT_JSON] = payloadJson
        }
    }

    suspend fun loadOwnedGamesSnapshot(): OwnedGamesSnapshot {
        val prefs = dataStore.data.first()
        return OwnedGamesSnapshot(
            steamId64 = prefs[OWNED_GAMES_SNAPSHOT_STEAM_ID64] ?: 0,
            savedAtMs = prefs[OWNED_GAMES_SNAPSHOT_SAVED_AT_MS] ?: 0,
            payloadJson = prefs[OWNED_GAMES_SNAPSHOT_JSON].orEmpty(),
        )
    }

    private fun sanitizeDownloadTree(
        uri: String?,
        label: String?,
    ): Pair<String?, String?> {
        if (uri.isNullOrBlank()) return null to null
        val normalized = runCatching { Uri.decode(uri) }.getOrDefault(uri)
        val lowerCased = normalized.lowercase()
        val isUnsupportedAndroidDataPath =
            "android/data" in lowerCased || "android%2fdata" in lowerCased
        return if (isUnsupportedAndroidDataPath) {
            null to null
        } else {
            uri to label
        }
    }

    private fun decodeSavedAccounts(payload: String?): List<SavedSteamAccount> {
        if (payload.isNullOrBlank()) return emptyList()
        return sanitizeSavedAccounts(runCatching {
            json.decodeFromString<List<SavedSteamAccount>>(payload)
        }.getOrDefault(emptyList()))
    }

    private fun decodeFavoriteWorkshopGames(payload: String?): List<FavoriteWorkshopGame> {
        if (payload.isNullOrBlank()) return emptyList()
        return sanitizeFavoriteWorkshopGames(
            runCatching {
                json.decodeFromString<List<FavoriteWorkshopGame>>(payload)
            }.getOrDefault(emptyList()),
        )
    }

    private fun buildSavedAccounts(
        accountName: String,
        refreshToken: String,
        clientId: Long,
        steamId64: Long,
        rememberSession: Boolean,
        encodedAccounts: String?,
    ): List<SavedSteamAccount> {
        val storedAccounts = decodeSavedAccounts(encodedAccounts)
        if (accountName.isBlank() || refreshToken.isBlank() || !rememberSession) {
            return storedAccounts
        }
        val legacyAccount = SavedSteamAccount(
            accountName = accountName,
            refreshToken = refreshToken,
            clientId = clientId,
            steamId64 = steamId64,
            rememberSession = true,
            lastUsedAtMs = System.currentTimeMillis(),
        )
        return sanitizeSavedAccounts(storedAccounts.upsert(legacyAccount))
    }

    private fun encodeSavedAccounts(accounts: List<SavedSteamAccount>): String {
        return json.encodeToString(
            sanitizeSavedAccounts(accounts),
        )
    }

    private fun encodeFavoriteWorkshopGames(games: List<FavoriteWorkshopGame>): String {
        return json.encodeToString(
            sanitizeFavoriteWorkshopGames(games),
        )
    }

    private fun List<SavedSteamAccount>.upsert(account: SavedSteamAccount): List<SavedSteamAccount> {
        return filterNot { it.identityKey() == account.identityKey() } + account
    }

    private fun List<FavoriteWorkshopGame>.upsert(game: FavoriteWorkshopGame): List<FavoriteWorkshopGame> {
        val existing = firstOrNull { it.appId == game.appId }
        val merged = if (existing == null) {
            game
        } else {
            game.copy(
                addedAtMs = existing.addedAtMs.takeIf { it > 0L } ?: game.addedAtMs,
                lastOpenedAtMs = maxOf(existing.lastOpenedAtMs, game.lastOpenedAtMs),
                capsuleUrl = game.capsuleUrl ?: existing.capsuleUrl,
                previewUrl = game.previewUrl ?: existing.previewUrl,
                workshopItemCount = game.workshopItemCount ?: existing.workshopItemCount,
            )
        }
        return filterNot { it.appId == game.appId } + merged
    }

    private fun SavedSteamAccount.matches(accountName: String, steamId64: Long): Boolean {
        return steamId64 > 0L && this.steamId64 == steamId64 ||
            accountName.isNotBlank() && this.accountName.equals(accountName, ignoreCase = true)
    }

    private fun sanitizeSavedAccounts(accounts: List<SavedSteamAccount>): List<SavedSteamAccount> {
        return accounts
            .groupBy { it.identityKey() }
            .values
            .map { duplicates -> duplicates.reduce { acc, account -> acc.mergeWith(account) } }
            .sortedByDescending(SavedSteamAccount::lastUsedAtMs)
    }

    private fun sanitizeFavoriteWorkshopGames(games: List<FavoriteWorkshopGame>): List<FavoriteWorkshopGame> {
        return games
            .filter { it.appId > 0 && it.name.isNotBlank() }
            .groupBy(FavoriteWorkshopGame::appId)
            .values
            .map { duplicates ->
                duplicates.reduce { acc, game ->
                    acc.copy(
                        name = acc.name.ifBlank { game.name },
                        capsuleUrl = acc.capsuleUrl ?: game.capsuleUrl,
                        previewUrl = acc.previewUrl ?: game.previewUrl,
                        workshopItemCount = acc.workshopItemCount ?: game.workshopItemCount,
                        addedAtMs = listOf(acc.addedAtMs, game.addedAtMs).filter { it > 0L }.minOrNull() ?: 0L,
                        lastOpenedAtMs = maxOf(acc.lastOpenedAtMs, game.lastOpenedAtMs),
                    )
                }
            }
            .sortedWith(
                compareByDescending<FavoriteWorkshopGame> { it.lastOpenedAtMs }
                    .thenByDescending { it.addedAtMs },
            )
    }

    private fun SavedSteamAccount.identityKey(): String {
        val normalizedAccountName = accountName.trim().lowercase()
        return if (normalizedAccountName.isNotEmpty()) {
            "name:$normalizedAccountName"
        } else if (steamId64 > 0L) {
            "steam:$steamId64"
        } else {
            stableKey()
        }
    }

    private fun SavedSteamAccount.mergeWith(other: SavedSteamAccount): SavedSteamAccount {
        val newer = if (lastUsedAtMs >= other.lastUsedAtMs) this else other
        val older = if (newer === this) other else this
        return newer.copy(
            accountName = newer.accountName.ifBlank { older.accountName },
            refreshToken = newer.refreshToken.ifBlank { older.refreshToken },
            clientId = newer.clientId.takeIf { it > 0L } ?: older.clientId,
            steamId64 = newer.steamId64.takeIf { it > 0L } ?: older.steamId64,
            rememberSession = newer.rememberSession || older.rememberSession,
            lastUsedAtMs = maxOf(lastUsedAtMs, other.lastUsedAtMs),
        )
    }
}
