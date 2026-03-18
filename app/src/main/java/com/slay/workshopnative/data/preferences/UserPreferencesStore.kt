package com.slay.workshopnative.data.preferences

import android.net.Uri
import com.slay.workshopnative.core.storage.DEFAULT_DOWNLOAD_FOLDER_NAME
import com.slay.workshopnative.data.model.FavoriteWorkshopGame
import com.slay.workshopnative.data.model.WorkshopGameEntry
import com.slay.workshopnative.data.model.WorkshopBrowseQuery
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
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

const val DEFAULT_DOWNLOAD_CHUNK_CONCURRENCY = 12
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
    val hasAcknowledgedDisclaimer: Boolean = false,
    val hasAcknowledgedUsageBoundary: Boolean = false,
    val autoCheckAppUpdates: Boolean = true,
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
    private val secureSessionStore: SecureSessionStore,
) {
    private companion object {
        val ACCOUNT_NAME = stringPreferencesKey("account_name")
        val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        val CLIENT_ID = longPreferencesKey("client_id")
        val STEAM_ID64 = longPreferencesKey("steam_id64")
        val REMEMBER_SESSION = booleanPreferencesKey("remember_session")
        val SAVED_ACCOUNTS_JSON = stringPreferencesKey("saved_accounts_json")
        val HAS_ACKNOWLEDGED_DISCLAIMER = booleanPreferencesKey("has_acknowledged_disclaimer")
        val HAS_ACKNOWLEDGED_USAGE_BOUNDARY = booleanPreferencesKey("has_acknowledged_usage_boundary")
        val AUTO_CHECK_APP_UPDATES = booleanPreferencesKey("auto_check_app_updates")
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
        val OWNED_GAMES_SNAPSHOT_STEAM_ID64 = longPreferencesKey("owned_games_snapshot_steam_id64")
        val OWNED_GAMES_SNAPSHOT_SAVED_AT_MS = longPreferencesKey("owned_games_snapshot_saved_at_ms")
        val OWNED_GAMES_SNAPSHOT_JSON = stringPreferencesKey("owned_games_snapshot_json")
    }

    private val legacySecretsMigrationMutex = Mutex()

    @Volatile
    private var legacySecretsMigrated = false

    val preferences: Flow<UserPreferences> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { prefs ->
            val activeSessionProfile = secureSessionStore.readActiveSessionProfile()
            val activeRefreshToken = secureSessionStore.readActiveRefreshToken()
                .ifBlank { prefs[REFRESH_TOKEN].orEmpty() }
            val sanitizedDownloadTree = sanitizeDownloadTree(
                uri = prefs[DOWNLOAD_TREE_URI],
                label = prefs[DOWNLOAD_TREE_LABEL],
            )
            val savedAccounts = buildSavedAccounts(
                accountName = activeSessionProfile.accountName,
                refreshToken = activeRefreshToken,
                clientId = activeSessionProfile.clientId,
                steamId64 = activeSessionProfile.steamId64,
                rememberSession = prefs[REMEMBER_SESSION] ?: true,
                persistedAccounts = secureSessionStore.readSavedAccountsMetadata(),
                legacyEncodedAccounts = prefs[SAVED_ACCOUNTS_JSON],
            )
            UserPreferences(
                accountName = activeSessionProfile.accountName,
                refreshToken = activeRefreshToken,
                clientId = activeSessionProfile.clientId,
                steamId64 = activeSessionProfile.steamId64,
                rememberSession = prefs[REMEMBER_SESSION] ?: true,
                savedAccounts = savedAccounts,
                hasAcknowledgedDisclaimer = prefs[HAS_ACKNOWLEDGED_DISCLAIMER] ?: false,
                hasAcknowledgedUsageBoundary = prefs[HAS_ACKNOWLEDGED_USAGE_BOUNDARY] ?: false,
                autoCheckAppUpdates = prefs[AUTO_CHECK_APP_UPDATES] ?: true,
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
            )
        }
        .flowOn(Dispatchers.IO)

    suspend fun snapshot(): UserPreferences {
        migrateLegacySecretsIfNeeded()
        return preferences.first()
    }

    suspend fun saveSession(
        accountName: String,
        refreshToken: String,
        clientId: Long,
        steamId64: Long,
        rememberSession: Boolean,
    ) {
        migrateLegacySecretsIfNeeded()
        val currentAccounts = snapshot().savedAccounts
        val updatedAccounts = if (rememberSession) {
            currentAccounts.upsert(
                SavedSteamAccount(
                    accountName = accountName,
                    refreshToken = refreshToken,
                    clientId = clientId,
                    steamId64 = steamId64,
                    rememberSession = true,
                    lastUsedAtMs = System.currentTimeMillis(),
                ),
            )
        } else {
            currentAccounts.filterNot { it.matches(accountName, steamId64) }
        }

        if (rememberSession) {
            secureSessionStore.writeActiveSessionProfile(
                PersistedActiveSteamSession(
                    accountName = accountName,
                    clientId = clientId,
                    steamId64 = steamId64,
                ),
            )
            secureSessionStore.writeActiveRefreshToken(refreshToken)
            secureSessionStore.writeSavedAccountRefreshToken(
                accountIdentityKey(accountName, steamId64),
                refreshToken,
            )
            secureSessionStore.writeSavedAccountsMetadata(
                sanitizeSavedAccounts(updatedAccounts).map { it.toPersisted() },
            )
        } else {
            secureSessionStore.clearActiveSessionProfile()
            secureSessionStore.clearActiveRefreshToken()
            secureSessionStore.removeSavedAccountRefreshToken(
                accountIdentityKey(accountName, steamId64),
            )
            secureSessionStore.writeSavedAccountsMetadata(
                sanitizeSavedAccounts(updatedAccounts).map { it.toPersisted() },
            )
        }

        dataStore.edit { prefs ->
            prefs[REMEMBER_SESSION] = rememberSession
            prefs.remove(ACCOUNT_NAME)
            prefs.remove(REFRESH_TOKEN)
            prefs.remove(CLIENT_ID)
            prefs.remove(STEAM_ID64)
            prefs.remove(SAVED_ACCOUNTS_JSON)
        }
    }

    suspend fun clearSession() {
        migrateLegacySecretsIfNeeded()
        dataStore.edit { prefs ->
            prefs.remove(ACCOUNT_NAME)
            prefs.remove(REFRESH_TOKEN)
            prefs.remove(CLIENT_ID)
            prefs.remove(STEAM_ID64)
            prefs.remove(OWNED_GAMES_SNAPSHOT_STEAM_ID64)
            prefs.remove(OWNED_GAMES_SNAPSHOT_SAVED_AT_MS)
            prefs.remove(OWNED_GAMES_SNAPSHOT_JSON)
        }
        secureSessionStore.clearActiveSessionProfile()
        secureSessionStore.clearActiveRefreshToken()
    }

    suspend fun clearAllAccountData() {
        migrateLegacySecretsIfNeeded()
        dataStore.edit { prefs ->
            buildSavedAccounts(
                accountName = secureSessionStore.readActiveSessionProfile().accountName,
                refreshToken = secureSessionStore.readActiveRefreshToken()
                    .ifBlank { prefs[REFRESH_TOKEN].orEmpty() },
                clientId = secureSessionStore.readActiveSessionProfile().clientId,
                steamId64 = secureSessionStore.readActiveSessionProfile().steamId64,
                rememberSession = prefs[REMEMBER_SESSION] ?: true,
                persistedAccounts = secureSessionStore.readSavedAccountsMetadata(),
                legacyEncodedAccounts = prefs[SAVED_ACCOUNTS_JSON],
            ).forEach { account ->
                secureSessionStore.removeSavedAccountRefreshToken(account.identityKey())
            }
            prefs.remove(ACCOUNT_NAME)
            prefs.remove(REFRESH_TOKEN)
            prefs.remove(CLIENT_ID)
            prefs.remove(STEAM_ID64)
            prefs.remove(REMEMBER_SESSION)
            prefs.remove(SAVED_ACCOUNTS_JSON)
            prefs.remove(LAST_CONNECTION_PROFILE)
            prefs.remove(LAST_CDN_HOST)
            prefs.remove(LAST_CDN_TRANSPORT_DIRECT)
            prefs.remove(OWNED_GAMES_SNAPSHOT_STEAM_ID64)
            prefs.remove(OWNED_GAMES_SNAPSHOT_SAVED_AT_MS)
            prefs.remove(OWNED_GAMES_SNAPSHOT_JSON)
        }
        secureSessionStore.clearActiveSessionProfile()
        secureSessionStore.clearActiveRefreshToken()
        secureSessionStore.clearSavedAccountsMetadata()
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
        migrateLegacySecretsIfNeeded()
        val savedAccounts = snapshot().savedAccounts
        val target = savedAccounts.firstOrNull { it.stableKey() == accountKey } ?: return null
        if (target.refreshToken.isBlank()) return null
        val selectedAccount = target.copy(lastUsedAtMs = System.currentTimeMillis())
        secureSessionStore.writeActiveSessionProfile(
            PersistedActiveSteamSession(
                accountName = target.accountName,
                clientId = target.clientId,
                steamId64 = target.steamId64,
            ),
        )
        secureSessionStore.writeActiveRefreshToken(target.refreshToken)
        secureSessionStore.writeSavedAccountsMetadata(
            savedAccounts.upsert(selectedAccount).map { it.toPersisted() },
        )
        dataStore.edit { prefs ->
            prefs[REMEMBER_SESSION] = target.rememberSession
            prefs.remove(ACCOUNT_NAME)
            prefs.remove(REFRESH_TOKEN)
            prefs.remove(CLIENT_ID)
            prefs.remove(STEAM_ID64)
            prefs.remove(SAVED_ACCOUNTS_JSON)
        }
        return selectedAccount
    }

    suspend fun removeSavedAccount(accountKey: String) {
        migrateLegacySecretsIfNeeded()
        val currentSnapshot = snapshot()
        val removedAccounts = currentSnapshot.savedAccounts.filter { it.stableKey() == accountKey }
        if (removedAccounts.isEmpty()) return
        val retainedAccounts = currentSnapshot.savedAccounts.filterNot { it.stableKey() == accountKey }
        val activeProfile = secureSessionStore.readActiveSessionProfile()
        val activeAccountKey = SavedSteamAccount(
            accountName = activeProfile.accountName,
            refreshToken = "",
            clientId = activeProfile.clientId,
            steamId64 = activeProfile.steamId64,
            rememberSession = true,
            lastUsedAtMs = 0L,
        ).stableKey()
        val removedActiveAccount = accountKey == activeAccountKey

        secureSessionStore.writeSavedAccountsMetadata(retainedAccounts.map { it.toPersisted() })
        removedAccounts.forEach { account ->
            secureSessionStore.removeSavedAccountRefreshToken(account.identityKey())
        }
        if (removedActiveAccount) {
            secureSessionStore.clearActiveSessionProfile()
            secureSessionStore.clearActiveRefreshToken()
        }

        dataStore.edit { prefs ->
            prefs.remove(SAVED_ACCOUNTS_JSON)
            if (removedActiveAccount) {
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

    suspend fun saveAutoCheckAppUpdates(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[AUTO_CHECK_APP_UPDATES] = enabled
        }
    }

    suspend fun saveDisclaimerAcknowledged(acknowledged: Boolean = true) {
        dataStore.edit { prefs ->
            prefs[HAS_ACKNOWLEDGED_DISCLAIMER] = acknowledged
        }
    }

    suspend fun saveUsageBoundaryAcknowledged(acknowledged: Boolean = true) {
        dataStore.edit { prefs ->
            prefs[HAS_ACKNOWLEDGED_USAGE_BOUNDARY] = acknowledged
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

    suspend fun clearOwnedGamesSnapshot() {
        dataStore.edit { prefs ->
            prefs.remove(OWNED_GAMES_SNAPSHOT_STEAM_ID64)
            prefs.remove(OWNED_GAMES_SNAPSHOT_SAVED_AT_MS)
            prefs.remove(OWNED_GAMES_SNAPSHOT_JSON)
        }
    }

    suspend fun clearFavoriteWorkshopGames() {
        dataStore.edit { prefs ->
            prefs.remove(FAVORITE_WORKSHOP_GAMES_JSON)
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

    private fun decodeSavedAccounts(
        persistedAccounts: List<PersistedSavedSteamAccount>,
        legacyPayload: String?,
    ): List<SavedSteamAccount> {
        if (persistedAccounts.isNotEmpty()) {
            return sanitizeSavedAccounts(
                persistedAccounts.map { persisted ->
                    SavedSteamAccount(
                        accountName = persisted.accountName,
                        refreshToken = secureSessionStore.readSavedAccountRefreshToken(
                            persisted.identityKey(),
                        ),
                        clientId = persisted.clientId,
                        steamId64 = persisted.steamId64,
                        rememberSession = persisted.rememberSession,
                        lastUsedAtMs = persisted.lastUsedAtMs,
                    )
                },
            )
        }
        if (legacyPayload.isNullOrBlank()) return emptyList()
        if (isLegacySavedAccountsPayload(legacyPayload)) {
            return decodeLegacySavedAccounts(legacyPayload)
        }
        decodePersistedSavedAccounts(legacyPayload)?.let { accounts ->
            return sanitizeSavedAccounts(
                accounts.map { persisted ->
                    SavedSteamAccount(
                        accountName = persisted.accountName,
                        refreshToken = secureSessionStore.readSavedAccountRefreshToken(
                            persisted.identityKey(),
                        ),
                        clientId = persisted.clientId,
                        steamId64 = persisted.steamId64,
                        rememberSession = persisted.rememberSession,
                        lastUsedAtMs = persisted.lastUsedAtMs,
                    )
                },
            )
        }
        return decodeLegacySavedAccounts(legacyPayload)
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
        persistedAccounts: List<PersistedSavedSteamAccount>,
        legacyEncodedAccounts: String?,
    ): List<SavedSteamAccount> {
        val storedAccounts = decodeSavedAccounts(
            persistedAccounts = persistedAccounts,
            legacyPayload = legacyEncodedAccounts,
        )
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

    private fun accountIdentityKey(
        accountName: String,
        steamId64: Long,
    ): String {
        return SavedSteamAccount(
            accountName = accountName,
            refreshToken = "",
            clientId = 0L,
            steamId64 = steamId64,
            rememberSession = true,
            lastUsedAtMs = 0L,
        ).identityKey()
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

    private fun SavedSteamAccount.toPersisted(): PersistedSavedSteamAccount {
        return PersistedSavedSteamAccount(
            accountName = accountName,
            clientId = clientId,
            steamId64 = steamId64,
            rememberSession = rememberSession,
            lastUsedAtMs = lastUsedAtMs,
        )
    }

    private fun PersistedSavedSteamAccount.identityKey(): String {
        return accountIdentityKey(
            accountName = accountName,
            steamId64 = steamId64,
        )
    }

    private fun decodePersistedSavedAccounts(payload: String): List<PersistedSavedSteamAccount>? {
        return runCatching {
            json.decodeFromString<List<PersistedSavedSteamAccount>>(payload)
        }.getOrNull()
    }

    private fun isLegacySavedAccountsPayload(payload: String): Boolean {
        return "\"refreshToken\"" in payload
    }

    private fun decodeLegacySavedAccounts(payload: String?): List<SavedSteamAccount> {
        if (payload.isNullOrBlank()) return emptyList()
        return sanitizeSavedAccounts(
            runCatching {
                json.decodeFromString<List<SavedSteamAccount>>(payload)
            }.getOrDefault(emptyList()),
        )
    }

    private suspend fun migrateLegacySecretsIfNeeded() {
        if (legacySecretsMigrated) return
        legacySecretsMigrationMutex.withLock {
            if (legacySecretsMigrated) return@withLock
            dataStore.edit { prefs ->
                val legacyRefreshToken = prefs[REFRESH_TOKEN].orEmpty()
                val secureActiveProfile = secureSessionStore.readActiveSessionProfile()
                val secureSavedAccounts = secureSessionStore.readSavedAccountsMetadata()
                val legacySavedAccounts = decodeSavedAccounts(
                    persistedAccounts = secureSavedAccounts,
                    legacyPayload = prefs[SAVED_ACCOUNTS_JSON],
                )
                val mergedAccounts = legacySavedAccounts.toMutableList()
                val legacyActiveProfile = PersistedActiveSteamSession(
                    accountName = prefs[ACCOUNT_NAME].orEmpty(),
                    clientId = prefs[CLIENT_ID] ?: 0L,
                    steamId64 = prefs[STEAM_ID64] ?: 0L,
                )

                if (
                    legacyActiveProfile.accountName.isNotBlank() &&
                    (prefs[REMEMBER_SESSION] ?: true) &&
                    legacyRefreshToken.isNotBlank()
                ) {
                    mergedAccounts += SavedSteamAccount(
                        accountName = legacyActiveProfile.accountName,
                        refreshToken = legacyRefreshToken,
                        clientId = legacyActiveProfile.clientId,
                        steamId64 = legacyActiveProfile.steamId64,
                        rememberSession = true,
                        lastUsedAtMs = System.currentTimeMillis(),
                    )
                }

                if (secureActiveProfile.isBlankProfile()) {
                    if (
                        (prefs[REMEMBER_SESSION] ?: true) &&
                        legacyRefreshToken.isNotBlank() &&
                        !legacyActiveProfile.isBlankProfile()
                    ) {
                        secureSessionStore.writeActiveSessionProfile(legacyActiveProfile)
                    } else {
                        secureSessionStore.clearActiveSessionProfile()
                    }
                }

                if (legacyRefreshToken.isNotBlank()) {
                    secureSessionStore.writeActiveRefreshToken(legacyRefreshToken)
                }

                if (mergedAccounts.isNotEmpty()) {
                    val sanitizedAccounts = sanitizeSavedAccounts(mergedAccounts)
                    sanitizedAccounts.forEach { account ->
                        if (account.refreshToken.isNotBlank()) {
                            secureSessionStore.writeSavedAccountRefreshToken(
                                account.identityKey(),
                                account.refreshToken,
                            )
                        }
                    }
                    secureSessionStore.writeSavedAccountsMetadata(
                        sanitizedAccounts.map { it.toPersisted() },
                    )
                } else if (secureSavedAccounts.isEmpty()) {
                    secureSessionStore.clearSavedAccountsMetadata()
                }
                prefs.remove(ACCOUNT_NAME)
                prefs.remove(REFRESH_TOKEN)
                prefs.remove(CLIENT_ID)
                prefs.remove(STEAM_ID64)
                prefs.remove(SAVED_ACCOUNTS_JSON)
            }
            legacySecretsMigrated = true
        }
    }
}

private fun PersistedActiveSteamSession.isBlankProfile(): Boolean {
    return accountName.isBlank() && clientId <= 0L && steamId64 <= 0L
}
