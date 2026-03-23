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
const val COMPATIBILITY_DOWNLOAD_CHUNK_CONCURRENCY = 4
const val PRIMORDIAL_DOWNLOAD_CHUNK_CONCURRENCY = 24
const val MIN_DOWNLOAD_CHUNK_CONCURRENCY = 1
const val MAX_DOWNLOAD_CHUNK_CONCURRENCY = 24
val DOWNLOAD_CHUNK_CONCURRENCY_OPTIONS = listOf(1, 2, 4, 6, 8, 12, 16, 24)

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

enum class DownloadPerformanceMode {
    Auto,
    Compatibility,
    Primordial,
}

fun normalizeDownloadChunkConcurrency(value: Int): Int {
    return value.coerceIn(MIN_DOWNLOAD_CHUNK_CONCURRENCY, MAX_DOWNLOAD_CHUNK_CONCURRENCY)
}

fun requestedDownloadChunkConcurrency(mode: DownloadPerformanceMode): Int {
    return when (mode) {
        DownloadPerformanceMode.Auto -> DEFAULT_DOWNLOAD_CHUNK_CONCURRENCY
        DownloadPerformanceMode.Compatibility -> COMPATIBILITY_DOWNLOAD_CHUNK_CONCURRENCY
        DownloadPerformanceMode.Primordial -> PRIMORDIAL_DOWNLOAD_CHUNK_CONCURRENCY
    }
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
    val isLoginFeatureEnabled: Boolean = false,
    val isLoggedInDownloadEnabled: Boolean = false,
    val isOwnedGamesDisplayEnabled: Boolean = false,
    val isSubscriptionDisplayEnabled: Boolean = false,
    val hasAcknowledgedDisclaimer: Boolean = false,
    val hasAcknowledgedUsageBoundary: Boolean = false,
    val autoCheckAppUpdates: Boolean = false,
    val autoCheckDownloadedModUpdatesOnLaunch: Boolean = false,
    val lastDownloadedModUpdatesLaunchCheckAtMs: Long = 0L,
    val defaultGuestMode: Boolean = true,
    val lastConnectionProfileLabel: String? = null,
    val lastCdnHost: String? = null,
    val lastCdnTransportDirect: Boolean? = null,
    val cdnTransportPreference: CdnTransportPreference = CdnTransportPreference.Auto,
    val cdnPoolPreference: CdnPoolPreference = CdnPoolPreference.Auto,
    val downloadFolderName: String = DEFAULT_DOWNLOAD_FOLDER_NAME,
    val downloadTreeUri: String? = null,
    val downloadTreeLabel: String? = null,
    val downloadPerformanceMode: DownloadPerformanceMode = DownloadPerformanceMode.Auto,
    val downloadChunkConcurrency: Int = DEFAULT_DOWNLOAD_CHUNK_CONCURRENCY,
    val primordialReducedMemoryProtectionEnabled: Boolean = false,
    val primordialAuthenticatedAggressiveCdnEnabled: Boolean = false,
    val preferAnonymousDownloads: Boolean = true,
    val allowAuthenticatedDownloadFallback: Boolean = true,
    val workshopPageSize: Int = WorkshopBrowseQuery.DEFAULT_PAGE_SIZE,
    val workshopAutoResolveVisibleItems: Boolean = false,
    val animatedWorkshopPreviewEnabled: Boolean = false,
    val terrariaArchivePostProcessorEnabled: Boolean = false,
    val terrariaArchiveKeepOriginal: Boolean = false,
    val wallpaperEnginePkgExtractEnabled: Boolean = false,
    val wallpaperEnginePkgKeepOriginal: Boolean = false,
    val wallpaperEngineTexConversionEnabled: Boolean = false,
    val wallpaperEngineKeepConvertedTexOriginal: Boolean = true,
    val translationProvider: TranslationProvider = DEFAULT_TRANSLATION_PROVIDER,
    val translationAzureEndpoint: String = DEFAULT_AZURE_TRANSLATOR_ENDPOINT,
    val translationAzureRegion: String = "",
    val translationAzureApiKey: String = "",
    val translationGoogleApiKey: String = "",
    val favoriteWorkshopGames: List<FavoriteWorkshopGame> = emptyList(),
    val themeMode: AppThemeMode = DEFAULT_APP_THEME_MODE,
) {
    val isTranslationConfigured: Boolean
        get() = translationProvider.isReady(
            azureEndpoint = translationAzureEndpoint,
            azureApiKey = translationAzureApiKey,
            googleApiKey = translationGoogleApiKey,
        )
}

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
        val ACCOUNT_LOGIN_ENABLED = booleanPreferencesKey("account_login_enabled")
        val ACCOUNT_LOGIN_DOWNLOAD_ENABLED = booleanPreferencesKey("account_login_download_enabled")
        val ACCOUNT_OWNED_GAMES_DISPLAY_ENABLED = booleanPreferencesKey("account_owned_games_display_enabled")
        val ACCOUNT_SUBSCRIPTION_DISPLAY_ENABLED = booleanPreferencesKey("account_subscription_display_enabled")
        val HAS_ACKNOWLEDGED_DISCLAIMER = booleanPreferencesKey("has_acknowledged_disclaimer")
        val HAS_ACKNOWLEDGED_USAGE_BOUNDARY = booleanPreferencesKey("has_acknowledged_usage_boundary")
        val AUTO_CHECK_APP_UPDATES = booleanPreferencesKey("auto_check_app_updates")
        val AUTO_CHECK_DOWNLOADED_MOD_UPDATES_ON_LAUNCH =
            booleanPreferencesKey("auto_check_downloaded_mod_updates_on_launch")
        val LAST_DOWNLOADED_MOD_UPDATES_LAUNCH_CHECK_AT_MS =
            longPreferencesKey("last_downloaded_mod_updates_launch_check_at_ms")
        val DEFAULT_GUEST_MODE = booleanPreferencesKey("default_guest_mode")
        val LAST_CONNECTION_PROFILE = stringPreferencesKey("last_connection_profile")
        val LAST_CDN_HOST = stringPreferencesKey("last_cdn_host")
        val LAST_CDN_TRANSPORT_DIRECT = booleanPreferencesKey("last_cdn_transport_direct")
        val CDN_TRANSPORT_PREFERENCE = stringPreferencesKey("cdn_transport_preference")
        val CDN_POOL_PREFERENCE = stringPreferencesKey("cdn_pool_preference")
        val DOWNLOAD_FOLDER_NAME = stringPreferencesKey("download_folder_name")
        val DOWNLOAD_TREE_URI = stringPreferencesKey("download_tree_uri")
        val DOWNLOAD_TREE_LABEL = stringPreferencesKey("download_tree_label")
        val DOWNLOAD_PERFORMANCE_MODE = stringPreferencesKey("download_performance_mode")
        val DOWNLOAD_CHUNK_CONCURRENCY = intPreferencesKey("download_chunk_concurrency")
        val PRIMORDIAL_REDUCED_MEMORY_PROTECTION_ENABLED =
            booleanPreferencesKey("primordial_reduced_memory_protection_enabled")
        val PRIMORDIAL_AUTHENTICATED_AGGRESSIVE_CDN_ENABLED =
            booleanPreferencesKey("primordial_authenticated_aggressive_cdn_enabled")
        val PREFER_ANONYMOUS_DOWNLOADS = booleanPreferencesKey("prefer_anonymous_downloads")
        val ALLOW_AUTHENTICATED_DOWNLOAD_FALLBACK = booleanPreferencesKey("allow_authenticated_download_fallback")
        val WORKSHOP_PAGE_SIZE = intPreferencesKey("workshop_page_size")
        val WORKSHOP_AUTO_RESOLVE_VISIBLE_ITEMS = booleanPreferencesKey("workshop_auto_resolve_visible_items")
        val ANIMATED_WORKSHOP_PREVIEW_ENABLED = booleanPreferencesKey("animated_workshop_preview_enabled")
        val TERRARIA_ARCHIVE_POST_PROCESSOR_ENABLED =
            booleanPreferencesKey("terraria_archive_post_processor_enabled")
        val TERRARIA_ARCHIVE_KEEP_ORIGINAL =
            booleanPreferencesKey("terraria_archive_keep_original")
        val WALLPAPER_ENGINE_PKG_EXTRACT_ENABLED =
            booleanPreferencesKey("wallpaper_engine_pkg_extract_enabled")
        val WALLPAPER_ENGINE_PKG_KEEP_ORIGINAL =
            booleanPreferencesKey("wallpaper_engine_pkg_keep_original")
        val WALLPAPER_ENGINE_TEX_CONVERSION_ENABLED =
            booleanPreferencesKey("wallpaper_engine_tex_conversion_enabled")
        val WALLPAPER_ENGINE_KEEP_CONVERTED_TEX_ORIGINAL =
            booleanPreferencesKey("wallpaper_engine_keep_converted_tex_original")
        val TRANSLATION_PROVIDER = stringPreferencesKey("translation_provider")
        val TRANSLATION_AZURE_ENDPOINT = stringPreferencesKey("translation_azure_endpoint")
        val TRANSLATION_AZURE_REGION = stringPreferencesKey("translation_azure_region")
        val TRANSLATION_SECRET_VERSION = longPreferencesKey("translation_secret_version")
        val FAVORITE_WORKSHOP_GAMES_JSON = stringPreferencesKey("favorite_workshop_games_json")
        val APP_THEME_MODE = stringPreferencesKey("app_theme_mode")
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
            val actualActiveSessionProfile = secureSessionStore.readActiveSessionProfile()
            val actualActiveRefreshToken = secureSessionStore.readActiveRefreshToken()
                .ifBlank { prefs[REFRESH_TOKEN].orEmpty() }
            val isLoginFeatureEnabled = prefs[ACCOUNT_LOGIN_ENABLED] ?: false
            val activeSessionProfile = if (isLoginFeatureEnabled) {
                actualActiveSessionProfile
            } else {
                PersistedActiveSteamSession()
            }
            val activeRefreshToken = if (isLoginFeatureEnabled) {
                actualActiveRefreshToken
            } else {
                ""
            }
            val sanitizedDownloadTree = sanitizeDownloadTree(
                uri = prefs[DOWNLOAD_TREE_URI],
                label = prefs[DOWNLOAD_TREE_LABEL],
            )
            val downloadPerformanceMode = prefs[DOWNLOAD_PERFORMANCE_MODE]
                ?.let { value -> runCatching { DownloadPerformanceMode.valueOf(value) }.getOrNull() }
                ?: DownloadPerformanceMode.Auto
            val translationProvider = prefs[TRANSLATION_PROVIDER]
                ?.let { value -> runCatching { TranslationProvider.valueOf(value) }.getOrNull() }
                ?: DEFAULT_TRANSLATION_PROVIDER
            val themeMode = prefs[APP_THEME_MODE]
                ?.let { value -> runCatching { AppThemeMode.valueOf(value) }.getOrNull() }
                ?: DEFAULT_APP_THEME_MODE
            val savedAccounts = if (isLoginFeatureEnabled) {
                buildSavedAccounts(
                    accountName = actualActiveSessionProfile.accountName,
                    refreshToken = actualActiveRefreshToken,
                    clientId = actualActiveSessionProfile.clientId,
                    steamId64 = actualActiveSessionProfile.steamId64,
                    rememberSession = prefs[REMEMBER_SESSION] ?: true,
                    persistedAccounts = secureSessionStore.readSavedAccountsMetadata(),
                    legacyEncodedAccounts = prefs[SAVED_ACCOUNTS_JSON],
                )
            } else {
                emptyList()
            }
            UserPreferences(
                accountName = activeSessionProfile.accountName,
                refreshToken = activeRefreshToken,
                clientId = activeSessionProfile.clientId,
                steamId64 = activeSessionProfile.steamId64,
                rememberSession = prefs[REMEMBER_SESSION] ?: true,
                savedAccounts = savedAccounts,
                isLoginFeatureEnabled = isLoginFeatureEnabled,
                isLoggedInDownloadEnabled = prefs[ACCOUNT_LOGIN_DOWNLOAD_ENABLED] ?: false,
                isOwnedGamesDisplayEnabled = prefs[ACCOUNT_OWNED_GAMES_DISPLAY_ENABLED] ?: false,
                isSubscriptionDisplayEnabled = prefs[ACCOUNT_SUBSCRIPTION_DISPLAY_ENABLED] ?: false,
                hasAcknowledgedDisclaimer = prefs[HAS_ACKNOWLEDGED_DISCLAIMER] ?: false,
                hasAcknowledgedUsageBoundary = prefs[HAS_ACKNOWLEDGED_USAGE_BOUNDARY] ?: false,
                autoCheckAppUpdates = prefs[AUTO_CHECK_APP_UPDATES] ?: false,
                autoCheckDownloadedModUpdatesOnLaunch =
                    prefs[AUTO_CHECK_DOWNLOADED_MOD_UPDATES_ON_LAUNCH] ?: false,
                lastDownloadedModUpdatesLaunchCheckAtMs =
                    prefs[LAST_DOWNLOADED_MOD_UPDATES_LAUNCH_CHECK_AT_MS] ?: 0L,
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
                downloadPerformanceMode = downloadPerformanceMode,
                downloadChunkConcurrency = requestedDownloadChunkConcurrency(downloadPerformanceMode),
                primordialReducedMemoryProtectionEnabled =
                    prefs[PRIMORDIAL_REDUCED_MEMORY_PROTECTION_ENABLED] ?: false,
                primordialAuthenticatedAggressiveCdnEnabled =
                    prefs[PRIMORDIAL_AUTHENTICATED_AGGRESSIVE_CDN_ENABLED] ?: false,
                preferAnonymousDownloads = prefs[PREFER_ANONYMOUS_DOWNLOADS] ?: true,
                allowAuthenticatedDownloadFallback = prefs[ALLOW_AUTHENTICATED_DOWNLOAD_FALLBACK] ?: true,
                workshopPageSize = WorkshopBrowseQuery.normalizePageSize(
                    prefs[WORKSHOP_PAGE_SIZE] ?: WorkshopBrowseQuery.DEFAULT_PAGE_SIZE,
                ),
                workshopAutoResolveVisibleItems = prefs[WORKSHOP_AUTO_RESOLVE_VISIBLE_ITEMS] ?: false,
                animatedWorkshopPreviewEnabled = prefs[ANIMATED_WORKSHOP_PREVIEW_ENABLED] ?: false,
                terrariaArchivePostProcessorEnabled =
                    prefs[TERRARIA_ARCHIVE_POST_PROCESSOR_ENABLED] ?: false,
                terrariaArchiveKeepOriginal = prefs[TERRARIA_ARCHIVE_KEEP_ORIGINAL] ?: false,
                wallpaperEnginePkgExtractEnabled =
                    prefs[WALLPAPER_ENGINE_PKG_EXTRACT_ENABLED] ?: false,
                wallpaperEnginePkgKeepOriginal = prefs[WALLPAPER_ENGINE_PKG_KEEP_ORIGINAL] ?: false,
                wallpaperEngineTexConversionEnabled =
                    prefs[WALLPAPER_ENGINE_TEX_CONVERSION_ENABLED] ?: false,
                wallpaperEngineKeepConvertedTexOriginal =
                    prefs[WALLPAPER_ENGINE_KEEP_CONVERTED_TEX_ORIGINAL] ?: true,
                translationProvider = translationProvider,
                translationAzureEndpoint = prefs[TRANSLATION_AZURE_ENDPOINT]
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?: DEFAULT_AZURE_TRANSLATOR_ENDPOINT,
                translationAzureRegion = prefs[TRANSLATION_AZURE_REGION]?.trim().orEmpty(),
                translationAzureApiKey = secureSessionStore.readTranslationAzureApiKey(),
                translationGoogleApiKey = secureSessionStore.readTranslationGoogleApiKey(),
                favoriteWorkshopGames = decodeFavoriteWorkshopGames(prefs[FAVORITE_WORKSHOP_GAMES_JSON]),
                themeMode = themeMode,
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
        secureSessionStore.clearOwnedGamesSnapshotPayload()
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
        secureSessionStore.clearOwnedGamesSnapshotPayload()
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
        if (removedActiveAccount) {
            secureSessionStore.clearOwnedGamesSnapshotPayload()
        }
    }

    suspend fun saveDefaultGuestMode(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[DEFAULT_GUEST_MODE] = enabled
        }
    }

    suspend fun saveLoginFeatureEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[ACCOUNT_LOGIN_ENABLED] = enabled
        }
    }

    suspend fun saveLoggedInDownloadEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[ACCOUNT_LOGIN_DOWNLOAD_ENABLED] = enabled
        }
    }

    suspend fun saveOwnedGamesDisplayEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[ACCOUNT_OWNED_GAMES_DISPLAY_ENABLED] = enabled
        }
    }

    suspend fun saveSubscriptionDisplayEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[ACCOUNT_SUBSCRIPTION_DISPLAY_ENABLED] = enabled
        }
    }

    suspend fun saveAutoCheckAppUpdates(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[AUTO_CHECK_APP_UPDATES] = enabled
        }
    }

    suspend fun saveAutoCheckDownloadedModUpdatesOnLaunch(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[AUTO_CHECK_DOWNLOADED_MOD_UPDATES_ON_LAUNCH] = enabled
        }
    }

    suspend fun saveLastDownloadedModUpdatesLaunchCheckAt(timestampMs: Long) {
        dataStore.edit { prefs ->
            prefs[LAST_DOWNLOADED_MOD_UPDATES_LAUNCH_CHECK_AT_MS] = timestampMs
        }
    }

    suspend fun saveAppThemeMode(themeMode: AppThemeMode) {
        dataStore.edit { prefs ->
            prefs[APP_THEME_MODE] = themeMode.name
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
        val normalized = normalizeDownloadChunkConcurrency(concurrency)
        val mode = when {
            normalized <= COMPATIBILITY_DOWNLOAD_CHUNK_CONCURRENCY -> DownloadPerformanceMode.Compatibility
            normalized > DEFAULT_DOWNLOAD_CHUNK_CONCURRENCY -> DownloadPerformanceMode.Primordial
            else -> DownloadPerformanceMode.Auto
        }
        saveDownloadPerformanceMode(mode)
    }

    suspend fun saveDownloadPerformanceMode(mode: DownloadPerformanceMode) {
        dataStore.edit { prefs ->
            prefs[DOWNLOAD_PERFORMANCE_MODE] = mode.name
            prefs[DOWNLOAD_CHUNK_CONCURRENCY] = requestedDownloadChunkConcurrency(mode)
        }
    }

    suspend fun savePrimordialReducedMemoryProtectionEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[PRIMORDIAL_REDUCED_MEMORY_PROTECTION_ENABLED] = enabled
        }
    }

    suspend fun savePrimordialAuthenticatedAggressiveCdnEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[PRIMORDIAL_AUTHENTICATED_AGGRESSIVE_CDN_ENABLED] = enabled
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

    suspend fun saveAnimatedWorkshopPreviewEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[ANIMATED_WORKSHOP_PREVIEW_ENABLED] = enabled
        }
    }

    suspend fun saveTerrariaArchivePostProcessorEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[TERRARIA_ARCHIVE_POST_PROCESSOR_ENABLED] = enabled
        }
    }

    suspend fun saveTerrariaArchiveKeepOriginal(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[TERRARIA_ARCHIVE_KEEP_ORIGINAL] = enabled
        }
    }

    suspend fun saveWallpaperEnginePkgExtractEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[WALLPAPER_ENGINE_PKG_EXTRACT_ENABLED] = enabled
        }
    }

    suspend fun saveWallpaperEnginePkgKeepOriginal(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[WALLPAPER_ENGINE_PKG_KEEP_ORIGINAL] = enabled
        }
    }

    suspend fun saveWallpaperEngineTexConversionEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[WALLPAPER_ENGINE_TEX_CONVERSION_ENABLED] = enabled
        }
    }

    suspend fun saveWallpaperEngineKeepConvertedTexOriginal(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[WALLPAPER_ENGINE_KEEP_CONVERTED_TEX_ORIGINAL] = enabled
        }
    }

    suspend fun saveTranslationProvider(provider: TranslationProvider) {
        dataStore.edit { prefs ->
            prefs[TRANSLATION_PROVIDER] = provider.name
        }
    }

    suspend fun saveTranslationAzureEndpoint(endpoint: String) {
        dataStore.edit { prefs ->
            prefs[TRANSLATION_AZURE_ENDPOINT] = endpoint
                .trim()
                .ifBlank { DEFAULT_AZURE_TRANSLATOR_ENDPOINT }
        }
    }

    suspend fun saveTranslationAzureRegion(region: String) {
        dataStore.edit { prefs ->
            prefs[TRANSLATION_AZURE_REGION] = region.trim()
        }
    }

    suspend fun saveTranslationAzureApiKey(apiKey: String) {
        migrateLegacySecretsIfNeeded()
        secureSessionStore.writeTranslationAzureApiKey(apiKey.trim())
        dataStore.edit { prefs ->
            prefs[TRANSLATION_SECRET_VERSION] = System.currentTimeMillis()
        }
    }

    suspend fun saveTranslationGoogleApiKey(apiKey: String) {
        migrateLegacySecretsIfNeeded()
        secureSessionStore.writeTranslationGoogleApiKey(apiKey.trim())
        dataStore.edit { prefs ->
            prefs[TRANSLATION_SECRET_VERSION] = System.currentTimeMillis()
        }
    }

    suspend fun clearTranslationSettings() {
        migrateLegacySecretsIfNeeded()
        secureSessionStore.clearTranslationAzureApiKey()
        secureSessionStore.clearTranslationGoogleApiKey()
        dataStore.edit { prefs ->
            prefs[TRANSLATION_PROVIDER] = TranslationProvider.Disabled.name
            prefs[TRANSLATION_AZURE_ENDPOINT] = DEFAULT_AZURE_TRANSLATOR_ENDPOINT
            prefs[TRANSLATION_AZURE_REGION] = ""
            prefs[TRANSLATION_SECRET_VERSION] = System.currentTimeMillis()
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
        migrateLegacySecretsIfNeeded()
        secureSessionStore.writeOwnedGamesSnapshotPayload(payloadJson)
        dataStore.edit { prefs ->
            prefs[OWNED_GAMES_SNAPSHOT_STEAM_ID64] = steamId64
            prefs[OWNED_GAMES_SNAPSHOT_SAVED_AT_MS] = savedAtMs
            prefs.remove(OWNED_GAMES_SNAPSHOT_JSON)
        }
    }

    suspend fun clearOwnedGamesSnapshot() {
        migrateLegacySecretsIfNeeded()
        secureSessionStore.clearOwnedGamesSnapshotPayload()
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
        migrateLegacySecretsIfNeeded()
        val prefs = dataStore.data.first()
        return OwnedGamesSnapshot(
            steamId64 = prefs[OWNED_GAMES_SNAPSHOT_STEAM_ID64] ?: 0,
            savedAtMs = prefs[OWNED_GAMES_SNAPSHOT_SAVED_AT_MS] ?: 0,
            payloadJson = secureSessionStore.readOwnedGamesSnapshotPayload(),
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

                prefs[OWNED_GAMES_SNAPSHOT_JSON]
                    ?.takeIf { it.isNotBlank() && secureSessionStore.readOwnedGamesSnapshotPayload().isBlank() }
                    ?.let(secureSessionStore::writeOwnedGamesSnapshotPayload)

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
                prefs.remove(OWNED_GAMES_SNAPSHOT_JSON)
            }
            legacySecretsMigrated = true
        }
    }
}

private fun PersistedActiveSteamSession.isBlankProfile(): Boolean {
    return accountName.isBlank() && clientId <= 0L && steamId64 <= 0L
}
