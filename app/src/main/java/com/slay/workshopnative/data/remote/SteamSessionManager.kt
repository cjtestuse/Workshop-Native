package com.slay.workshopnative.data.remote

import android.app.ActivityManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import androidx.annotation.WorkerThread
import androidx.core.text.HtmlCompat
import androidx.documentfile.provider.DocumentFile
import com.slay.workshopnative.core.logging.AppLog as Log
import com.slay.workshopnative.core.logging.SupportDiagnosticsStore
import com.slay.workshopnative.core.logging.SupportDownloadDecisionSnapshot
import com.slay.workshopnative.core.logging.SupportPerformanceSnapshot
import com.slay.workshopnative.core.logging.SupportSessionRuntimeSnapshot
import com.slay.workshopnative.core.storage.createMediaStoreFileOutput
import com.slay.workshopnative.core.storage.createMediaStoreFileUri
import com.slay.workshopnative.core.storage.copyLocalFileToUri
import com.slay.workshopnative.core.storage.finalizeMediaStoreFile
import com.slay.workshopnative.core.storage.findMediaStoreDownloadUri
import com.slay.workshopnative.core.storage.MEDIASTORE_DOWNLOADS_URI_STRING
import com.slay.workshopnative.core.storage.openExistingMediaStoreFileOutput
import com.slay.workshopnative.core.storage.queryUriSize
import com.slay.workshopnative.core.storage.uniqueMediaStoreRootName
import com.slay.workshopnative.core.storage.workshopDownloadStagingRoot
import com.slay.workshopnative.core.util.buildAccountBindingHash
import com.slay.workshopnative.core.util.DownloadPausedException
import com.slay.workshopnative.core.util.sanitizeFileName
import com.slay.workshopnative.core.util.toUserMessage
import com.slay.workshopnative.data.local.DownloadAuthMode
import com.slay.workshopnative.data.model.AuthChallenge
import com.slay.workshopnative.data.model.AuthChallengeType
import com.slay.workshopnative.data.model.GameDetails
import com.slay.workshopnative.data.model.OwnedGame
import com.slay.workshopnative.data.model.OwnershipSource
import com.slay.workshopnative.data.model.SessionStatus
import com.slay.workshopnative.data.model.SteamAccountSession
import com.slay.workshopnative.data.model.SteamSessionState
import com.slay.workshopnative.data.model.WorkshopBrowsePage
import com.slay.workshopnative.data.model.WorkshopBrowsePeriodOption
import com.slay.workshopnative.data.model.WorkshopBrowseQuery
import com.slay.workshopnative.data.model.WorkshopBrowseSectionOption
import com.slay.workshopnative.data.model.WorkshopBrowseSortOption
import com.slay.workshopnative.data.model.WorkshopDateRangeFilter
import com.slay.workshopnative.data.model.WorkshopBrowseTagGroup
import com.slay.workshopnative.data.model.WorkshopBrowseTagGroupSelectionMode
import com.slay.workshopnative.data.model.WorkshopBrowseTagOption
import com.slay.workshopnative.data.model.WorkshopGameEntry
import com.slay.workshopnative.data.model.WorkshopGamePage
import com.slay.workshopnative.data.model.WorkshopItem
import com.slay.workshopnative.data.preferences.CdnPoolPreference
import com.slay.workshopnative.data.preferences.CdnTransportPreference
import com.slay.workshopnative.data.preferences.DownloadPerformanceMode
import com.slay.workshopnative.data.preferences.MAX_DOWNLOAD_CHUNK_CONCURRENCY
import com.slay.workshopnative.data.preferences.normalizeDownloadChunkConcurrency
import com.slay.workshopnative.data.preferences.UserPreferencesStore
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.dragonbra.javasteam.enums.EAccountType
import `in`.dragonbra.javasteam.enums.EDepotFileFlag
import `in`.dragonbra.javasteam.enums.ELicenseFlags
import `in`.dragonbra.javasteam.enums.ELicenseType
import `in`.dragonbra.javasteam.enums.EPublishedFileQueryType
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.enums.EOSType
import `in`.dragonbra.javasteam.enums.EWorkshopFileType
import `in`.dragonbra.javasteam.networking.steam3.ProtocolTypes
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPlayerSteamclient
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPublishedfileSteamclient
import `in`.dragonbra.javasteam.rpc.service.Player
import `in`.dragonbra.javasteam.rpc.service.PublishedFile
import `in`.dragonbra.javasteam.steam.cdn.Client as SteamCdnClient
import `in`.dragonbra.javasteam.steam.cdn.DepotChunk
import `in`.dragonbra.javasteam.steam.cdn.Server
import `in`.dragonbra.javasteam.steam.authentication.AuthSessionDetails
import `in`.dragonbra.javasteam.steam.authentication.AuthenticationException
import `in`.dragonbra.javasteam.steam.authentication.IAuthenticator
import `in`.dragonbra.javasteam.steam.discovery.MemoryServerListProvider
import `in`.dragonbra.javasteam.steam.discovery.ServerRecord
import `in`.dragonbra.javasteam.steam.handlers.steamapps.License
import `in`.dragonbra.javasteam.steam.handlers.steamapps.PICSProductInfo
import `in`.dragonbra.javasteam.steam.handlers.steamapps.PICSRequest
import `in`.dragonbra.javasteam.steam.handlers.steamapps.SteamApps
import `in`.dragonbra.javasteam.steam.handlers.steamapps.callback.LicenseListCallback
import `in`.dragonbra.javasteam.steam.handlers.steamcontent.SteamContent
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.SteamUnifiedMessages
import `in`.dragonbra.javasteam.steam.handlers.steamuser.ChatMode
import `in`.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails
import `in`.dragonbra.javasteam.steam.handlers.steamuser.AnonymousLogOnDetails
import `in`.dragonbra.javasteam.steam.handlers.steamuser.SteamUser
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOffCallback
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback
import `in`.dragonbra.javasteam.steam.steamclient.AsyncJobFailedException
import `in`.dragonbra.javasteam.steam.steamclient.SteamClient
import `in`.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback
import `in`.dragonbra.javasteam.steam.steamclient.configuration.SteamConfiguration
import `in`.dragonbra.javasteam.types.DepotManifest
import `in`.dragonbra.javasteam.types.KeyValue
import `in`.dragonbra.javasteam.types.SteamID
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.URI
import java.net.ProxySelector
import java.net.Proxy
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.jsoup.Jsoup

@Singleton
class SteamSessionManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val preferencesStore: UserPreferencesStore,
    private val supportDiagnosticsStore: SupportDiagnosticsStore,
) {
    companion object {
        private const val LOG_TAG = "SteamSessionManager"
        private const val CDN_FAILURE_COOLDOWN_MS = 5 * 60 * 1000L
        private const val OWNED_GAMES_CACHE_MS = 2 * 60 * 1000L
        private const val OWNED_GAMES_SNAPSHOT_MAX_AGE_MS = 24 * 60 * 60 * 1000L
        private const val WORKSHOP_DEPOT_ID_CACHE_MS = 24 * 60 * 60 * 1000L
        private const val WORKSHOP_BROWSE_CACHE_MS = 45_000L
        private const val WORKSHOP_SUBSCRIPTIONS_CACHE_MS = 45_000L
        private const val WORKSHOP_AUTHENTICATED_QUERY_CACHE_MS = 45_000L
        private const val WORKSHOP_ITEM_CACHE_MS = 10 * 60 * 1000L
        private const val WORKSHOP_CONTENT_ACCESS_CACHE_MS = 5 * 60 * 1000L
        private const val WORKSHOP_GAME_DISCOVERY_CACHE_MS = 45_000L
        private const val CDN_SERVER_LIST_CACHE_MS = 10 * 60 * 1000L
        private const val ANONYMOUS_CONTENT_SESSION_IDLE_MS = 60 * 60 * 1000L
        private const val ANONYMOUS_PREWARM_COOLDOWN_MS = 10 * 60 * 1000L
        private const val INTERACTIVE_LOGIN_RECOVERY_ATTEMPTS = 3
        private const val FOREGROUND_RECOVERY_DEBOUNCE_MS = 1_500L
        private const val DOWNLOAD_AUTH_RESTORE_TIMEOUT_MS = 30_000L
        private const val DOWNLOAD_WORKSHOP_DEPOT_LOOKUP_TIMEOUT_MS = 12_000L
        private const val DOWNLOAD_ANONYMOUS_CONTENT_ACCESS_TIMEOUT_MS = 18_000L
        private const val DOWNLOAD_DEPOT_KEY_TIMEOUT_MS = 12_000L
        private const val DOWNLOAD_CDN_SERVER_LIST_TIMEOUT_MS = 12_000L
        private const val DOWNLOAD_MANIFEST_REQUEST_CODE_TIMEOUT_MS = 12_000L
        private const val CDN_SERVER_CANDIDATE_LIMIT = 40
        private const val MAX_CDN_ROUTES_PER_TRANSPORT = 4
        private const val MAX_ACTIVE_CDN_ROUTES = 4
        private const val CDN_MANIFEST_RACE_ROUTE_COUNT = 2
        private const val MAX_CDN_ROUTE_ATTEMPTS_PER_CHUNK = 3
        private const val CDN_READ_TIMEOUT_SECONDS = 10L
        private const val CDN_CALL_TIMEOUT_SECONDS = 12L
        private const val MAX_DOWNLOAD_CHUNK_PIPELINE_DEPTH = 8
        private const val WORKSHOP_FILE_WRITE_BUFFER_SIZE = 1024 * 1024
        private const val EXPORT_PROGRESS_UPDATE_INTERVAL_MS = 1000L
        private const val MAX_CDN_SERVER_ATTEMPTS_PER_TRANSPORT = 4
        private const val STEAM_COMMUNITY_URL = "https://steamcommunity.com/workshop/browse/"
        private const val STEAM_WORKSHOP_HOME_URL = "https://steamcommunity.com/workshop/"
        private const val STEAM_WORKSHOP_SEARCH_URL = "https://steamcommunity.com/workshop/ajaxfindworkshops/"
        private const val STEAM_WORKSHOP_EXPLORE_AJAX_URL = "https://steamcommunity.com/sharedfiles/ajaxgetworkshops/"
        private const val WORKSHOP_EXPLORE_PAGE_SIZE = 8
        private const val STEAM_STORE_APP_DETAILS_URL = "https://store.steampowered.com/api/appdetails"
        private const val STEAM_PUBLISHED_FILE_DETAILS_URL = "https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/"
        private const val WORKSHOP_USER_FILES_TYPE_SUBSCRIPTIONS = "mysubscriptions"
        private const val LOCAL_ROOT_REF_PREFIX = "local:"
        private const val TREE_ROOT_REF_PREFIX = "tree:"
        private const val DOWNLOADS_ROOT_REF_PREFIX = "downloads:"
        private val WORKSHOP_PREVIEW_REGEX = Regex(
            """data-publishedfileid="(\d+)".*?<img[^>]*class="[^"]*workshopItemPreviewImage[^"]*"[^>]*src="([^"]+)"""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        private val WORKSHOP_AUTHOR_REGEX = Regex(
            """data-publishedfileid="(\d+)".*?<div class="workshopItemAuthorName ellipsis">by&nbsp;<a[^>]*>(.*?)</a>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        private val WORKSHOP_HOVER_REGEX = Regex(
            """SharedFileBindMouseHover\(\s*"sharedfile_(\d+)"\s*,\s*false\s*,\s*(\{.*?\})\s*\);""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        private val WORKSHOP_TOTAL_EN_REGEX = Regex("""Showing\s+\d+\s*-\s*\d+\s+of\s+([\d,]+)\s+entries""")
        private val WORKSHOP_TOTAL_ZH_REGEX = Regex("""正在显示第\s*\d+\s*-\s*\d+\s*项，共\s*([\d,]+)\s*项条目""")
        private val WORKSHOP_PAGE_LINK_REGEX = Regex("""[?&]p=(\d+)""")
        private val WORKSHOP_GAME_ENTRY_REGEX = Regex(
            """class="app"[^>]*onClick="top\.location\.href='https://steamcommunity\.com/app/(\d+)/workshop/'".*?itemPreviewHolder" style="background:\s*url\('([^']*)'.*?<img class="appLogo" src="([^"]+)" alt="([^"]+)".*?<div class="appNumItems">([^<]+)</div>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        private val WORKSHOP_ITEM_COUNT_REGEX = Regex("""(\d[\d,]*)""")
        private val WORKSHOP_SECTION_REGEX = Regex("""section=([^"&]+)[^>]*&gt;([^<&]+)&lt;/a&gt;""")
        private val WORKSHOP_SORTING_CONTROLS_REGEX = Regex(
            """<div class="workshopBrowseSortingControls">(.*?)</div>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val WORKSHOP_DROPDOWN_HTML_REGEX = Regex(
            """class="dropdown"\s+data-dropdown-html="(.*?)"[\s>]*""",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val WORKSHOP_SORT_OPTION_REGEX = Regex("""actualsort=([^"&]+)[^>]*>([^<]+)</a>""")
        private val WORKSHOP_PERIOD_OPTION_REGEX = Regex("""days=([-0-9]+)[^>]*>([^<]+)</a>""")
        private val WORKSHOP_TAG_CATEGORY_REGEX = Regex("""<div class="tag_category_desc">\s*(.*?)\s*</div>""", RegexOption.DOT_MATCHES_ALL)
        private val WORKSHOP_TAG_OPTION_REGEX = Regex(
            """name="requiredtags\[\]"[^>]*value="([^"]+)"[^>]*class="inputTagsFilter"\s*/>\s*(.*?)\s*</label>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        private val seededCmServers = listOf(
            "cmp1-tyo3.steamserver.net:27020",
            "cmp2-tyo3.steamserver.net:27018",
            "cmp2-tyo3.steamserver.net:27019",
            "cmp1-tyo3.steamserver.net:443",
            "cmp1-hkg1.steamserver.net:443",
            "cmp2-hkg1.steamserver.net:27025",
            "cmp2-sgp1.steamserver.net:27020",
            "cmp1-sgp1.steamserver.net:27019",
            "cmp1-sea1.steamserver.net:443",
            "cmp2-ord1.steamserver.net:443",
            "cmp1-fra1.steamserver.net:443",
            "cmp2-fra1.steamserver.net:27018",
            "cmp1-iad1.steamserver.net:443",
            "cmp2-iad1.steamserver.net:27019",
        )
        private val cdnFailureTracker = ConcurrentHashMap<String, Long>()
        @Volatile
        private var lastSuccessfulCdnHost: String? = null
        @Volatile
        private var lastSuccessfulCdnTransportDirect: Boolean? = null
    }

    private data class ConnectionProfile(
        val label: String,
        val protocolTypes: EnumSet<ProtocolTypes>,
        val connectionTimeoutMs: Long,
        val useDirectoryFetch: Boolean,
        val serverListProviderFactory: (() -> MemoryServerListProvider)? = null,
    )

    private data class CachedValue<T>(
        val value: T,
        val cachedAtMs: Long = System.currentTimeMillis(),
    ) {
        fun isFresh(ttlMs: Long): Boolean {
            return System.currentTimeMillis() - cachedAtMs <= ttlMs
        }
    }

    private data class DownloadedChunk(
        val buffer: ByteArray,
        val written: Int,
    )

    private data class FileResumePlan(
        val relativePath: String,
        val restoredChunkCount: Int,
        val restoredBytes: Long,
    )

    private data class WorkshopContentAccess(
        val depotKey: ByteArray,
        val servers: List<Server>,
        val manifestRequestCode: Long,
        val accessLabel: String,
        val allowInsecureTransport: Boolean,
        val cdnAuthTokenProvider: (suspend (String) -> String?)? = null,
    )

    private data class WorkshopContentAccessCacheKey(
        val authMode: DownloadAuthMode,
        val appId: Int,
        val depotId: Int,
        val manifestId: Long,
        val steamId64: Long?,
    )

    private data class WorkshopDetailPageContent(
        val title: String = "",
        val description: String = "",
    )

    private class AuthSessionInterruptedException(
        message: String,
        cause: Throwable? = null,
    ) : IllegalStateException(message, cause)

    private class CdnHostPerformance {
        private val activeRequests = AtomicLong(0L)
        private val failureCount = AtomicLong(0L)
        private val sampledBytes = AtomicLong(0L)
        private val sampledNanos = AtomicLong(0L)

        fun beginRequest() {
            activeRequests.incrementAndGet()
        }

        fun finishSuccess(sampleBytesCount: Long, durationNanos: Long) {
            activeRequests.decrementAndGet()
            if (sampleBytesCount > 0L && durationNanos > 0L) {
                sampledBytes.addAndGet(sampleBytesCount)
                sampledNanos.addAndGet(durationNanos)
            }
            while (true) {
                val current = failureCount.get()
                if (current <= 0L) break
                if (failureCount.compareAndSet(current, current - 1L)) {
                    break
                }
            }
        }

        fun finishFailure() {
            activeRequests.decrementAndGet()
            failureCount.incrementAndGet()
        }

        fun finishCancelled() {
            activeRequests.decrementAndGet()
        }

        fun activeRequestCount(): Long = activeRequests.get().coerceAtLeast(0L)

        fun failurePenalty(): Long = failureCount.get().coerceAtLeast(0L)

        fun hasSamples(): Boolean = sampledBytes.get() > 0L && sampledNanos.get() > 0L

        fun averageNanosPerMiB(): Long {
            val bytes = sampledBytes.get()
            val nanos = sampledNanos.get()
            if (bytes <= 0L || nanos <= 0L) return Long.MAX_VALUE
            return (nanos * 1024L * 1024L) / bytes.coerceAtLeast(1L)
        }
    }

    private data class TransportClient(
        val forceDirect: Boolean,
        val transportLabel: String,
        val transportDisplayLabel: String,
        val client: SteamCdnClient,
    )

    private class CdnRoute(
        val rank: Int,
        val forceDirect: Boolean,
        val transportLabel: String,
        val transportDisplayLabel: String,
        val client: SteamCdnClient,
        val server: Server,
        val hostName: String,
        val serverLabel: String,
        val performance: CdnHostPerformance,
        private val tokenProvider: suspend () -> String?,
    ) {
        val selectionKey: String = "${hostName.lowercase()}|${if (forceDirect) "direct" else "system"}"
        private val tokenMutex = Mutex()
        @Volatile
        private var tokenResolved = false
        @Volatile
        private var cachedToken: String? = null

        suspend fun authToken(): String? {
            if (tokenResolved) return cachedToken
            return tokenMutex.withLock {
                if (!tokenResolved) {
                    cachedToken = tokenProvider()
                    tokenResolved = true
                }
                cachedToken
            }
        }
    }

    private data class ManifestProbeResult(
        val route: CdnRoute,
        val manifest: DepotManifest? = null,
        val failure: Throwable? = null,
    )

    private data class DownloadPerformanceProfile(
        val mode: DownloadPerformanceMode,
        val requestedChunkConcurrency: Int,
        val chunkConcurrency: Int,
        val maxRoutesPerTransport: Int,
        val maxActiveRoutes: Int,
        val dispatcherRequestFloor: Int,
        val dispatcherPerHostFloor: Int,
        val heapBudgetMb: Int,
        val availableHeapMb: Int,
        val isLowRamDevice: Boolean,
    )

    private class AnonymousContentSession(
        val profileLabel: String,
        val client: SteamClient,
        val callbackManager: CallbackManager,
        val apps: SteamApps,
        val content: SteamContent,
        val user: SteamUser,
        val callbackScope: CoroutineScope,
        val callbackJob: Job,
        val subscriptions: List<Closeable>,
        val connectionAlive: AtomicBoolean = AtomicBoolean(true),
        val lastUsedAtMs: AtomicLong = AtomicLong(System.currentTimeMillis()),
    )

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionMutex = Mutex()
    private val anonymousContentSessionMutex = Mutex()
    private val callbackSubscriptions = mutableListOf<Closeable>()

    private var steamClient: SteamClient? = null
    private var callbackManager: CallbackManager? = null
    private var steamApps: SteamApps? = null
    private var steamContent: SteamContent? = null
    private var steamUser: SteamUser? = null
    private var unifiedMessages: SteamUnifiedMessages? = null
    private var playerService: Player? = null
    private var publishedFileService: PublishedFile? = null

    private var callbackLoopStarted = false
    private var isConnected = false
    private var manualLogout = false
    private var pendingAccountName = ""
    private var pendingRefreshToken = ""
    private var pendingRememberSession = true
    private var preferredConnectionProfileLabel: String? = null

    private var connectDeferred: CompletableDeferred<Unit>? = null
    private var logonDeferred: CompletableDeferred<Result<SteamAccountSession>>? = null
    private var licenseListDeferred: CompletableDeferred<List<License>>? = null
    private var pendingAuthCode: CompletableFuture<String>? = null
    private var bufferedAuthCode: String? = null
    private var lastSubmittedAuthCode: String? = null
    private var interactiveLoginJob: Job? = null
    private var recoveryJob: Job? = null
    private var bootstrapJob: Job? = null
    private var latestLicenseList: List<License> = emptyList()
    private var connectionRevision: Long = 0L
    private var ownedGamesCacheSteamId64: Long = 0L
    private var ownedGamesCache: CachedValue<List<OwnedGame>>? = null
    private val workshopDepotIdCache = ConcurrentHashMap<Int, CachedValue<Int>>()
    private val workshopBrowsePageCache = ConcurrentHashMap<String, CachedValue<WorkshopBrowsePage>>()
    private val workshopAuthenticatedQueryPageCache = ConcurrentHashMap<String, CachedValue<WorkshopBrowsePage>>()
    private val workshopSubscriptionsPageCache = ConcurrentHashMap<String, CachedValue<WorkshopBrowsePage>>()
    private val workshopGamePageCache = ConcurrentHashMap<Int, CachedValue<WorkshopGamePage>>()
    private val workshopGameSearchCache = ConcurrentHashMap<String, CachedValue<List<WorkshopGameEntry>>>()
    private val workshopItemCache = ConcurrentHashMap<Long, CachedValue<WorkshopItem>>()
    private val downloadWorkshopItemCache = ConcurrentHashMap<Long, CachedValue<WorkshopItem>>()
    private val workshopContentAccessCache =
        ConcurrentHashMap<WorkshopContentAccessCacheKey, CachedValue<WorkshopContentAccess>>()
    private val cdnHostPerformanceTracker = ConcurrentHashMap<String, CdnHostPerformance>()
    private val cdnConnectionPoolCache = ConcurrentHashMap<Boolean, ConnectionPool>()
    private val cdnServerCacheMutex = Mutex()
    private var authenticatedCdnServersCache: CachedValue<List<Server>>? = null
    private var anonymousCdnServersCache: CachedValue<List<Server>>? = null
    private var anonymousContentSession: AnonymousContentSession? = null
    private var anonymousPrewarmInFlight = false
    private var lastAnonymousPrewarmAtMs = 0L
    private var appInForeground = true
    private var pendingForegroundRecovery = false
    private var lastForegroundRecoveryAtMs = 0L
    private var lastAppForegroundAtMs: Long? = System.currentTimeMillis()
    private var lastAppBackgroundAtMs: Long? = null
    private var lastConnectedAtMs: Long? = null
    private var lastDisconnectedAtMs: Long? = null
    private var networkAvailable: Boolean? = null
    private var networkValidated: Boolean? = null
    private var lastNetworkAvailableAtMs: Long? = null
    private var lastNetworkLostAtMs: Long? = null
    private var lastNetworkChangedAtMs: Long? = null
    private var lastObservedNetworkTransport: String? = null
    private var lastDisconnectCategory: String? = null
    private var lastDisconnectDetail: String? = null
    private var lastDisconnectUserInitiated: Boolean? = null
    private var lastDisconnectWhileForeground: Boolean? = null
    private var connectivityMonitoringRegistered = false

    private val _sessionState = MutableStateFlow(SteamSessionState())
    val sessionState: StateFlow<SteamSessionState> = _sessionState.asStateFlow()

    fun isAuthenticatedDownloadReady(): Boolean = hasUsableAuthenticatedDownloadSession()

    private val connectivityManager: ConnectivityManager? by lazy {
        appContext.getSystemService(ConnectivityManager::class.java)
    }

    private val connectivityCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            updateObservedNetworkState(
                available = true,
                capabilities = connectivityManager?.getNetworkCapabilities(network),
                action = "network_available",
            )
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            updateObservedNetworkState(
                available = true,
                capabilities = networkCapabilities,
                action = "network_capabilities_changed",
            )
        }

        override fun onLost(network: Network) {
            updateObservedNetworkState(
                available = false,
                capabilities = null,
                action = "network_lost",
            )
        }
    }

    init {
        registerConnectivityMonitoring()
        refreshObservedNetworkState()
    }

    private val primaryConnectionProfiles = listOf(
        ConnectionProfile(
            label = "ws-directory",
            protocolTypes = EnumSet.of(ProtocolTypes.WEB_SOCKET),
            connectionTimeoutMs = 15_000L,
            useDirectoryFetch = true,
        ),
        ConnectionProfile(
            label = "ws-seeded",
            protocolTypes = EnumSet.of(ProtocolTypes.WEB_SOCKET),
            connectionTimeoutMs = 15_000L,
            useDirectoryFetch = false,
            serverListProviderFactory = ::createSeededServerListProvider,
        ),
        ConnectionProfile(
            label = "tcp-directory",
            protocolTypes = EnumSet.of(ProtocolTypes.TCP),
            connectionTimeoutMs = 15_000L,
            useDirectoryFetch = true,
        ),
        ConnectionProfile(
            label = "tcp-seeded",
            protocolTypes = EnumSet.of(ProtocolTypes.TCP),
            connectionTimeoutMs = 15_000L,
            useDirectoryFetch = false,
            serverListProviderFactory = ::createSeededServerListProvider,
        ),
        ConnectionProfile(
            label = "tcp+ws-directory",
            protocolTypes = EnumSet.of(ProtocolTypes.TCP, ProtocolTypes.WEB_SOCKET),
            connectionTimeoutMs = 15_000L,
            useDirectoryFetch = true,
        ),
    )
    private var activeConnectionProfile = primaryConnectionProfiles.first()

    suspend fun bootstrap() = withContext(Dispatchers.IO) {
        bootstrapJob?.join()
        val prefs = preferencesStore.snapshot()
        supportDiagnosticsStore.recordRecoveryEvent(
            action = "bootstrap_start",
            fields = mapOf(
                "rememberSession" to prefs.rememberSession.toString(),
                "hasSavedSession" to (prefs.accountName.isNotBlank() && prefs.refreshToken.isNotBlank()).toString(),
                "loginFeatureEnabled" to prefs.isLoginFeatureEnabled.toString(),
                "defaultGuestMode" to prefs.defaultGuestMode.toString(),
            ),
        )
        Log.i(
            LOG_TAG,
            "bootstrap remember=${prefs.rememberSession} hasSavedSession=${prefs.accountName.isNotBlank() && prefs.refreshToken.isNotBlank()}",
        )
        preferredConnectionProfileLabel = prefs.lastConnectionProfileLabel
        lastSuccessfulCdnHost = prefs.lastCdnHost
        lastSuccessfulCdnTransportDirect = prefs.lastCdnTransportDirect
        if (!prefs.isLoginFeatureEnabled) {
            _sessionState.value = SteamSessionState(
                status = SessionStatus.Idle,
                connectionRevision = connectionRevision,
            )
            return@withContext
        }
        if (prefs.accountName.isNotBlank()) {
            _sessionState.value = _sessionState.value.copy(
                account = SteamAccountSession(
                    accountName = prefs.accountName,
                    steamId64 = prefs.steamId64,
                ),
            )
        }

        if (prefs.defaultGuestMode) {
            _sessionState.value = _sessionState.value.copy(
                status = SessionStatus.Idle,
                isRestoring = false,
                errorMessage = null,
            )
            return@withContext
        }

        if (!prefs.rememberSession || prefs.refreshToken.isBlank()) return@withContext
        pendingRememberSession = prefs.rememberSession

        _sessionState.value = _sessionState.value.copy(
            status = SessionStatus.Connecting,
            isRestoring = true,
            errorMessage = null,
        )

        bootstrapJob = appScope.launch {
            runCatching {
                restoreSessionWithRetries(
                    accountName = prefs.accountName,
                    refreshToken = prefs.refreshToken,
                    clientId = prefs.clientId,
                    attempts = primaryConnectionProfiles.size,
                    restore = true,
                )
            }.onFailure { throwable ->
                supportDiagnosticsStore.recordRecoveryEvent(
                    action = "bootstrap_restore_failed",
                    fields = mapOf("message" to readableMessage(throwable)),
                )
                Log.w(LOG_TAG, "bootstrap restore failed", throwable)
                _sessionState.value = SteamSessionState(
                    status = SessionStatus.Idle,
                    account = _sessionState.value.account,
                    errorMessage = readableMessage(throwable),
                    connectionRevision = connectionRevision,
                )
            }
        }.also { job ->
            job.invokeOnCompletion {
                if (bootstrapJob === job) {
                    bootstrapJob = null
                }
            }
        }
        bootstrapJob?.join()
    }

    fun retryRestore() {
        appScope.launch {
            val previousAccount = _sessionState.value.account
            val prefs = preferencesStore.snapshot()
            supportDiagnosticsStore.recordRecoveryEvent(
                action = "retry_restore_requested",
                fields = mapOf(
                    "hasSavedSession" to (prefs.accountName.isNotBlank() && prefs.refreshToken.isNotBlank()).toString(),
                    "loginFeatureEnabled" to prefs.isLoginFeatureEnabled.toString(),
                ),
            )
            preferredConnectionProfileLabel = prefs.lastConnectionProfileLabel
            lastSuccessfulCdnHost = prefs.lastCdnHost
            lastSuccessfulCdnTransportDirect = prefs.lastCdnTransportDirect
            if (!prefs.isLoginFeatureEnabled) {
                Log.i(LOG_TAG, "retryRestore ignored because login feature is disabled")
                return@launch
            }
            Log.i(
                LOG_TAG,
                "retryRestore remember=${prefs.rememberSession} hasSavedSession=${prefs.accountName.isNotBlank() && prefs.refreshToken.isNotBlank()}",
            )
            if (!prefs.rememberSession || prefs.refreshToken.isBlank() || prefs.accountName.isBlank()) {
                _sessionState.value = SteamSessionState(
                    status = SessionStatus.Error,
                    account = previousAccount,
                    errorMessage = "当前没有可恢复的登录状态",
                    connectionRevision = connectionRevision,
                )
                return@launch
            }
            scheduleSessionRecovery("正在恢复 Steam 登录状态")
        }
    }

    fun onAppForegrounded() {
        appInForeground = true
        lastAppForegroundAtMs = System.currentTimeMillis()
        supportDiagnosticsStore.recordRecoveryEvent(
            action = "app_foregrounded",
            fields = recoveryContextFields(),
        )
        appScope.launch {
            if (interactiveLoginJob?.isActive == true) return@launch
            if (bootstrapJob?.isActive == true) return@launch
            if (recoveryJob?.isActive == true) return@launch

            val currentState = _sessionState.value

            val prefs = preferencesStore.snapshot()
            preferredConnectionProfileLabel = prefs.lastConnectionProfileLabel
            lastSuccessfulCdnHost = prefs.lastCdnHost
            lastSuccessfulCdnTransportDirect = prefs.lastCdnTransportDirect
            if (!prefs.isLoginFeatureEnabled) return@launch
            if (!hasRecoverableAuthenticatedCredentials(prefs)) {
                return@launch
            }
            if (hasUsableAuthenticatedDownloadSession()) {
                pendingForegroundRecovery = false
                return@launch
            }
            val canResumeSavedSession =
                pendingForegroundRecovery ||
                    currentState.isRestoring ||
                    currentState.status == SessionStatus.Authenticated ||
                    currentState.status == SessionStatus.Connecting ||
                    currentState.status == SessionStatus.Error
            if (!canResumeSavedSession) return@launch

            val now = System.currentTimeMillis()
            if (!pendingForegroundRecovery && now - lastForegroundRecoveryAtMs < FOREGROUND_RECOVERY_DEBOUNCE_MS) {
                return@launch
            }
            lastForegroundRecoveryAtMs = now
            pendingForegroundRecovery = false

            Log.i(
                LOG_TAG,
                "onAppForegrounded reconnect clientMissing=${steamClient == null} connected=$isConnected status=${currentState.status} restoring=${currentState.isRestoring}",
            )
            scheduleSessionRecovery("应用回到前台，正在恢复 Steam 连接")
        }
    }

    fun onAppBackgrounded() {
        appInForeground = false
        lastAppBackgroundAtMs = System.currentTimeMillis()
        supportDiagnosticsStore.recordRecoveryEvent(
            action = "app_backgrounded",
            fields = recoveryContextFields(),
        )
    }

    fun sessionRuntimeSnapshot(): SupportSessionRuntimeSnapshot {
        return SupportSessionRuntimeSnapshot(
            appInForeground = appInForeground,
            pendingForegroundRecovery = pendingForegroundRecovery,
            sessionConnected = isConnected,
            sessionClientReady = steamClient != null,
            hasSteamApps = steamApps != null,
            hasSteamContent = steamContent != null,
            recoveryInProgress = recoveryJob?.isActive == true,
            bootstrapInProgress = bootstrapJob?.isActive == true,
            interactiveLoginInProgress = interactiveLoginJob?.isActive == true,
            networkAvailable = networkAvailable,
            networkValidated = networkValidated,
            networkTransport = lastObservedNetworkTransport,
            lastAppForegroundAtMs = lastAppForegroundAtMs,
            lastAppBackgroundAtMs = lastAppBackgroundAtMs,
            lastConnectedAtMs = lastConnectedAtMs,
            lastDisconnectedAtMs = lastDisconnectedAtMs,
            lastNetworkAvailableAtMs = lastNetworkAvailableAtMs,
            lastNetworkLostAtMs = lastNetworkLostAtMs,
            lastNetworkChangedAtMs = lastNetworkChangedAtMs,
            lastDisconnectCategory = lastDisconnectCategory,
            lastDisconnectDetail = lastDisconnectDetail,
            lastDisconnectUserInitiated = lastDisconnectUserInitiated,
            lastDisconnectWhileForeground = lastDisconnectWhileForeground,
        )
    }

    private fun registerConnectivityMonitoring() {
        val manager = connectivityManager ?: return
        if (connectivityMonitoringRegistered) return
        runCatching {
            manager.registerDefaultNetworkCallback(connectivityCallback)
            connectivityMonitoringRegistered = true
        }.onFailure { throwable ->
            Log.w(LOG_TAG, "registerConnectivityMonitoring failed", throwable)
        }
    }

    private fun refreshObservedNetworkState() {
        val manager = connectivityManager ?: return
        val network = manager.activeNetwork
        if (network == null) {
            updateObservedNetworkState(
                available = false,
                capabilities = null,
                action = "network_snapshot_unavailable",
            )
            return
        }
        updateObservedNetworkState(
            available = true,
            capabilities = manager.getNetworkCapabilities(network),
            action = "network_snapshot",
        )
    }

    private fun updateObservedNetworkState(
        available: Boolean,
        capabilities: NetworkCapabilities?,
        action: String,
    ) {
        val transport = capabilities.toTransportSummary()
        val validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val stateChanged =
            networkAvailable != available ||
                networkValidated != validated ||
                lastObservedNetworkTransport != transport

        val now = System.currentTimeMillis()
        networkAvailable = available
        networkValidated = validated
        lastObservedNetworkTransport = transport
        lastNetworkChangedAtMs = now
        if (available) {
            lastNetworkAvailableAtMs = now
        } else {
            lastNetworkLostAtMs = now
        }
        if (!stateChanged) return

        supportDiagnosticsStore.recordRecoveryEvent(
            action = action,
            fields = recoveryContextFields(
                "networkAvailable" to available.toString(),
                "networkValidated" to validated?.toString(),
                "networkTransport" to transport,
            ),
        )
    }

    private fun recoveryContextFields(vararg extra: Pair<String, String?>): Map<String, String?> {
        val now = System.currentTimeMillis()
        return buildMap {
            put("sessionStatus", _sessionState.value.status.name)
            put("isRestoring", _sessionState.value.isRestoring.toString())
            put("appInForeground", appInForeground.toString())
            put("pendingForegroundRecovery", pendingForegroundRecovery.toString())
            put("sessionConnected", isConnected.toString())
            put("sessionClientReady", (steamClient != null).toString())
            put("networkAvailable", networkAvailable?.toString())
            put("networkValidated", networkValidated?.toString())
            put("networkTransport", lastObservedNetworkTransport)
            put("msSinceAppForeground", ageSince(now, lastAppForegroundAtMs))
            put("msSinceAppBackground", ageSince(now, lastAppBackgroundAtMs))
            put("msSinceConnected", ageSince(now, lastConnectedAtMs))
            put("msSinceDisconnected", ageSince(now, lastDisconnectedAtMs))
            put("msSinceNetworkAvailable", ageSince(now, lastNetworkAvailableAtMs))
            put("msSinceNetworkLost", ageSince(now, lastNetworkLostAtMs))
            put("lastDisconnectCategory", lastDisconnectCategory)
            put("lastDisconnectDetail", lastDisconnectDetail)
            extra.forEach { (key, value) -> put(key, value) }
        }
    }

    private fun ageSince(now: Long, timestampMs: Long?): String? {
        return timestampMs?.let { (now - it).coerceAtLeast(0L).toString() }
    }

    private fun NetworkCapabilities?.toTransportSummary(): String? {
        if (this == null) return null
        val transports = buildList {
            if (hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
            if (hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("WIFI")
            if (hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("CELLULAR")
            if (hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ETHERNET")
            if (hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("BLUETOOTH")
        }
        return if (transports.isEmpty()) "OTHER" else transports.joinToString("+")
    }

    private fun classifyDisconnectCategory(
        userInitiated: Boolean,
        interactiveLoginInProgress: Boolean,
        restoring: Boolean,
    ): String {
        val now = System.currentTimeMillis()
        return when {
            manualLogout || userInitiated -> "user_initiated"
            interactiveLoginInProgress -> "interactive_login_disconnect"
            restoring -> "restoring_disconnect"
            !appInForeground -> {
                when {
                    lastNetworkLostAtMs != null && now - lastNetworkLostAtMs!! <= 15_000L -> "background_network_loss"
                    lastNetworkChangedAtMs != null && now - lastNetworkChangedAtMs!! <= 15_000L -> "background_network_change"
                    else -> "background_disconnect"
                }
            }
            lastNetworkLostAtMs != null && now - lastNetworkLostAtMs!! <= 15_000L -> "foreground_network_loss"
            lastNetworkChangedAtMs != null && now - lastNetworkChangedAtMs!! <= 15_000L -> "foreground_network_change"
            else -> "foreground_disconnect"
        }
    }

    fun prewarmAnonymousDownloadAccess() {
        appScope.launch {
            val shouldRun = anonymousContentSessionMutex.withLock {
                val now = System.currentTimeMillis()
                if (
                    anonymousPrewarmInFlight ||
                    now - lastAnonymousPrewarmAtMs < ANONYMOUS_PREWARM_COOLDOWN_MS
                ) {
                    false
                } else {
                    anonymousPrewarmInFlight = true
                    true
                }
            }
            if (!shouldRun) return@launch
            supportDiagnosticsStore.incrementCounter("anonymous_prewarm_invocations")
            supportDiagnosticsStore.recordRecoveryEvent(action = "anonymous_prewarm_requested")
            runCatching {
                prewarmAnonymousContentSession()
                lastAnonymousPrewarmAtMs = System.currentTimeMillis()
            }.onFailure { throwable ->
                Log.w(LOG_TAG, "prewarmAnonymousDownloadAccess failed", throwable)
            }
            anonymousContentSessionMutex.withLock {
                anonymousPrewarmInFlight = false
            }
        }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        supportDiagnosticsStore.recordRecoveryEvent(action = "logout")
        Log.i(LOG_TAG, "logout")
        manualLogout = true
        pendingAccountName = ""
        pendingRefreshToken = ""
        disconnectCurrentSession()
        preferencesStore.clearSession()
        _sessionState.value = SteamSessionState(
            status = SessionStatus.Idle,
            connectionRevision = connectionRevision,
        )
    }

    suspend fun clearOwnedGamesCache() = withContext(Dispatchers.IO) {
        ownedGamesCacheSteamId64 = 0L
        ownedGamesCache = null
        preferencesStore.clearOwnedGamesSnapshot()
    }

    suspend fun switchSavedAccount(accountKey: String) = withContext(Dispatchers.IO) {
        supportDiagnosticsStore.recordRecoveryEvent(
            action = "switch_saved_account",
            fields = mapOf("accountKeyPresent" to accountKey.isNotBlank().toString()),
        )
        Log.i(LOG_TAG, "switchSavedAccount requested")
        check(preferencesStore.snapshot().isLoginFeatureEnabled) { "已在设置中关闭登录功能" }
        val savedAccount = preferencesStore.activateSavedAccount(accountKey)
            ?: error("未找到可恢复的已保存账号")
        manualLogout = false
        preferencesStore.snapshot().let { prefs ->
            preferredConnectionProfileLabel = prefs.lastConnectionProfileLabel
            lastSuccessfulCdnHost = prefs.lastCdnHost
            lastSuccessfulCdnTransportDirect = prefs.lastCdnTransportDirect
        }
        disconnectCurrentSession()
        pendingRememberSession = savedAccount.rememberSession
        _sessionState.value = SteamSessionState(
            status = SessionStatus.Connecting,
            account = SteamAccountSession(savedAccount.accountName, savedAccount.steamId64),
            isRestoring = true,
            connectionRevision = connectionRevision,
        )
        runCatching {
            restoreSessionWithRetries(
                accountName = savedAccount.accountName,
                refreshToken = savedAccount.refreshToken,
                clientId = savedAccount.clientId,
                attempts = primaryConnectionProfiles.size,
                restore = true,
            )
        }.onFailure { throwable ->
            Log.w(LOG_TAG, "switchSavedAccount failed", throwable)
            _sessionState.value = SteamSessionState(
                status = SessionStatus.Error,
                account = SteamAccountSession(savedAccount.accountName, savedAccount.steamId64),
                errorMessage = readableMessage(throwable),
                connectionRevision = connectionRevision,
            )
            throw throwable
        }
    }

    private suspend fun disconnectCurrentSession() {
        interactiveLoginJob?.cancel()
        interactiveLoginJob = null
        bootstrapJob?.cancel()
        bootstrapJob = null
        recoveryJob?.cancel()
        recoveryJob = null
        pendingAuthCode?.cancel(true)
        pendingAuthCode = null
        bufferedAuthCode = null
        lastSubmittedAuthCode = null
        clearSessionCaches()
        steamUser?.logOff()
        steamClient?.disconnect()
        teardownClient()
        connectionRevision = 0L
    }

    @WorkerThread
    suspend fun loadOwnedGames(forceRefresh: Boolean = false): List<OwnedGame> = withContext(Dispatchers.IO) {
        Log.d(LOG_TAG, "loadOwnedGames start")
        supportDiagnosticsStore.incrementCounter("steam_rpc_owned_games_requests")
        val prefs = preferencesStore.snapshot()
        check(prefs.isLoginFeatureEnabled) { "已在设置中关闭登录功能" }
        check(prefs.isOwnedGamesDisplayEnabled) { "已在设置中关闭“用户已购买标识展示”" }
        val cachedSteamId64 = _sessionState.value.account?.steamId64 ?: 0L
        if (!forceRefresh && cachedSteamId64 > 0L) {
            ownedGamesCache
                ?.takeIf { ownedGamesCacheSteamId64 == cachedSteamId64 && it.isFresh(OWNED_GAMES_CACHE_MS) }
                ?.let { cached ->
                    Log.d(LOG_TAG, "loadOwnedGames hit cache count=${cached.value.size}")
                    return@withContext cached.value
                }
        }
        ensureAuthenticated()
        val steamId64 = currentSteamId64()
        if (!forceRefresh) {
            ownedGamesCache
                ?.takeIf { ownedGamesCacheSteamId64 == steamId64 && it.isFresh(OWNED_GAMES_CACHE_MS) }
                ?.let { cached ->
                    Log.d(LOG_TAG, "loadOwnedGames hit cache after auth count=${cached.value.size}")
                    return@withContext cached.value
                }
        }
        val ownedGames = loadOwnedGamesFromPlayerService()
        val mergedGames = linkedMapOf<Int, OwnedGame>()

        ownedGames.forEach { game ->
            mergedGames[game.appId] = game
        }

        runCatching {
            loadFamilySharedGames(existingOwnedAppIds = mergedGames.keys)
        }.getOrDefault(emptyList()).forEach { game ->
            mergedGames.putIfAbsent(game.appId, game)
        }

        mergedGames.values.sortedBy { it.name.lowercase() }
            .also {
                ownedGamesCacheSteamId64 = steamId64
                ownedGamesCache = CachedValue(it)
                persistOwnedGamesSnapshot(steamId64, it)
                Log.i(LOG_TAG, "loadOwnedGames success total=${it.size}")
            }
    }

    suspend fun loadOwnedGamesSnapshot(): List<OwnedGame> = withContext(Dispatchers.IO) {
        val prefs = preferencesStore.snapshot()
        if (!prefs.isLoginFeatureEnabled || !prefs.isOwnedGamesDisplayEnabled) {
            return@withContext emptyList()
        }
        val snapshot = preferencesStore.loadOwnedGamesSnapshot()
        val expectedSteamId64 = _sessionState.value.account?.steamId64
            ?.takeIf { it > 0L }
            ?: prefs.steamId64
        if (snapshot.payloadJson.isBlank() || snapshot.steamId64 <= 0L) return@withContext emptyList()
        if (expectedSteamId64 > 0L && snapshot.steamId64 != expectedSteamId64) {
            return@withContext emptyList()
        }
        if (System.currentTimeMillis() - snapshot.savedAtMs > OWNED_GAMES_SNAPSHOT_MAX_AGE_MS) {
            return@withContext emptyList()
        }
        runCatching {
            json.decodeFromString<List<OwnedGame>>(snapshot.payloadJson)
        }.getOrElse { throwable ->
            Log.w(LOG_TAG, "loadOwnedGamesSnapshot failed to decode", throwable)
            emptyList()
        }
    }

    @WorkerThread
    suspend fun loadGameDetails(appId: Int): GameDetails = withContext(Dispatchers.IO) {
        Log.d(LOG_TAG, "loadGameDetails appId=$appId")
        val url = STEAM_STORE_APP_DETAILS_URL.toHttpUrl().newBuilder()
            .addQueryParameter("appids", appId.toString())
            .addQueryParameter("l", "schinese")
            .build()
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        val payload = okHttpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Steam 商店信息请求失败：HTTP ${response.code}" }
            response.body?.string().orEmpty()
        }

        val appNode = json.parseToJsonElement(payload)
            .jsonObject[appId.toString()]
            ?.jsonObject
            ?: error("未找到该游戏的商店详情")
        check(appNode["success"]?.jsonPrimitive?.booleanOrNull == true) { "Steam 商店未返回有效详情" }

        val data = appNode["data"]?.jsonObject ?: error("Steam 商店详情为空")
        GameDetails(
            appId = appId,
            title = data["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            shortDescription = htmlToPlainText(data["short_description"]?.jsonPrimitive?.contentOrNull.orEmpty()),
            about = htmlToPlainText(data["about_the_game"]?.jsonPrimitive?.contentOrNull.orEmpty()),
            headerImageUrl = data["header_image"]?.jsonPrimitive?.contentOrNull,
            developers = data["developers"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
            publishers = data["publishers"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
            genres = data["genres"]?.jsonArray?.mapNotNull {
                it.jsonObject["description"]?.jsonPrimitive?.contentOrNull
            }.orEmpty(),
        )
    }

    @WorkerThread
    suspend fun loadWorkshopExplorePage(page: Int): WorkshopGamePage = withContext(Dispatchers.IO) {
        val normalizedPage = page.coerceAtLeast(1)
        workshopGamePageCache[normalizedPage]
            ?.takeIf { it.isFresh(WORKSHOP_GAME_DISCOVERY_CACHE_MS) }
            ?.let { cached -> return@withContext cached.value }

        val start = (normalizedPage - 1) * WORKSHOP_EXPLORE_PAGE_SIZE
        val request = Request.Builder()
            .url(
                STEAM_WORKSHOP_EXPLORE_AJAX_URL.toHttpUrl().newBuilder()
                    .addQueryParameter("query", "MostRecent")
                    .addQueryParameter("start", start.toString())
                    .addQueryParameter("count", WORKSHOP_EXPLORE_PAGE_SIZE.toString())
                    .build(),
            )
            .header("User-Agent", "Mozilla/5.0")
            .header("X-Requested-With", "XMLHttpRequest")
            .get()
            .build()
        val payload = okHttpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "读取公开工坊游戏失败：HTTP ${response.code}" }
            response.body?.string().orEmpty()
        }
        val parsed = json.decodeFromString<WorkshopExploreAjaxResponse>(payload)
        check(parsed.success) { "公开工坊游戏分页返回无效结果" }
        val html = parsed.resultsHtml

        val items = WORKSHOP_GAME_ENTRY_REGEX.findAll(html).mapNotNull { match ->
            val appId = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val previewUrl = match.groupValues[2].takeIf { it.isNotBlank() }
            val capsuleUrl = match.groupValues[3].takeIf { it.isNotBlank() }
            val title = decodeHtml(match.groupValues[4]).ifBlank { "Workshop App $appId" }
            val itemCount = WORKSHOP_ITEM_COUNT_REGEX.find(match.groupValues[5])
                ?.groupValues
                ?.getOrNull(1)
                ?.replace(",", "")
                ?.toIntOrNull()
            WorkshopGameEntry(
                appId = appId,
                name = title,
                capsuleUrl = capsuleUrl,
                previewUrl = previewUrl,
                workshopItemCount = itemCount,
            )
        }.toList()

        val totalCount = parsed.totalCount ?: items.size

        return@withContext WorkshopGamePage(
            items = items,
            page = normalizedPage,
            totalCount = totalCount,
            hasPrevious = normalizedPage > 1,
            hasNext = start + items.size < totalCount,
        ).also { parsed ->
            workshopGamePageCache[normalizedPage] = CachedValue(parsed)
        }
    }

    @WorkerThread
    suspend fun searchWorkshopGames(query: String): List<WorkshopGameEntry> = withContext(Dispatchers.IO) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return@withContext emptyList()
        val cacheKey = normalizedQuery.lowercase()
        workshopGameSearchCache[cacheKey]
            ?.takeIf { it.isFresh(WORKSHOP_GAME_DISCOVERY_CACHE_MS) }
            ?.let { cached -> return@withContext cached.value }

        val request = Request.Builder()
            .url("$STEAM_WORKSHOP_SEARCH_URL?searchText=${Uri.encode(normalizedQuery)}")
            .header("User-Agent", "Mozilla/5.0")
            .get()
            .build()
        val payload = okHttpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "搜索公开工坊游戏失败：HTTP ${response.code}" }
            response.body?.string().orEmpty()
        }

        val results = runCatching {
            json.decodeFromString<List<WorkshopSearchGameDto>>(payload)
        }.getOrDefault(emptyList())
            .map { dto ->
                WorkshopGameEntry(
                    appId = dto.appId,
                    name = dto.name,
                    capsuleUrl = dto.logo,
                    previewUrl = dto.icon,
                    workshopItemCount = null,
                )
            }
            .distinctBy(WorkshopGameEntry::appId)

        workshopGameSearchCache[cacheKey] = CachedValue(results)
        return@withContext results
    }

    @WorkerThread
    suspend fun loadWorkshopBrowsePage(
        appId: Int,
        query: WorkshopBrowseQuery,
        forceRefresh: Boolean = false,
    ): WorkshopBrowsePage = withContext(Dispatchers.IO) {
        Log.d(LOG_TAG, "loadWorkshopBrowsePage appId=$appId")
        val cacheKey = workshopBrowseCacheKey(appId, query)
        if (!forceRefresh) {
            workshopBrowsePageCache[cacheKey]
                ?.takeIf { it.isFresh(WORKSHOP_BROWSE_CACHE_MS) }
                ?.let { cached ->
                    Log.d(LOG_TAG, "loadWorkshopBrowsePage hit cache appId=$appId")
                    return@withContext cached.value
                }
        }

        val url = buildWorkshopBrowseUrl(appId, query)
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        val html = runCatching {
            okHttpClient.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Steam 创意工坊页面请求失败：HTTP ${response.code}" }
                response.body?.string().orEmpty()
            }
        }.getOrElse { throwable ->
            workshopBrowsePageCache[cacheKey]
                ?.takeIf { it.isFresh(WORKSHOP_BROWSE_CACHE_MS * 4) }
                ?.let { cached ->
                    Log.w(LOG_TAG, "loadWorkshopBrowsePage fallback cache appId=$appId", throwable)
                    return@withContext cached.value
                }
            throw throwable
        }
        parseWorkshopBrowsePage(appId = appId, query = query, html = html).also { parsed ->
            workshopBrowsePageCache[cacheKey] = CachedValue(parsed)
        }
    }

    @WorkerThread
    suspend fun loadAuthenticatedWorkshopQueryPage(
        appId: Int,
        query: WorkshopBrowseQuery,
        forceRefresh: Boolean = false,
    ): WorkshopBrowsePage = withContext(Dispatchers.IO) {
        Log.d(LOG_TAG, "loadAuthenticatedWorkshopQueryPage appId=$appId")
        ensureAuthenticated()
        val steamId64 = currentSteamId64()
        val normalizedSearchText = query.searchText.trim()
        if (normalizedSearchText.isBlank()) {
            return@withContext WorkshopBrowsePage(
                items = emptyList(),
                totalCount = 0,
                page = query.page.coerceAtLeast(1),
                hasMore = false,
                sectionOptions = emptyList(),
                sortOptions = emptyList(),
                periodOptions = emptyList(),
                tagGroups = emptyList(),
                supportsIncompatibleFilter = false,
            )
        }
        val normalizedQuery = query.copy(
            searchText = normalizedSearchText,
            page = query.page.coerceAtLeast(1),
            pageSize = WorkshopBrowseQuery.normalizePageSize(query.pageSize),
        )
        val cacheKey = workshopAuthenticatedQueryCacheKey(
            steamId64 = steamId64,
            appId = appId,
            query = normalizedQuery,
        )
        if (!forceRefresh) {
            workshopAuthenticatedQueryPageCache[cacheKey]
                ?.takeIf { it.isFresh(WORKSHOP_AUTHENTICATED_QUERY_CACHE_MS) }
                ?.let { cached ->
                    Log.d(LOG_TAG, "loadAuthenticatedWorkshopQueryPage hit cache appId=$appId")
                    return@withContext cached.value
                }
        }

        val request = SteammessagesPublishedfileSteamclient.CPublishedFile_QueryFiles_Request.newBuilder().apply {
            queryType = EPublishedFileQueryType.RankedByTextSearch.code()
            page = normalizedQuery.page
            numperpage = normalizedQuery.pageSize
            this.appid = appId
            searchText = normalizedQuery.searchText
            filetype = EWorkshopFileType.Community.code()
            returnTags = true
            returnPreviews = true
            returnChildren = true
            returnShortDescription = true
            returnMetadata = true
            returnDetails = true
            stripDescriptionBbcode = true
        }.build()

        val response = publishedFileService?.queryFiles(request)?.await()
            ?: error("Steam workshop service unavailable")
        supportDiagnosticsStore.incrementCounter("steam_rpc_query_files_requests")
        val details = response.body.publishedfiledetailsList
            .filter { it.result == EResult.OK.code() }
        val totalCount = if (response.body.hasTotal()) response.body.total else details.size
        val nextCursor = response.body.nextCursor.takeIf(String::isNotBlank)
        val parsed = WorkshopBrowsePage(
            items = details.map(::mapPublishedFileDetails),
            totalCount = totalCount,
            page = normalizedQuery.page,
            hasMore = nextCursor != null || normalizedQuery.page * normalizedQuery.pageSize < totalCount,
            sectionOptions = emptyList(),
            sortOptions = emptyList(),
            periodOptions = emptyList(),
            tagGroups = emptyList(),
            supportsIncompatibleFilter = false,
        )
        workshopAuthenticatedQueryPageCache[cacheKey] = CachedValue(parsed)
        parsed
    }

    @WorkerThread
    suspend fun loadSubscribedWorkshopPage(
        appId: Int,
        page: Int,
        pageSize: Int,
        forceRefresh: Boolean = false,
    ): WorkshopBrowsePage = withContext(Dispatchers.IO) {
        Log.d(LOG_TAG, "loadSubscribedWorkshopPage appId=$appId page=$page pageSize=$pageSize")
        val prefs = preferencesStore.snapshot()
        check(prefs.isLoginFeatureEnabled) { "已在设置中关闭登录功能" }
        check(prefs.isSubscriptionDisplayEnabled) { "已在设置中关闭“用户已订阅展示”" }
        ensureAuthenticated()
        val steamId64 = currentSteamId64()
        val normalizedPage = page.coerceAtLeast(1)
        val normalizedPageSize = WorkshopBrowseQuery.normalizePageSize(pageSize)
        val cacheKey = workshopSubscriptionsCacheKey(
            steamId64 = steamId64,
            appId = appId,
            page = normalizedPage,
            pageSize = normalizedPageSize,
        )
        if (!forceRefresh) {
            workshopSubscriptionsPageCache[cacheKey]
                ?.takeIf { it.isFresh(WORKSHOP_SUBSCRIPTIONS_CACHE_MS) }
                ?.let { cached ->
                    Log.d(LOG_TAG, "loadSubscribedWorkshopPage hit cache appId=$appId page=$normalizedPage")
                    return@withContext cached.value
                }
        }

        val request = SteammessagesPublishedfileSteamclient.CPublishedFile_GetUserFiles_Request.newBuilder().apply {
            steamid = steamId64
            this.appid = appId
            this.page = normalizedPage
            numperpage = normalizedPageSize
            type = WORKSHOP_USER_FILES_TYPE_SUBSCRIPTIONS
            sortmethod = "lastupdated"
            setReturnTags(true)
            setReturnPreviews(true)
            setReturnChildren(true)
            setReturnShortDescription(true)
        }.build()

        val response = publishedFileService?.getUserFiles(request)?.await()
            ?: error("Steam workshop service unavailable")
        supportDiagnosticsStore.incrementCounter("steam_rpc_subscriptions_requests")
        val details = response.body.publishedfiledetailsList
            .filter { it.result == EResult.OK.code() }
        val totalCount = if (response.body.hasTotal()) response.body.total else details.size
        val parsed = WorkshopBrowsePage(
            items = details.map(::mapPublishedFileDetails),
            totalCount = totalCount,
            page = normalizedPage,
            hasMore = normalizedPage * normalizedPageSize < totalCount,
            sectionOptions = listOf(
                WorkshopBrowseSectionOption(
                    key = WorkshopBrowseQuery.SECTION_MY_SUBSCRIPTIONS,
                    label = "我的订阅",
                ),
            ),
            sortOptions = emptyList(),
            periodOptions = emptyList(),
            tagGroups = emptyList(),
            supportsIncompatibleFilter = false,
        )
        workshopSubscriptionsPageCache[cacheKey] = CachedValue(parsed)
        parsed
    }

    @WorkerThread
    suspend fun resolveWorkshopItem(publishedFileId: Long): WorkshopItem = withContext(Dispatchers.IO) {
        Log.d(LOG_TAG, "resolveWorkshopItem publishedFileId=$publishedFileId")
        resolveWorkshopItems(listOf(publishedFileId)).firstOrNull()
            ?: error("未找到该创意工坊条目")
    }

    suspend fun resolveWorkshopItems(publishedFileIds: Collection<Long>): List<WorkshopItem> = withContext(Dispatchers.IO) {
        val normalizedIds = publishedFileIds
            .filter { it > 0L }
            .distinct()
        if (normalizedIds.isEmpty()) return@withContext emptyList()

        Log.d(LOG_TAG, "resolveWorkshopItems count=${normalizedIds.size}")
        val cachedById = normalizedIds.associateWithNotNull { publishedFileId ->
            getCachedWorkshopItem(workshopItemCache, publishedFileId)
        }
        val missingIds = normalizedIds.filter { it !in cachedById }
        if (missingIds.isNotEmpty()) {
            loadPublishedFileDetailsPublic(missingIds)
                .forEach { item ->
                    workshopItemCache[item.publishedFileId] = CachedValue(item)
                }

            if (normalizedIds.size <= 6) {
                val itemsNeedingDetailPageFallback = missingIds.mapNotNull { publishedFileId ->
                    getCachedWorkshopItem(workshopItemCache, publishedFileId)
                }.filter { item -> item.needsWorkshopDetailPageFallback() }

                if (itemsNeedingDetailPageFallback.isNotEmpty()) {
                    supervisorScope {
                        itemsNeedingDetailPageFallback
                            .map { item ->
                                async {
                                    loadWorkshopDetailPageContent(item)?.let { detailPage ->
                                        item.mergeWorkshopDetailPage(detailPage)
                                    }
                                }
                            }
                            .forEach { deferred ->
                                deferred.await()?.let { item ->
                                    workshopItemCache[item.publishedFileId] = CachedValue(item)
                                }
                            }
                    }
                }
            }
        }

        normalizedIds.mapNotNull { publishedFileId ->
            getCachedWorkshopItem(workshopItemCache, publishedFileId)
                ?: cachedById[publishedFileId]
        }
    }

    @WorkerThread
    suspend fun resolveWorkshopItemForDownload(publishedFileId: Long): WorkshopItem = withContext(Dispatchers.IO) {
        Log.d(LOG_TAG, "resolveWorkshopItemForDownload publishedFileId=$publishedFileId")
        resolveWorkshopItemsForDownload(listOf(publishedFileId)).firstOrNull()
            ?: error("未找到该创意工坊条目")
    }

    suspend fun resolveWorkshopItemsForDownload(
        publishedFileIds: Collection<Long>,
    ): List<WorkshopItem> = withContext(Dispatchers.IO) {
        val normalizedIds = publishedFileIds
            .filter { it > 0L }
            .distinct()
        if (normalizedIds.isEmpty()) return@withContext emptyList()

        Log.d(LOG_TAG, "resolveWorkshopItemsForDownload count=${normalizedIds.size}")
        val cachedById = normalizedIds.associateWithNotNull { publishedFileId ->
            getCachedWorkshopItem(downloadWorkshopItemCache, publishedFileId)
                ?: getCachedWorkshopItem(workshopItemCache, publishedFileId)
        }
        val missingIds = normalizedIds.filter { it !in cachedById }
        if (missingIds.isNotEmpty()) {
            resolveWorkshopItems(missingIds).forEach { item ->
                downloadWorkshopItemCache[item.publishedFileId] = CachedValue(item)
            }

            val unresolvedIds = missingIds.filter { publishedFileId ->
                getCachedWorkshopItem(downloadWorkshopItemCache, publishedFileId) == null &&
                    getCachedWorkshopItem(workshopItemCache, publishedFileId) == null
            }
            if (unresolvedIds.isNotEmpty() && canUseAuthenticatedWorkshopFallback()) {
                loadPublishedFileDetailsAuthenticated(unresolvedIds)
                    .map(::mapPublishedFileDetails)
                    .forEach { item ->
                        downloadWorkshopItemCache[item.publishedFileId] = CachedValue(item)
                    }
            }
        }

        normalizedIds.mapNotNull { publishedFileId ->
            getCachedWorkshopItem(downloadWorkshopItemCache, publishedFileId)
                ?: getCachedWorkshopItem(workshopItemCache, publishedFileId)
                ?: cachedById[publishedFileId]
        }
    }

    @WorkerThread
    suspend fun downloadWorkshopItem(
        item: WorkshopItem,
        stagingTaskId: String,
        targetTreeUri: String?,
        downloadFolderName: String?,
        rootName: String,
        existingRootRef: String? = null,
        downloadAuthMode: DownloadAuthMode = DownloadAuthMode.Authenticated,
        boundAccountKeyHash: String? = null,
        onStorageRootResolved: suspend (String) -> Unit = {},
        shouldPause: suspend () -> Boolean = { false },
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long) -> Unit,
        onPhaseChanged: suspend (String?) -> Unit = {},
        onRuntimeInfoChanged: suspend (
            routeLabel: String?,
            transportLabel: String?,
            endpointLabel: String?,
            sourceAddress: String?,
            attemptCount: Int?,
            chunkConcurrency: Int?,
            lastFailure: String?,
        ) -> Unit = { _, _, _, _, _, _, _ -> },
    ): String = withContext(Dispatchers.IO) {
        Log.i(
            LOG_TAG,
            "downloadWorkshopItem publishedFileId=${item.publishedFileId} appId=${item.appId} manifest=${item.contentManifestId} direct=${item.canDirectDownload} authMode=$downloadAuthMode",
        )

        val manifestId = item.contentManifestId.takeIf { it > 0L }
            ?: error("该条目没有可下载的 Steam 内容 manifest")
        val appId = item.appId.takeIf { it > 0 } ?: error("无效的 Workshop AppID")
        val prefs = preferencesStore.snapshot()
        val currentSessionBindingHash = buildAccountBindingHash(
            accountName = _sessionState.value.account?.accountName,
            steamId64 = _sessionState.value.account?.steamId64,
        )
        val persistedBindingHash = buildAccountBindingHash(
            accountName = prefs.accountName,
            steamId64 = prefs.steamId64,
        )
        val boundAccountHashMatched =
            boundAccountKeyHash.isNullOrBlank() ||
                boundAccountKeyHash == currentSessionBindingHash ||
                boundAccountKeyHash == persistedBindingHash
        val canUseAuthenticatedPath =
            prefs.isLoginFeatureEnabled &&
                prefs.isLoggedInDownloadEnabled &&
                boundAccountHashMatched &&
                hasUsableAuthenticatedDownloadSession()
        val canAttemptAuthenticatedPath =
            prefs.isLoginFeatureEnabled &&
                prefs.isLoggedInDownloadEnabled &&
                boundAccountHashMatched &&
                (
                    canUseAuthenticatedPath ||
                        hasRecoverableAuthenticatedCredentials(prefs)
                )
        val allowAuthenticatedFallback = canAttemptAuthenticatedPath &&
            (
                downloadAuthMode == DownloadAuthMode.Authenticated ||
                    (
                        downloadAuthMode == DownloadAuthMode.Auto &&
                            prefs.allowAuthenticatedDownloadFallback
                        )
                )
        val anonymousFirst = when (downloadAuthMode) {
            DownloadAuthMode.Anonymous -> true
            DownloadAuthMode.Authenticated -> !canUseAuthenticatedPath
            DownloadAuthMode.Auto -> true
        }
        supportDiagnosticsStore.recordDownloadDecision(
            SupportDownloadDecisionSnapshot(
                taskId = stagingTaskId,
                timestampMs = System.currentTimeMillis(),
                publishedFileId = item.publishedFileId,
                appId = appId,
                downloadAuthMode = downloadAuthMode.name,
                anonymousFirst = anonymousFirst,
                canUseAuthenticatedPath = canUseAuthenticatedPath,
                allowAuthenticatedFallback = allowAuthenticatedFallback,
                sessionStatus = _sessionState.value.status.name,
                sessionConnected = isConnected,
                sessionClientReady = steamClient != null && steamApps != null && steamContent != null,
                recoveryInProgress = recoveryJob?.isActive == true || _sessionState.value.isRestoring,
                isLoginFeatureEnabled = prefs.isLoginFeatureEnabled,
                isLoggedInDownloadEnabled = prefs.isLoggedInDownloadEnabled,
                preferAnonymousDownloads = prefs.preferAnonymousDownloads,
                allowAuthenticatedDownloadFallbackSetting = prefs.allowAuthenticatedDownloadFallback,
                boundAccountHashMatched = boundAccountHashMatched,
            ),
        )
        supportDiagnosticsStore.recordDownloadEvent(
            action = "download_item_start",
            taskId = stagingTaskId,
            fields = mapOf(
                "publishedFileId" to item.publishedFileId.toString(),
                "appId" to appId.toString(),
                "authMode" to downloadAuthMode.name,
                "anonymousFirst" to anonymousFirst.toString(),
                "allowAuthenticatedFallback" to allowAuthenticatedFallback.toString(),
                "sessionConnected" to isConnected.toString(),
                "sessionClientReady" to (steamClient != null && steamApps != null && steamContent != null).toString(),
                "recoveryInProgress" to (recoveryJob?.isActive == true || _sessionState.value.isRestoring).toString(),
            ),
        )

        val storageRoot = createWorkshopStorageRoot(
            stagingTaskId = stagingTaskId,
            rootName = rootName,
            treeUri = targetTreeUri,
            downloadFolderName = downloadFolderName,
            existingRootRef = existingRootRef,
        )
        val performanceProfile = effectiveDownloadPerformanceProfile(
            requested = normalizeDownloadChunkConcurrency(prefs.downloadChunkConcurrency),
            mode = prefs.downloadPerformanceMode,
        )
        if (performanceProfile.chunkConcurrency < performanceProfile.requestedChunkConcurrency) {
            Log.w(
                LOG_TAG,
                "downloadWorkshopItem clamp chunk concurrency requested=${performanceProfile.requestedChunkConcurrency} effective=${performanceProfile.chunkConcurrency} mode=${performanceProfile.mode} heapBudgetMb=${performanceProfile.heapBudgetMb} availableHeapMb=${performanceProfile.availableHeapMb} lowRam=${performanceProfile.isLowRamDevice}",
            )
        }
        supportDiagnosticsStore.recordPerformanceSnapshot(
            SupportPerformanceSnapshot(
                taskId = stagingTaskId,
                timestampMs = System.currentTimeMillis(),
                mode = performanceProfile.mode.name,
                requestedChunkConcurrency = performanceProfile.requestedChunkConcurrency,
                effectiveChunkConcurrency = performanceProfile.chunkConcurrency,
                maxRoutesPerTransport = performanceProfile.maxRoutesPerTransport,
                maxActiveRoutes = performanceProfile.maxActiveRoutes,
                dispatcherMaxRequests = when (performanceProfile.mode) {
                    DownloadPerformanceMode.Auto ->
                        (performanceProfile.chunkConcurrency * 2).coerceAtLeast(performanceProfile.dispatcherRequestFloor)
                    DownloadPerformanceMode.Compatibility ->
                        (performanceProfile.chunkConcurrency + 1).coerceAtLeast(performanceProfile.dispatcherRequestFloor)
                },
                dispatcherMaxRequestsPerHost = when (performanceProfile.mode) {
                    DownloadPerformanceMode.Auto ->
                        (performanceProfile.chunkConcurrency + 2).coerceAtLeast(performanceProfile.dispatcherPerHostFloor)
                    DownloadPerformanceMode.Compatibility ->
                        performanceProfile.chunkConcurrency.coerceAtLeast(performanceProfile.dispatcherPerHostFloor)
                },
                heapBudgetMb = performanceProfile.heapBudgetMb,
                availableHeapMb = performanceProfile.availableHeapMb,
                isLowRamDevice = performanceProfile.isLowRamDevice,
            ),
        )
        val transportModes = prioritizedTransportModes(prefs.cdnTransportPreference)
        onStorageRootResolved(storageRoot.resumeRef)
        val failures = mutableListOf<String>()

        if (anonymousFirst) {
            runCatching {
                val anonymousAccess = runDownloadStage(
                    taskId = stagingTaskId,
                    stage = "anonymous_content_access",
                    timeoutMs = DOWNLOAD_ANONYMOUS_CONTENT_ACCESS_TIMEOUT_MS,
                    timeoutMessage = "获取匿名 Steam 内容访问超时，请检查当前网络后重试",
                    fields = mapOf(
                        "publishedFileId" to item.publishedFileId.toString(),
                        "appId" to appId.toString(),
                        "manifestId" to manifestId.toString(),
                    ),
                ) {
                    loadWorkshopContentAccessAnonymously(
                        appId = appId,
                        manifestId = manifestId,
                        depotId = item.appId.takeIf { it > 0 } ?: appId,
                    )
                }
                downloadWorkshopItemViaContentAccess(
                    taskId = stagingTaskId,
                    item = item,
                    depotId = item.appId.takeIf { it > 0 } ?: appId,
                    manifestId = manifestId,
                    contentAccess = anonymousAccess,
                    storageRoot = storageRoot,
                    performanceProfile = performanceProfile,
                    transportModes = transportModes,
                    cdnPoolPreference = prefs.cdnPoolPreference,
                    shouldPause = shouldPause,
                    onProgress = onProgress,
                    onRuntimeInfoChanged = onRuntimeInfoChanged,
                )
            }.onSuccess {
                return@withContext storageRoot.exportResultUri(
                    treeUri = targetTreeUri,
                    downloadFolderName = downloadFolderName,
                    onPhaseChanged = onPhaseChanged,
                )
            }.onFailure { throwable ->
                if (throwable.isPauseSignal()) throw throwable
                failures += "匿名 Steam 内容下载失败：${throwable.message ?: "未知错误"}"
            }
        }

        if (allowAuthenticatedFallback) {
            runCatching {
                val authenticatedDepotId = runDownloadStage(
                    taskId = stagingTaskId,
                    stage = "authenticated_depot_lookup",
                    timeoutMs = DOWNLOAD_WORKSHOP_DEPOT_LOOKUP_TIMEOUT_MS,
                    timeoutMessage = "获取 Workshop depot 信息超时，请稍后重试",
                    fields = mapOf(
                        "publishedFileId" to item.publishedFileId.toString(),
                        "appId" to appId.toString(),
                        "manifestId" to manifestId.toString(),
                    ),
                ) {
                    fetchWorkshopDepotId(appId)
                }
                val authenticatedAccess = loadWorkshopContentAccessAuthenticated(
                    taskId = stagingTaskId,
                    appId = appId,
                    depotId = authenticatedDepotId,
                    manifestId = manifestId,
                    boundAccountKeyHash = boundAccountKeyHash,
                )
                downloadWorkshopItemViaContentAccess(
                    taskId = stagingTaskId,
                    item = item,
                    depotId = authenticatedDepotId,
                    manifestId = manifestId,
                    contentAccess = authenticatedAccess,
                    storageRoot = storageRoot,
                    performanceProfile = performanceProfile,
                    transportModes = transportModes,
                    cdnPoolPreference = prefs.cdnPoolPreference,
                    shouldPause = shouldPause,
                    onProgress = onProgress,
                    onRuntimeInfoChanged = onRuntimeInfoChanged,
                )
            }.onSuccess {
                return@withContext storageRoot.exportResultUri(
                    treeUri = targetTreeUri,
                    downloadFolderName = downloadFolderName,
                    onPhaseChanged = onPhaseChanged,
                )
            }.onFailure { throwable ->
                if (throwable.isPauseSignal()) throw throwable
                failures += "已登录 Steam 内容下载失败：${throwable.message ?: "未知错误"}"
            }
        }

        if (!anonymousFirst && downloadAuthMode != DownloadAuthMode.Anonymous) {
            runCatching {
                val anonymousAccess = runDownloadStage(
                    taskId = stagingTaskId,
                    stage = "anonymous_content_access",
                    timeoutMs = DOWNLOAD_ANONYMOUS_CONTENT_ACCESS_TIMEOUT_MS,
                    timeoutMessage = "获取匿名 Steam 内容访问超时，请检查当前网络后重试",
                    fields = mapOf(
                        "publishedFileId" to item.publishedFileId.toString(),
                        "appId" to appId.toString(),
                        "manifestId" to manifestId.toString(),
                    ),
                ) {
                    loadWorkshopContentAccessAnonymously(
                        appId = appId,
                        manifestId = manifestId,
                        depotId = item.appId.takeIf { it > 0 } ?: appId,
                    )
                }
                downloadWorkshopItemViaContentAccess(
                    taskId = stagingTaskId,
                    item = item,
                    depotId = item.appId.takeIf { it > 0 } ?: appId,
                    manifestId = manifestId,
                    contentAccess = anonymousAccess,
                    storageRoot = storageRoot,
                    performanceProfile = performanceProfile,
                    transportModes = transportModes,
                    cdnPoolPreference = prefs.cdnPoolPreference,
                    shouldPause = shouldPause,
                    onProgress = onProgress,
                    onRuntimeInfoChanged = onRuntimeInfoChanged,
                )
            }.onSuccess {
                return@withContext storageRoot.exportResultUri(
                    treeUri = targetTreeUri,
                    downloadFolderName = downloadFolderName,
                    onPhaseChanged = onPhaseChanged,
                )
            }.onFailure { throwable ->
                if (throwable.isPauseSignal()) throw throwable
                failures += "匿名 Steam 内容回退失败：${throwable.message ?: "未知错误"}"
            }
        }

        if (!prefs.isLoginFeatureEnabled && downloadAuthMode != DownloadAuthMode.Anonymous) {
            error("已在设置中关闭登录功能，当前只允许匿名下载")
        }
        if (!prefs.isLoggedInDownloadEnabled && downloadAuthMode != DownloadAuthMode.Anonymous) {
            error("已在设置中关闭“登录后下载”，当前只允许匿名下载")
        }
        error(failures.distinct().joinToString("；").ifBlank { "Steam 内容下载失败" })
    }

    fun login(username: String, password: String, rememberSession: Boolean) {
        if (interactiveLoginJob?.isActive == true) {
            Log.w(LOG_TAG, "login ignored because interactive login is already in progress")
            return
        }
        startInteractiveLogin(
            username = username,
            password = password,
            rememberSession = rememberSession,
        )
    }

    private fun startInteractiveLogin(
        username: String,
        password: String,
        rememberSession: Boolean,
    ) {
        pendingAuthCode = null
        bufferedAuthCode = null
        lastSubmittedAuthCode = null

        interactiveLoginJob = appScope.launch {
            if (!preferencesStore.snapshot().isLoginFeatureEnabled) {
                _sessionState.value = SteamSessionState(
                    status = SessionStatus.Error,
                    errorMessage = "已在设置中关闭登录功能",
                    connectionRevision = connectionRevision,
                )
                return@launch
            }
            bootstrapJob?.cancel()
            bootstrapJob = null
            recoveryJob?.cancel()
            _sessionState.value = SteamSessionState(
                status = SessionStatus.Connecting,
                account = _sessionState.value.account?.copy(accountName = username.trim())
                    ?: SteamAccountSession(username.trim(), 0),
                connectionRevision = connectionRevision,
            )

            runCatching {
                activeConnectionProfile = primaryConnectionProfiles.first()
                pendingRememberSession = rememberSession
                connectForInteractiveLogin()
                authenticateWithCredentialsWithRetry(username.trim(), password, rememberSession)
            }.onFailure { throwable ->
                if (throwable is CancellationException && !coroutineContext.isActive) {
                    Log.i(LOG_TAG, "interactive login cancelled by caller", throwable)
                    return@onFailure
                }
                if (throwable is CancellationException && _sessionState.value.status == SessionStatus.AwaitingCode) {
                    Log.w(LOG_TAG, "interactive login cancelled while waiting for a renewed auth challenge", throwable)
                    return@onFailure
                }
                Log.w(LOG_TAG, "interactive login failed", throwable)
                _sessionState.value = SteamSessionState(
                    status = SessionStatus.Error,
                    account = _sessionState.value.account?.copy(accountName = username.trim()),
                    errorMessage = readableMessage(throwable),
                    connectionRevision = connectionRevision,
                )
            }
        }.also { job ->
            job.invokeOnCompletion {
                if (interactiveLoginJob === job) {
                    interactiveLoginJob = null
                }
            }
        }
    }

    fun submitAuthCode(code: String) {
        val normalizedCode = code.trim()
        if (normalizedCode.isBlank()) return
        lastSubmittedAuthCode = normalizedCode

        val future = pendingAuthCode
        val submitted = future != null &&
            !future.isDone &&
            !future.isCancelled &&
            future.complete(normalizedCode)
        if (!submitted) {
            val currentState = _sessionState.value
            if (currentState.status == SessionStatus.AwaitingCode && currentState.challenge != null) {
                bufferedAuthCode = normalizedCode
                Log.i(LOG_TAG, "submitAuthCode buffered until auth challenge future becomes ready")
            } else {
                Log.w(LOG_TAG, "submitAuthCode ignored because there is no active auth challenge")
            }
            return
        }
        bufferedAuthCode = null
        _sessionState.value = _sessionState.value.copy(
            status = SessionStatus.Authenticating,
            challenge = null,
            errorMessage = null,
        )
    }

    private suspend fun loadOwnedGamesFromPlayerService(): List<OwnedGame> {
        val request = SteammessagesPlayerSteamclient.CPlayer_GetOwnedGames_Request.newBuilder().apply {
            steamid = currentSteamId64()
            includePlayedFreeGames = true
            includeFreeSub = true
            includeAppinfo = true
        }.build()

        val response = playerService?.getOwnedGames(request)?.await()
            ?: error("Steam unified Player service unavailable")
        check(response.result == EResult.OK) { "Steam 返回 ${response.result}" }

        return response.body.gamesList
            .map { game ->
                OwnedGame(
                    appId = game.appid,
                    name = game.name,
                    iconHash = game.imgIconUrl,
                    ownershipSource = OwnershipSource.Owned,
                )
            }
    }


    private suspend fun loadFamilySharedGames(
        existingOwnedAppIds: Collection<Int>,
    ): List<OwnedGame> {
        val licenses = awaitLicenseList()
        if (licenses.isEmpty()) return emptyList()

        val currentAccountId = currentAccountId()
        val borrowedLicenses = licenses.filter { license ->
            license.packageID > 0 &&
                license.licenseType != ELicenseType.NoLicense &&
                !license.licenseFlags.contains(ELicenseFlags.Expired) &&
                !license.licenseFlags.contains(ELicenseFlags.CancelledByUser) &&
                !license.licenseFlags.contains(ELicenseFlags.CancelledByAdmin) &&
                !license.licenseFlags.contains(ELicenseFlags.PendingRefund) &&
                (
                    license.licenseFlags.contains(ELicenseFlags.Borrowed) ||
                        (license.ownerAccountID.toLong() != 0L && license.ownerAccountID.toLong() != currentAccountId)
                    )
        }
        if (borrowedLicenses.isEmpty()) return emptyList()

        val packageIds = borrowedLicenses.map { it.packageID }.distinct()
        val packageTokens = steamApps?.picsGetAccessTokens(emptyList<Int>(), packageIds)
            ?.await()
            ?.packageTokens
            .orEmpty()

        val packageInfos = steamApps?.picsGetProductInfo(
            emptyList<PICSRequest>(),
            packageIds.map { packageId -> PICSRequest(packageId, packageTokens[packageId] ?: 0L) },
            false,
        )?.await()
            ?.results
            .orEmpty()
            .flatMap { it.packages.entries }
            .associate { it.toPair() }

        val sharedAppOwners = linkedMapOf<Int, Long?>()
        borrowedLicenses.forEach { license ->
            val packageInfo = packageInfos[license.packageID] ?: return@forEach
            val lenderSteamId64 = ownerAccountIdToSteamId64(license.ownerAccountID)
            extractPackageAppIds(packageInfo).forEach { appId ->
                if (appId !in existingOwnedAppIds) {
                    sharedAppOwners.putIfAbsent(appId, lenderSteamId64)
                }
            }
        }
        if (sharedAppOwners.isEmpty()) return emptyList()

        val appIds = sharedAppOwners.keys.toList()
        val appTokens = steamApps?.picsGetAccessTokens(appIds, emptyList<Int>())
            ?.await()
            ?.appTokens
            .orEmpty()

        val appInfos = steamApps?.picsGetProductInfo(
            appIds.map { appId -> PICSRequest(appId, appTokens[appId] ?: 0L) },
            emptyList<PICSRequest>(),
            false,
        )?.await()
            ?.results
            .orEmpty()
            .flatMap { it.apps.entries }
            .associate { it.toPair() }

        return sharedAppOwners.mapNotNull { (appId, _) ->
            val appInfo = appInfos[appId] ?: return@mapNotNull null
            val metadata = extractAppMetadata(appInfo) ?: return@mapNotNull null
            if (!metadata.shouldDisplayInLibrary()) return@mapNotNull null

            OwnedGame(
                appId = appId,
                name = metadata.name,
                iconHash = metadata.iconHash,
                ownershipSource = OwnershipSource.FamilyShared,
            )
        }
    }

    private suspend fun loadPublishedFileDetailsAuthenticated(
        publishedFileIds: Collection<Long>,
    ): List<SteammessagesPublishedfileSteamclient.PublishedFileDetails> {
        ensureAuthenticated()
        val normalizedIds = publishedFileIds
            .filter { it > 0L }
            .distinct()
        check(normalizedIds.isNotEmpty()) { "未提供有效的创意工坊条目标识" }
        val request = SteammessagesPublishedfileSteamclient.CPublishedFile_GetDetails_Request.newBuilder().apply {
            normalizedIds.forEach(::addPublishedfileids)
            includetags = true
            includeadditionalpreviews = true
            includechildren = true
            includemetadata = true
            shortDescription = true
        }.build()

        val response = publishedFileService?.getDetails(request)?.await()
            ?: error("Steam workshop service unavailable")
        return response.body.publishedfiledetailsList
            .filter { it.result == EResult.OK.code() }
    }

    private suspend fun loadPublishedFileDetailsPublic(
        publishedFileIds: Collection<Long>,
    ): List<WorkshopItem> = withContext(Dispatchers.IO) {
        val normalizedIds = publishedFileIds
            .filter { it > 0L }
            .distinct()
        if (normalizedIds.isEmpty()) return@withContext emptyList()

        val formBody = okhttp3.FormBody.Builder()
            .add("itemcount", normalizedIds.size.toString())
            .apply {
                normalizedIds.forEachIndexed { index, publishedFileId ->
                    add("publishedfileids[$index]", publishedFileId.toString())
                }
                add("includechildren", "true")
                add("includetags", "true")
                add("includeadditionalpreviews", "true")
                add("short_description", "true")
            }
            .build()
        val request = Request.Builder()
            .url(STEAM_PUBLISHED_FILE_DETAILS_URL)
            .post(formBody)
            .build()

        val payload = okHttpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Steam 创意工坊详情请求失败：HTTP ${response.code}" }
            response.body?.string().orEmpty()
        }
        val envelope = json.decodeFromString<PublicPublishedFileDetailsEnvelope>(payload)
        envelope.response.publishedFileDetails
            .mapNotNull(::mapPublicPublishedFileDetails)
    }

    private suspend fun fetchWorkshopDepotId(appId: Int): Int {
        workshopDepotIdCache[appId]
            ?.takeIf { it.isFresh(WORKSHOP_DEPOT_ID_CACHE_MS) }
            ?.let { return it.value }

        val appInfo = fetchAppInfo(appId)
        val depots = appInfo?.keyValues?.childOrNull("depots")
        val depotId = depots?.stringOrNull("workshopdepot")?.toIntOrNull()?.takeIf { it > 0 } ?: appId
        workshopDepotIdCache[appId] = CachedValue(depotId)
        return depotId
    }

    private suspend fun fetchAppInfo(appId: Int): PICSProductInfo? {
        val appTokens = steamApps?.picsGetAccessTokens(listOf(appId), emptyList<Int>())
            ?.await()
            ?.appTokens
            .orEmpty()

        return steamApps?.picsGetProductInfo(
            listOf(PICSRequest(appId, appTokens[appId] ?: 0L)),
            emptyList<PICSRequest>(),
            false,
        )?.await()
            ?.results
            .orEmpty()
            .flatMap { it.apps.entries }
            .firstOrNull { it.key == appId }
            ?.value
    }

    private fun prepareManifest(
        manifest: DepotManifest,
        depotKey: ByteArray,
    ) {
        if (manifest.filenamesEncrypted) {
            check(manifest.decryptFilenames(depotKey)) { "无法解密 Workshop manifest 文件名" }
        }
    }

    private suspend fun downloadManifestFiles(
        taskId: String?,
        manifest: DepotManifest,
        depotId: Int,
        routes: List<CdnRoute>,
        depotKey: ByteArray,
        storageRoot: WorkshopStorageRoot,
        chunkPrefetchCount: Int,
        maxActiveRoutes: Int,
        shouldPause: suspend () -> Boolean,
        onProgress: suspend (Long, Long) -> Unit,
    ) {
        val totalBytes = manifest.totalUncompressedSize.takeIf { it > 0L }
            ?: manifest.files.sumOf { it.totalSize.coerceAtLeast(0L) }
        val chunkPipelineDepth = chunkPrefetchCount
            .coerceAtLeast(1)
            .coerceAtMost(MAX_DOWNLOAD_CHUNK_PIPELINE_DEPTH)
        val files = manifest.files.sortedBy { it.fileName.lowercase() }
        val resumePlans = files.mapIndexed { index, file ->
            val relativePath = normalizeRelativePath(
                rawPath = file.fileName,
                fallbackName = "file-${index + 1}",
            )
            val restoredChunkCount = when {
                file.flags.contains(EDepotFileFlag.Directory) -> 0
                file.flags.contains(EDepotFileFlag.Symlink) -> 0
                else -> inferCompletedChunkCount(
                    chunkSizes = file.chunks
                        .sortedBy { it.offset }
                        .map { it.uncompressedLength },
                    existingSize = storageRoot.existingFileSize(relativePath),
                )
            }
            FileResumePlan(
                relativePath = relativePath,
                restoredChunkCount = restoredChunkCount,
                restoredBytes = file.chunks
                    .sortedBy { it.offset }
                    .take(restoredChunkCount)
                    .sumOf { it.uncompressedLength.toLong() },
            )
        }
        var bytesDownloaded = resumePlans.sumOf { it.restoredBytes }.coerceAtMost(totalBytes)
        onProgress(bytesDownloaded, totalBytes)

        files.forEachIndexed { index, file ->
            coroutineContext.ensureActive()
            val resumePlan = resumePlans[index]
            val relativePath = resumePlan.relativePath

            when {
                file.flags.contains(EDepotFileFlag.Directory) -> storageRoot.ensureDirectory(relativePath)
                file.flags.contains(EDepotFileFlag.Symlink) -> Unit
                else -> {
                    val chunks = file.chunks.sortedBy { it.offset }
                    if (resumePlan.restoredChunkCount >= chunks.size) {
                        return@forEachIndexed
                    }
                    BufferedOutputStream(
                        storageRoot.openFile(
                            relativePath = relativePath,
                            append = resumePlan.restoredChunkCount > 0,
                        ),
                        WORKSHOP_FILE_WRITE_BUFFER_SIZE,
                    ).use { output ->
                        coroutineScope {
                            var nextPrefetchIndex = resumePlan.restoredChunkCount
                            var nextWriteIndex = resumePlan.restoredChunkCount
                            val pendingChunks = linkedMapOf<Int, kotlinx.coroutines.Deferred<DownloadedChunk>>()

                            while (nextWriteIndex < chunks.size) {
                                while (
                                    nextPrefetchIndex < chunks.size &&
                                    pendingChunks.size < chunkPipelineDepth
                                ) {
                                    val chunkIndex = nextPrefetchIndex++
                                    val chunk = chunks[chunkIndex]
                                    pendingChunks[chunkIndex] = async {
                                        coroutineContext.ensureActive()
                                        downloadChunkViaRoutes(
                                            taskId = taskId,
                                            depotId,
                                            chunk,
                                            depotKey,
                                            routes,
                                            maxActiveRoutes,
                                            shouldPause,
                                        )
                                    }
                                }

                                val downloadedChunk = pendingChunks.remove(nextWriteIndex)?.await()
                                    ?: error("缺少分块下载任务：index=$nextWriteIndex")
                                nextWriteIndex += 1
                                if (downloadedChunk.written > 0) {
                                    output.write(downloadedChunk.buffer, 0, downloadedChunk.written)
                                    bytesDownloaded += downloadedChunk.written.toLong()
                                    onProgress(bytesDownloaded.coerceAtMost(totalBytes), totalBytes)
                                }
                                if (shouldPause()) {
                                    output.flush()
                                    throw DownloadPausedException()
                                }
                            }
                        }
                        output.flush()
                    }
                }
            }
        }

        onProgress(totalBytes, totalBytes)
    }

    private fun buildCdnServerLabel(
        server: Server,
        hostName: String,
    ): String {
        return buildString {
            append(server.protocol.name)
            append("://")
            append(hostName)
            if (server.port > 0) append(":${server.port}")
        }
    }

    private fun uniqueServersByHost(
        servers: List<Server>,
    ): List<Server> {
        val seenHosts = LinkedHashSet<String>()
        return servers.filter { server ->
            val hostName = serverIdentity(server) ?: return@filter false
            seenHosts.add(hostName)
        }
    }

    private fun createTransportClients(
        transportModes: List<Boolean>,
        performanceProfile: DownloadPerformanceProfile,
    ): List<TransportClient> {
        return transportModes
            .distinct()
            .map { forceDirect ->
                TransportClient(
                    forceDirect = forceDirect,
                    transportLabel = if (forceDirect) "direct" else "system",
                    transportDisplayLabel = if (forceDirect) "直连" else "系统网络",
                    client = createIsolatedCdnClient(
                        forceDirect = forceDirect,
                        performanceProfile = performanceProfile,
                    ),
                )
            }
    }

    private fun buildCdnRoutes(
        transportClients: List<TransportClient>,
        servers: List<Server>,
        contentAccess: WorkshopContentAccess,
        maxRoutesPerTransport: Int = MAX_CDN_ROUTES_PER_TRANSPORT,
    ): List<CdnRoute> {
        var rank = 0
        return buildList {
            transportClients.forEach { transportClient ->
                uniqueServersByHost(servers)
                    .take(maxRoutesPerTransport.coerceAtLeast(1))
                    .forEach { server ->
                    val hostName = serverIdentity(server) ?: return@forEach
                    add(
                        CdnRoute(
                            rank = rank++,
                            forceDirect = transportClient.forceDirect,
                            transportLabel = transportClient.transportLabel,
                            transportDisplayLabel = transportClient.transportDisplayLabel,
                            client = transportClient.client,
                            server = server,
                            hostName = hostName,
                            serverLabel = buildCdnServerLabel(server, hostName),
                            performance = cdnHostPerformanceTracker.getOrPut(hostName) {
                                CdnHostPerformance()
                            },
                            tokenProvider = {
                                contentAccess.cdnAuthTokenProvider?.invoke(hostName)
                            },
                        ),
                    )
                }
            }
        }
    }

    private fun selectPreferredCdnRoutes(
        routes: List<CdnRoute>,
        excludedHosts: Set<String> = emptySet(),
        excludedRouteKeys: Set<String> = emptySet(),
    ): List<CdnRoute> {
        val now = System.currentTimeMillis()
        return routes.asSequence()
            .filter { route ->
                route.hostName !in excludedHosts && route.selectionKey !in excludedRouteKeys
            }
            .sortedWith(
                compareBy<CdnRoute>(
                    { cdnCooldownPriority(it.hostName, now) },
                    { if (it.performance.hasSamples()) 0 else 1 },
                    { it.performance.averageNanosPerMiB() },
                    { it.performance.failurePenalty() },
                    { cdnLastSuccessPriority(it.hostName) },
                    {
                        lastSuccessfulCdnTransportDirect?.let { preferred ->
                            if (it.forceDirect == preferred) 0 else 1
                        } ?: 0
                    },
                    { it.performance.activeRequestCount() },
                    { it.rank },
                ),
            )
            .distinctBy(CdnRoute::selectionKey)
            .toList()
    }

    private fun uniqueRoutesByHost(
        routes: List<CdnRoute>,
    ): List<CdnRoute> {
        val seenHosts = LinkedHashSet<String>()
        return routes.filter { route -> seenHosts.add(route.hostName) }
    }

    private fun selectActiveCdnRoutes(
        routes: List<CdnRoute>,
        excludedHosts: Set<String> = emptySet(),
        excludedRouteKeys: Set<String> = emptySet(),
        maxActiveRoutes: Int = MAX_ACTIVE_CDN_ROUTES,
    ): List<CdnRoute> {
        val preferredRoutes = uniqueRoutesByHost(
            selectPreferredCdnRoutes(
                routes = routes,
                excludedHosts = excludedHosts,
                excludedRouteKeys = excludedRouteKeys,
            ),
        )
        val effectiveLimit = maxActiveRoutes.coerceAtLeast(1)
        if (preferredRoutes.size <= effectiveLimit) {
            return preferredRoutes
        }
        return preferredRoutes.take(effectiveLimit)
    }

    private fun transportSummaryLabel(routes: List<CdnRoute>): String {
        val hasDirect = routes.any { it.forceDirect }
        val hasSystem = routes.any { !it.forceDirect }
        return when {
            hasDirect && hasSystem -> "混合网络"
            hasDirect -> "直连"
            else -> "系统网络"
        }
    }

    private fun rememberSuccessfulCdnRoute(route: CdnRoute) {
        rememberSuccessfulCdnHost(route.hostName)
        lastSuccessfulCdnTransportDirect = route.forceDirect
        appScope.launch {
            preferencesStore.saveLastCdnSelection(
                host = route.hostName,
                forceDirect = route.forceDirect,
            )
        }
    }

    private suspend fun loadManifestFromRoutes(
        taskId: String?,
        routes: List<CdnRoute>,
        depotId: Int,
        manifestId: Long,
        contentAccess: WorkshopContentAccess,
    ): Pair<DepotManifest, CdnRoute> = coroutineScope {
        val preferredRoutes = uniqueRoutesByHost(selectPreferredCdnRoutes(routes))
        check(preferredRoutes.isNotEmpty()) { "未找到可用的 Steam CDN 路由" }

        val failures = mutableListOf<String>()
        val batchSize = CDN_MANIFEST_RACE_ROUTE_COUNT.coerceAtLeast(1)
        preferredRoutes.chunked(batchSize).forEach { raceRoutes ->
            val results = Channel<ManifestProbeResult>(raceRoutes.size)
            val jobs = raceRoutes.map { route ->
                launch {
                    route.performance.beginRequest()
                    val startedAt = System.nanoTime()
                    runCatching {
                        supportDiagnosticsStore.incrementCounter("download_manifest_requests", route.hostName)
                        val manifest = route.client.downloadManifestFuture(
                            depotId,
                            manifestId,
                            contentAccess.manifestRequestCode,
                            route.server,
                            contentAccess.depotKey,
                            null,
                            route.authToken(),
                        ).await()
                        prepareManifest(manifest, contentAccess.depotKey)
                        route.performance.finishSuccess(256L * 1024L, System.nanoTime() - startedAt)
                        supportDiagnosticsStore.recordManifestRouteSuccess(taskId, route.hostName)
                        results.trySend(
                            ManifestProbeResult(
                                route = route,
                                manifest = manifest,
                            ),
                        )
                    }.onFailure { throwable ->
                        when {
                            throwable.isPauseSignal() -> route.performance.finishCancelled()
                            throwable is CancellationException && !coroutineContext.isActive -> {
                                route.performance.finishCancelled()
                            }

                            else -> {
                                route.performance.finishFailure()
                                recordCdnFailure(route.hostName)
                                supportDiagnosticsStore.recordCdnFailure(
                                    taskId = taskId,
                                    host = route.hostName,
                                    message = throwable.message ?: throwable.javaClass.simpleName,
                                )
                            }
                        }
                        results.trySend(
                            ManifestProbeResult(
                                route = route,
                                failure = throwable,
                            ),
                        )
                    }
                }
            }

            try {
                repeat(raceRoutes.size) {
                    val result = results.receive()
                    val manifest = result.manifest
                    if (manifest != null) {
                        jobs.forEach { if (it.isActive) it.cancel() }
                        return@coroutineScope manifest to result.route
                    }

                    val failure = result.failure
                    if (failure.isPauseSignal()) {
                        throw (failure ?: DownloadPausedException())
                    }
                    val message = failure?.message ?: failure?.javaClass?.simpleName.orEmpty()
                    failures += "${result.route.serverLabel}/${result.route.transportLabel} ${message.ifBlank { "下载失败" }}"
                }
            } finally {
                jobs.forEach { it.cancel() }
                results.close()
            }
        }

        error(
            failures
                .distinct()
                .take(5)
                .joinToString(prefix = "${contentAccess.accessLabel} 下载失败：", separator = "；")
                .ifBlank { "${contentAccess.accessLabel} 下载失败" },
        )
    }

    private suspend fun downloadChunkViaRoutes(
        taskId: String?,
        depotId: Int,
        chunk: `in`.dragonbra.javasteam.types.ChunkData,
        depotKey: ByteArray,
        routes: List<CdnRoute>,
        maxActiveRoutes: Int,
        shouldPause: suspend () -> Boolean,
    ): DownloadedChunk {
        val attemptedRouteKeys = linkedSetOf<String>()
        var lastError: Throwable? = null
        val maxAttempts = routes
            .asSequence()
            .map(CdnRoute::selectionKey)
            .distinct()
            .count()
            .coerceAtMost(MAX_CDN_ROUTE_ATTEMPTS_PER_CHUNK)
            .coerceAtLeast(1)

        repeat(maxAttempts) {
            coroutineContext.ensureActive()
            if (shouldPause()) {
                throw DownloadPausedException()
            }

            val route = selectActiveCdnRoutes(
                routes,
                excludedRouteKeys = attemptedRouteKeys,
                maxActiveRoutes = maxActiveRoutes,
            ).firstOrNull() ?: return@repeat
            attemptedRouteKeys += route.selectionKey
            route.performance.beginRequest()
            val startedAt = System.nanoTime()

            try {
                supportDiagnosticsStore.incrementCounter("download_chunk_attempts", route.hostName)
                val encryptedBuffer = ByteArray(chunk.compressedLength)
                val fileBuffer = ByteArray(chunk.uncompressedLength)
                route.client.downloadDepotChunkFuture(
                    depotId,
                    chunk,
                    route.server,
                    encryptedBuffer,
                    null,
                    null,
                    route.authToken(),
                ).await()
                val written = DepotChunk.process(
                    chunk,
                    encryptedBuffer,
                    fileBuffer,
                    depotKey,
                )
                route.performance.finishSuccess(
                    written.toLong().coerceAtLeast(1L),
                    System.nanoTime() - startedAt,
                )
                supportDiagnosticsStore.recordChunkRouteSuccess(taskId, route.hostName)
                return DownloadedChunk(
                    buffer = if (written == fileBuffer.size) {
                        fileBuffer
                    } else {
                        fileBuffer.copyOf(written.coerceAtLeast(0))
                    },
                    written = written,
                )
            } catch (throwable: Throwable) {
                when {
                    throwable.isPauseSignal() -> {
                        route.performance.finishCancelled()
                        throw throwable
                    }

                    throwable is CancellationException && !coroutineContext.isActive -> {
                        route.performance.finishCancelled()
                        throw throwable
                    }

                    else -> {
                        route.performance.finishFailure()
                        recordCdnFailure(route.hostName)
                        supportDiagnosticsStore.recordCdnFailure(
                            taskId = taskId,
                            host = route.hostName,
                            message = throwable.message ?: throwable.javaClass.simpleName,
                        )
                        lastError = throwable
                    }
                }
            }
        }

        throw lastError ?: IllegalStateException("Steam CDN chunk download failed")
    }

    private suspend fun downloadWorkshopItemViaContentAccess(
        taskId: String,
        item: WorkshopItem,
        depotId: Int,
        manifestId: Long,
        contentAccess: WorkshopContentAccess,
        storageRoot: WorkshopStorageRoot,
        performanceProfile: DownloadPerformanceProfile,
        transportModes: List<Boolean>,
        cdnPoolPreference: CdnPoolPreference,
        shouldPause: suspend () -> Boolean,
        onProgress: suspend (Long, Long) -> Unit,
        onRuntimeInfoChanged: suspend (
            routeLabel: String?,
            transportLabel: String?,
            endpointLabel: String?,
            sourceAddress: String?,
            attemptCount: Int?,
            chunkConcurrency: Int?,
            lastFailure: String?,
        ) -> Unit,
    ) {
        val candidateServers = prioritizeServersByUserPreference(
            servers = contentAccess.servers,
            preference = cdnPoolPreference,
        ).filter { server ->
            contentAccess.allowInsecureTransport || server.protocol.name.equals("HTTPS", ignoreCase = true)
        }
        check(candidateServers.isNotEmpty()) {
            if (contentAccess.allowInsecureTransport) {
                "${contentAccess.accessLabel} 没有可用的 Steam CDN 路由"
            } else {
                "${contentAccess.accessLabel} 没有可用的 HTTPS Steam CDN 路由"
            }
        }
        val transportClients = createTransportClients(
            transportModes = transportModes,
            performanceProfile = performanceProfile,
        )
        try {
            val routes = buildCdnRoutes(
                transportClients = transportClients,
                servers = candidateServers,
                contentAccess = contentAccess,
                maxRoutesPerTransport = performanceProfile.maxRoutesPerTransport,
            )
            supportDiagnosticsStore.recordCdnTaskPlan(
                taskId = taskId,
                accessLabel = contentAccess.accessLabel,
                poolPreference = cdnPoolPreference.name,
                transportSummary = transportSummaryLabel(routes),
                candidateServerCount = candidateServers.size,
                httpsServerCount = candidateServers.count { it.protocol.name.equals("HTTPS", ignoreCase = true) },
                selectedRouteCount = routes.size,
            )
            check(routes.isNotEmpty()) {
                if (contentAccess.allowInsecureTransport) {
                    "${contentAccess.accessLabel} 没有可用的 Steam CDN 路由"
                } else {
                    "${contentAccess.accessLabel} 没有可用的 HTTPS Steam CDN 路由"
                }
            }

            val sourceAddress = "steam://publishedfile/${item.publishedFileId} · manifest $manifestId"
            onRuntimeInfoChanged(
                contentAccess.accessLabel,
                transportSummaryLabel(routes),
                "${routes.size} 个 CDN 节点并发",
                sourceAddress,
                1,
                performanceProfile.chunkConcurrency,
                null,
            )

            val (manifest, manifestRoute) = loadManifestFromRoutes(
                taskId = taskId,
                routes = routes,
                depotId = depotId,
                manifestId = manifestId,
                contentAccess = contentAccess,
            )
            rememberSuccessfulCdnRoute(manifestRoute)

            downloadManifestFiles(
                manifest = manifest,
                depotId = depotId,
                routes = routes,
                depotKey = contentAccess.depotKey,
                storageRoot = storageRoot,
                taskId = taskId,
                chunkPrefetchCount = performanceProfile.chunkConcurrency,
                maxActiveRoutes = performanceProfile.maxActiveRoutes,
                shouldPause = shouldPause,
                onProgress = onProgress,
            )

            rememberSuccessfulCdnRoute(
                selectPreferredCdnRoutes(routes).firstOrNull() ?: manifestRoute,
            )
            Log.i(
                LOG_TAG,
                "downloadWorkshopItem success routes=${routes.size} access=${contentAccess.accessLabel} publishedFileId=${item.publishedFileId}",
            )
        } finally {
            transportClients.forEach { transportClient ->
                runCatching { transportClient.client.close() }
            }
        }
    }

    private suspend fun loadWorkshopContentAccessAuthenticated(
        taskId: String?,
        appId: Int,
        depotId: Int,
        manifestId: Long,
        boundAccountKeyHash: String?,
    ): WorkshopContentAccess {
        validateBoundAuthenticatedAccount(boundAccountKeyHash)
        runDownloadStage(
            taskId = taskId,
            stage = "authenticated_session_restore",
            timeoutMs = DOWNLOAD_AUTH_RESTORE_TIMEOUT_MS,
            timeoutMessage = "恢复 Steam 登录超时，请检查当前网络、代理或 VPN 设置后重试",
            fields = mapOf(
                "appId" to appId.toString(),
                "depotId" to depotId.toString(),
                "manifestId" to manifestId.toString(),
            ),
        ) {
            ensureAuthenticated()
        }
        val effectiveSteamId64 = _sessionState.value.account?.steamId64
            ?.takeIf { it > 0L }
        val cacheKey = WorkshopContentAccessCacheKey(
            authMode = DownloadAuthMode.Authenticated,
            appId = appId,
            depotId = depotId,
            manifestId = manifestId,
            steamId64 = effectiveSteamId64,
        )
        getCachedWorkshopContentAccess(cacheKey)?.let { cached ->
            return cached
        }

        val depotKeyCallback = runDownloadStage(
            taskId = taskId,
            stage = "authenticated_depot_key",
            timeoutMs = DOWNLOAD_DEPOT_KEY_TIMEOUT_MS,
            timeoutMessage = "获取已登录下载密钥超时，请稍后重试",
            fields = mapOf(
                "appId" to appId.toString(),
                "depotId" to depotId.toString(),
                "manifestId" to manifestId.toString(),
            ),
        ) {
            steamApps?.getDepotDecryptionKey(depotId, appId)?.await()
                ?: error("无法获取 Workshop depot key")
        }
        check(depotKeyCallback.result == EResult.OK) { "Steam 返回 ${depotKeyCallback.result}" }

        val contentHandler = steamContent ?: error("Steam content handler 不可用")
        val servers = loadAuthenticatedCdnServers(
            contentHandler = contentHandler,
            taskId = taskId,
            appId = appId,
            depotId = depotId,
            manifestId = manifestId,
        )

        val manifestRequestCode = runDownloadStage(
            taskId = taskId,
            stage = "authenticated_manifest_request",
            timeoutMs = DOWNLOAD_MANIFEST_REQUEST_CODE_TIMEOUT_MS,
            timeoutMessage = "获取 Steam manifest 访问码超时，请稍后重试",
            fields = mapOf(
                "appId" to appId.toString(),
                "depotId" to depotId.toString(),
                "manifestId" to manifestId.toString(),
            ),
        ) {
            contentHandler.getManifestRequestCode(
                depotId,
                appId,
                manifestId,
                "public",
                null,
                appScope,
            ).await()
        }

        return WorkshopContentAccess(
            depotKey = depotKeyCallback.depotKey,
            servers = servers,
            manifestRequestCode = manifestRequestCode,
            accessLabel = "已登录 Steam 内容",
            allowInsecureTransport = false,
            cdnAuthTokenProvider = { hostName ->
                runCatching {
                    contentHandler.getCDNAuthToken(appId, depotId, hostName, appScope).await()
                }.getOrNull()
                    ?.takeIf { it.result == EResult.OK }
                    ?.token
            },
        ).also { access ->
            workshopContentAccessCache[cacheKey] = CachedValue(access)
        }
    }

    private suspend fun loadWorkshopContentAccessAnonymously(
        appId: Int,
        depotId: Int,
        manifestId: Long,
    ): WorkshopContentAccess {
        val cacheKey = WorkshopContentAccessCacheKey(
            authMode = DownloadAuthMode.Anonymous,
            appId = appId,
            depotId = depotId,
            manifestId = manifestId,
            steamId64 = null,
        )
        getCachedWorkshopContentAccess(cacheKey)?.let { cached ->
            return cached
        }
        var lastError: Throwable? = null
        repeat(2) { attempt ->
            val result = runCatching {
                val session = ensureAnonymousContentSession(forceRecreate = attempt > 0)
                loadWorkshopContentAccessFromAnonymousSession(
                    session = session,
                    appId = appId,
                    depotId = depotId,
                    manifestId = manifestId,
                )
            }
            if (result.isSuccess) {
                return result.getOrThrow().also { access ->
                    workshopContentAccessCache[cacheKey] = CachedValue(access)
                }
            }
            lastError = result.exceptionOrNull()
            invalidateAnonymousContentSession()
            Log.w(
                LOG_TAG,
                "loadWorkshopContentAccessAnonymously failed attempt=${attempt + 1}/2",
                lastError,
            )
        }

        throw lastError ?: IllegalStateException("匿名 Steam 内容访问失败")
    }

    private suspend fun ensureAnonymousContentSession(
        forceRecreate: Boolean = false,
    ): AnonymousContentSession = anonymousContentSessionMutex.withLock {
        val now = System.currentTimeMillis()
        val existing = anonymousContentSession
        if (
            !forceRecreate &&
            existing != null &&
            existing.connectionAlive.get() &&
            now - existing.lastUsedAtMs.get() <= ANONYMOUS_CONTENT_SESSION_IDLE_MS
        ) {
            existing.lastUsedAtMs.set(now)
            return@withLock existing
        }

        if (existing != null) {
            anonymousContentSession = null
            teardownAnonymousContentSession(existing)
        }

        var lastError: Throwable? = null
        prioritizedAnonymousConnectionProfiles().forEach { profile ->
            val result = runCatching { createAnonymousContentSession(profile) }
            if (result.isSuccess) {
                return@withLock result.getOrThrow().also { created ->
                    anonymousContentSession = created
                }
            }
            lastError = result.exceptionOrNull()
            Log.w(LOG_TAG, "ensureAnonymousContentSession failed profile=${profile.label}", lastError)
        }
        throw lastError ?: IllegalStateException("无法建立匿名 Steam 内容会话")
    }

    private suspend fun prewarmAnonymousContentSession() {
        if (!preferencesStore.snapshot().preferAnonymousDownloads) return
        val session = ensureAnonymousContentSession()
        runCatching {
            loadAnonymousCdnServers(session)
        }.onFailure { throwable ->
            Log.w(LOG_TAG, "prewarmAnonymousContentSession server warmup failed", throwable)
        }
    }

    private suspend fun loadWorkshopContentAccessFromAnonymousSession(
        session: AnonymousContentSession,
        appId: Int,
        depotId: Int,
        manifestId: Long,
    ): WorkshopContentAccess = withContext(Dispatchers.IO) {
        check(session.connectionAlive.get()) { "匿名 Steam 会话已断开" }
        session.lastUsedAtMs.set(System.currentTimeMillis())
        val depotKeyCallback = session.apps.getDepotDecryptionKey(depotId, appId).await()
        check(depotKeyCallback.result == EResult.OK) { "匿名 Steam 返回 ${depotKeyCallback.result}" }

        val servers = loadAnonymousCdnServers(session)

        val manifestRequestCode = session.content.getManifestRequestCode(
            depotId,
            appId,
            manifestId,
            "public",
            null,
            session.callbackScope,
        ).await()

        WorkshopContentAccess(
            depotKey = depotKeyCallback.depotKey,
            servers = servers,
            manifestRequestCode = manifestRequestCode,
            accessLabel = "匿名 Steam 内容",
            allowInsecureTransport = true,
        )
    }

    private suspend fun createAnonymousContentSession(
        profile: ConnectionProfile,
    ): AnonymousContentSession = withContext(Dispatchers.IO) {
        val localScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val configuration = SteamConfiguration.create {
            it.withProtocolTypes(profile.protocolTypes)
            it.withConnectionTimeout(profile.connectionTimeoutMs)
            it.withDirectoryFetch(profile.useDirectoryFetch)
            profile.serverListProviderFactory?.invoke()?.let(it::withServerListProvider)
            it.withHttpClient(okHttpClient)
        }
        val localClient = SteamClient(configuration)
        val localCallbackManager = CallbackManager(localClient)
        val localApps = localClient.getHandler(SteamApps::class.java) ?: error("匿名 SteamApps handler 不可用")
        val localContent = localClient.getHandler(SteamContent::class.java) ?: error("匿名 SteamContent handler 不可用")
        val localUser = localClient.getHandler(SteamUser::class.java) ?: error("匿名 SteamUser handler 不可用")
        val connected = CompletableDeferred<Unit>()
        val loggedOn = CompletableDeferred<Unit>()
        val alive = AtomicBoolean(true)
        val subscriptions = mutableListOf<Closeable>()

        val connectedSubscription = localCallbackManager.subscribe(ConnectedCallback::class.java) {
            connected.complete(Unit)
        }
        val disconnectedSubscription = localCallbackManager.subscribe(DisconnectedCallback::class.java) { callback ->
            alive.set(false)
            val error = IllegalStateException("匿名 Steam 连接已断开")
            if (!connected.isCompleted) {
                connected.completeExceptionally(error)
            }
            if (!loggedOn.isCompleted) {
                loggedOn.completeExceptionally(error)
            }
        }
        val loggedOnSubscription = localCallbackManager.subscribe(LoggedOnCallback::class.java) { callback ->
            if (callback.result == EResult.OK) {
                loggedOn.complete(Unit)
            } else {
                loggedOn.completeExceptionally(
                    IllegalStateException("匿名 Steam 登录失败：${callback.result}"),
                )
            }
        }
        subscriptions += connectedSubscription
        subscriptions += disconnectedSubscription
        subscriptions += loggedOnSubscription
        val callbackJob = localScope.launch {
            while (isActive) {
                localCallbackManager.runWaitCallbacks(1000L)
            }
        }

        try {
            localClient.connect()
            withTimeoutOrNull(profile.connectionTimeoutMs + 5_000L) {
                connected.await()
            } ?: error("匿名 Steam 连接超时")

            localUser.logOnAnonymous(
                AnonymousLogOnDetails(
                    null,
                    EOSType.AndroidUnknown,
                    "schinese",
                ),
            )
            withTimeoutOrNull(15_000L) {
                loggedOn.await()
            } ?: error("匿名 Steam 登录超时")
            preferredConnectionProfileLabel = profile.label
            appScope.launch {
                preferencesStore.saveLastConnectionProfile(profile.label)
            }

            AnonymousContentSession(
                profileLabel = profile.label,
                client = localClient,
                callbackManager = localCallbackManager,
                apps = localApps,
                content = localContent,
                user = localUser,
                callbackScope = localScope,
                callbackJob = callbackJob,
                subscriptions = subscriptions,
                connectionAlive = alive,
            )
        } catch (throwable: Throwable) {
            callbackJob.cancel()
            subscriptions.forEach { subscription -> runCatching { subscription.close() } }
            runCatching { localUser.logOff() }
            runCatching { localClient.disconnect() }
            localScope.cancel()
            throw throwable
        }
    }

    private suspend fun invalidateAnonymousContentSession() {
        anonymousContentSessionMutex.withLock {
            val existing = anonymousContentSession ?: return@withLock
            anonymousContentSession = null
            anonymousCdnServersCache = null
            teardownAnonymousContentSession(existing)
        }
    }

    private fun teardownAnonymousContentSession(session: AnonymousContentSession) {
        anonymousCdnServersCache = null
        session.connectionAlive.set(false)
        session.callbackJob.cancel()
        session.subscriptions.forEach { subscription ->
            runCatching { subscription.close() }
        }
        runCatching { session.user.logOff() }
        runCatching { session.client.disconnect() }
        session.callbackScope.cancel()
    }

    private suspend fun validateBoundAuthenticatedAccount(
        boundAccountKeyHash: String?,
    ) {
        val requiredHash = boundAccountKeyHash?.takeIf { it.isNotBlank() } ?: return
        val currentSession = _sessionState.value.account
        if (
            requiredHash == buildAccountBindingHash(
                accountName = currentSession?.accountName,
                steamId64 = currentSession?.steamId64,
            )
        ) {
            return
        }
        val prefs = preferencesStore.snapshot()
        if (
            requiredHash == buildAccountBindingHash(
                accountName = prefs.accountName,
                steamId64 = prefs.steamId64,
            )
        ) {
            return
        }
        error("当前下载任务绑定的是其他 Steam 账号")
    }

    private suspend fun canUseAuthenticatedWorkshopFallback(): Boolean {
        val prefs = preferencesStore.snapshot()
        return prefs.isLoginFeatureEnabled &&
            prefs.isLoggedInDownloadEnabled &&
            hasUsableAuthenticatedDownloadSession()
    }

    private fun hasUsableAuthenticatedDownloadSession(): Boolean {
        val state = _sessionState.value
        return state.status == SessionStatus.Authenticated &&
            !state.isRestoring &&
            recoveryJob?.isActive != true &&
            bootstrapJob?.isActive != true &&
            isConnected &&
            steamClient != null &&
            steamApps != null &&
            steamContent != null
    }

    private fun hasRecoverableAuthenticatedCredentials(
        prefs: com.slay.workshopnative.data.preferences.UserPreferences,
    ): Boolean {
        return prefs.rememberSession &&
            prefs.accountName.isNotBlank() &&
            (prefs.refreshToken.isNotBlank() || pendingRefreshToken.isNotBlank())
    }

    private suspend fun <T> runDownloadStage(
        taskId: String?,
        stage: String,
        timeoutMs: Long,
        timeoutMessage: String,
        fields: Map<String, String?> = emptyMap(),
        block: suspend () -> T,
    ): T {
        supportDiagnosticsStore.recordDownloadEvent(
            action = "${stage}_start",
            taskId = taskId,
            fields = fields + ("timeoutMs" to timeoutMs.toString()),
        )
        val startedAt = System.currentTimeMillis()
        try {
            val result = withTimeout(timeoutMs) { block() }
            supportDiagnosticsStore.recordDownloadEvent(
                action = "${stage}_success",
                taskId = taskId,
                fields = fields + ("durationMs" to (System.currentTimeMillis() - startedAt).toString()),
            )
            return result
        } catch (throwable: Throwable) {
            val durationMs = (System.currentTimeMillis() - startedAt).toString()
            when {
                throwable is TimeoutCancellationException -> {
                    supportDiagnosticsStore.recordDownloadEvent(
                        action = "${stage}_failure",
                        taskId = taskId,
                        fields = fields + mapOf(
                            "durationMs" to durationMs,
                            "message" to timeoutMessage,
                            "timeout" to "true",
                        ),
                    )
                    throw IllegalStateException(timeoutMessage, throwable)
                }

                throwable.isPauseSignal() || (throwable is CancellationException && !coroutineContext.isActive) -> {
                    supportDiagnosticsStore.recordDownloadEvent(
                        action = "${stage}_cancelled",
                        taskId = taskId,
                        fields = fields + ("durationMs" to durationMs),
                    )
                    throw throwable
                }

                else -> {
                    supportDiagnosticsStore.recordDownloadEvent(
                        action = "${stage}_failure",
                        taskId = taskId,
                        fields = fields + mapOf(
                            "durationMs" to durationMs,
                            "message" to readableMessage(throwable),
                        ),
                    )
                    throw throwable
                }
            }
        }
    }

    private fun getCachedWorkshopItem(
        cache: ConcurrentHashMap<Long, CachedValue<WorkshopItem>>,
        publishedFileId: Long,
    ): WorkshopItem? {
        return cache[publishedFileId]
            ?.takeIf { it.isFresh(WORKSHOP_ITEM_CACHE_MS) }
            ?.value
    }

    private fun createWorkshopStorageRoot(
        stagingTaskId: String,
        rootName: String,
        treeUri: String?,
        downloadFolderName: String?,
        existingRootRef: String?,
    ): WorkshopStorageRoot {
        val safeRootName = sanitizeFileName(rootName, "workshop-item")
        return when {
            existingRootRef?.startsWith(TREE_ROOT_REF_PREFIX) == true && !treeUri.isNullOrBlank() -> {
                val parent = DocumentFile.fromTreeUri(appContext, Uri.parse(treeUri))
                    ?: error("无法访问所选目录")
                val root = existingRootRef
                    .removePrefix(TREE_ROOT_REF_PREFIX)
                    .let { DocumentFile.fromTreeUri(appContext, Uri.parse(it)) }
                    ?: uniqueDirectoryForTree(parent, safeRootName)
                object : WorkshopStorageRoot {
                    override val rootUri: String = root.uri.toString()
                    override val resumeRef: String = TREE_ROOT_REF_PREFIX + root.uri.toString()

                    override fun ensureDirectory(relativePath: String) {
                        findOrCreateTreeDirectory(root, splitRelativePath(relativePath))
                    }

                    override fun existingFileSize(relativePath: String): Long {
                        val segments = splitRelativePath(relativePath)
                        val fileName = segments.lastOrNull() ?: return 0L
                        val parentDir = findOrCreateTreeDirectory(root, segments.dropLast(1))
                        return parentDir.findFile(fileName)?.length()?.coerceAtLeast(0L) ?: 0L
                    }

                    override fun openFile(relativePath: String, append: Boolean): OutputStream {
                        val segments = splitRelativePath(relativePath)
                        val fileName = segments.lastOrNull() ?: error("无效的文件路径")
                        val parentDir = findOrCreateTreeDirectory(root, segments.dropLast(1))
                        val existing = parentDir.findFile(fileName)
                        val document = when {
                            append && existing != null -> existing
                            else -> {
                                existing?.delete()
                                parentDir.createFile("application/octet-stream", fileName)
                                    ?: error("无法创建目标文件")
                            }
                        }
                        return appContext.contentResolver.openOutputStream(
                            document.uri,
                            if (append) "wa" else "w",
                        ) ?: error("无法打开目标文件")
                    }

                    override suspend fun exportResultUri(
                        treeUri: String?,
                        downloadFolderName: String?,
                        onPhaseChanged: suspend (String?) -> Unit,
                    ): String = rootUri
                }
            }

            existingRootRef?.startsWith(DOWNLOADS_ROOT_REF_PREFIX) == true && treeUri.isNullOrBlank() -> {
                check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    "公共下载目录需要 Android 10 及以上，请在设置中选择自定义根目录。"
                }
                val resolvedRootName = existingRootRef.removePrefix(DOWNLOADS_ROOT_REF_PREFIX)
                object : WorkshopStorageRoot {
                    override val rootUri: String = MEDIASTORE_DOWNLOADS_URI_STRING
                    override val resumeRef: String = DOWNLOADS_ROOT_REF_PREFIX + resolvedRootName

                    override fun ensureDirectory(relativePath: String) = Unit

                    override fun existingFileSize(relativePath: String): Long {
                        val existingUri = findMediaStoreDownloadUri(
                            context = appContext,
                            folderName = downloadFolderName,
                            rootName = resolvedRootName,
                            relativePath = relativePath,
                        ) ?: return 0L
                        return queryUriSize(appContext, existingUri)
                    }

                    override fun openFile(relativePath: String, append: Boolean): OutputStream {
                        val existingUri = findMediaStoreDownloadUri(
                            context = appContext,
                            folderName = downloadFolderName,
                            rootName = resolvedRootName,
                            relativePath = relativePath,
                        )
                        if (append && existingUri != null) {
                            return openExistingMediaStoreFileOutput(
                                context = appContext,
                                uri = existingUri,
                                append = true,
                            )
                        }
                        val (output, _) = createMediaStoreFileOutput(
                            context = appContext,
                            folderName = downloadFolderName,
                            rootName = resolvedRootName,
                            relativePath = relativePath,
                            replaceExisting = true,
                        )
                        return output
                    }

                    override suspend fun exportResultUri(
                        treeUri: String?,
                        downloadFolderName: String?,
                        onPhaseChanged: suspend (String?) -> Unit,
                    ): String = rootUri
                }
            }

            else -> {
                val localRoot = existingRootRef
                    ?.takeIf { it.startsWith(LOCAL_ROOT_REF_PREFIX) }
                    ?.removePrefix(LOCAL_ROOT_REF_PREFIX)
                    ?.let(::File)
                    ?: workshopDownloadStagingRoot(
                        context = appContext,
                        taskId = stagingTaskId,
                        rootName = safeRootName,
                    )
                localRoot.mkdirs()
                object : WorkshopStorageRoot {
                    override val rootUri: String = localRoot.toURI().toString()
                    override val resumeRef: String = LOCAL_ROOT_REF_PREFIX + localRoot.absolutePath

                    override fun ensureDirectory(relativePath: String) {
                        File(localRoot, relativePath).mkdirs()
                    }

                    override fun existingFileSize(relativePath: String): Long {
                        return File(localRoot, relativePath).takeIf { it.isFile }?.length()?.coerceAtLeast(0L) ?: 0L
                    }

                    override fun openFile(relativePath: String, append: Boolean): OutputStream {
                        val file = File(localRoot, relativePath)
                        file.parentFile?.mkdirs()
                        if (!append && file.exists()) {
                            file.delete()
                        }
                        return FileOutputStream(file, append)
                    }

                    override suspend fun exportResultUri(
                        treeUri: String?,
                        downloadFolderName: String?,
                        onPhaseChanged: suspend (String?) -> Unit,
                    ): String {
                        return exportLocalWorkshopRoot(
                            localRootDir = localRoot,
                            rootName = safeRootName,
                            treeUri = treeUri,
                            downloadFolderName = downloadFolderName,
                            onPhaseChanged = onPhaseChanged,
                        )
                    }
                }
            }
        }
    }

    private fun splitRelativePath(relativePath: String): List<String> {
        val cleanedSegments = relativePath
            .replace('\\', '/')
            .split('/')
            .filter { it.isNotBlank() && it != "." && it != ".." }
        if (cleanedSegments.isEmpty()) return emptyList()
        return cleanedSegments.mapIndexed { index, segment ->
            sanitizeFileName(
                raw = segment,
                fallback = if (index == cleanedSegments.lastIndex) "file" else "folder",
            )
        }
    }

    private fun normalizeRelativePath(
        rawPath: String,
        fallbackName: String,
    ): String {
        return splitRelativePath(rawPath).joinToString("/").ifBlank {
            sanitizeFileName(fallbackName, "workshop-file")
        }
    }

    private fun inferCompletedChunkCount(
        chunkSizes: List<Int>,
        existingSize: Long,
    ): Int {
        if (existingSize <= 0L) return 0
        var matchedBytes = 0L
        chunkSizes.forEachIndexed { index, chunkSize ->
            matchedBytes += chunkSize.toLong().coerceAtLeast(0L)
            if (matchedBytes == existingSize) {
                return index + 1
            }
            if (matchedBytes > existingSize) {
                return 0
            }
        }
        return if (matchedBytes == existingSize) chunkSizes.size else 0
    }

    private fun Throwable?.isPauseSignal(): Boolean {
        return generateSequence(this) { it.cause }.any { it is DownloadPausedException }
    }

    private fun createIsolatedCdnClient(
        forceDirect: Boolean,
        performanceProfile: DownloadPerformanceProfile,
    ): SteamCdnClient {
        val normalizedParallelism = performanceProfile.chunkConcurrency.coerceIn(1, MAX_DOWNLOAD_CHUNK_CONCURRENCY)
        val dispatcher = Dispatcher().apply {
            maxRequests = when (performanceProfile.mode) {
                DownloadPerformanceMode.Auto ->
                    (normalizedParallelism * 2).coerceAtLeast(performanceProfile.dispatcherRequestFloor)
                DownloadPerformanceMode.Compatibility ->
                    (normalizedParallelism + 1).coerceAtLeast(performanceProfile.dispatcherRequestFloor)
            }
            maxRequestsPerHost = when (performanceProfile.mode) {
                DownloadPerformanceMode.Auto ->
                    (normalizedParallelism + 2).coerceAtLeast(performanceProfile.dispatcherPerHostFloor)
                DownloadPerformanceMode.Compatibility ->
                    normalizedParallelism.coerceAtLeast(performanceProfile.dispatcherPerHostFloor)
            }
        }
        val connectionPool = cdnConnectionPoolCache.getOrPut(forceDirect) {
            ConnectionPool(64, 10, TimeUnit.MINUTES)
        }
        val isolatedHttpClient = okHttpClient.newBuilder()
            .dispatcher(dispatcher)
            .connectionPool(connectionPool)
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(CDN_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(CDN_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .apply {
                if (forceDirect) {
                    proxy(Proxy.NO_PROXY)
                }
            }
            .build()
        val configuration = SteamConfiguration.create {
            it.withProtocolTypes(EnumSet.of(ProtocolTypes.TCP, ProtocolTypes.WEB_SOCKET))
            it.withConnectionTimeout(60_000L)
            it.withHttpClient(isolatedHttpClient)
        }
        return SteamCdnClient(SteamClient(configuration))
    }

    private fun effectiveDownloadPerformanceProfile(
        requested: Int,
        mode: DownloadPerformanceMode,
    ): DownloadPerformanceProfile {
        val normalizedRequested = requested.coerceIn(1, MAX_DOWNLOAD_CHUNK_CONCURRENCY)
        val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val runtimeHeapMb = (Runtime.getRuntime().maxMemory() / (1024L * 1024L)).toInt().coerceAtLeast(1)
        val allocatedHeapMb = (
            (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) /
                (1024L * 1024L)
            ).toInt().coerceAtLeast(0)
        val memoryClassMb = activityManager?.memoryClass?.coerceAtLeast(1) ?: runtimeHeapMb
        val heapBudgetMb = minOf(runtimeHeapMb, memoryClassMb)
        val availableHeapMb = (runtimeHeapMb - allocatedHeapMb).coerceAtLeast(1)
        val isLowRamDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            activityManager?.isLowRamDevice == true
        } else {
            false
        }
        val ceiling = when (mode) {
            DownloadPerformanceMode.Auto -> when {
                availableHeapMb < 64 -> 2
                heapBudgetMb <= 160 -> if (availableHeapMb >= 128) 3 else 2
                heapBudgetMb <= 192 -> if (availableHeapMb >= 160) 4 else 3
                isLowRamDevice && availableHeapMb < 96 -> 2
                isLowRamDevice && heapBudgetMb <= 256 -> if (availableHeapMb >= 160) 4 else 3
                isLowRamDevice -> if (availableHeapMb >= 160) 5 else 4
                heapBudgetMb <= 256 -> 4
                heapBudgetMb <= 384 -> 6
                heapBudgetMb <= 512 -> 8
                heapBudgetMb <= 768 -> 10
                else -> 12
            }

            DownloadPerformanceMode.Compatibility -> when {
                availableHeapMb < 64 -> 1
                heapBudgetMb <= 192 -> if (availableHeapMb >= 128) 2 else 1
                isLowRamDevice || heapBudgetMb <= 256 -> 2
                heapBudgetMb <= 384 -> 2
                heapBudgetMb <= 512 -> 3
                else -> 4
            }
        }
        val chunkConcurrency = normalizedRequested.coerceAtMost(ceiling).coerceAtLeast(1)
        return when (mode) {
            DownloadPerformanceMode.Auto ->
                DownloadPerformanceProfile(
                    mode = mode,
                    requestedChunkConcurrency = normalizedRequested,
                    chunkConcurrency = chunkConcurrency,
                    maxRoutesPerTransport = MAX_CDN_ROUTES_PER_TRANSPORT,
                    maxActiveRoutes = MAX_ACTIVE_CDN_ROUTES,
                    dispatcherRequestFloor = 8,
                    dispatcherPerHostFloor = 4,
                    heapBudgetMb = heapBudgetMb,
                    availableHeapMb = availableHeapMb,
                    isLowRamDevice = isLowRamDevice,
                )

            DownloadPerformanceMode.Compatibility ->
                DownloadPerformanceProfile(
                    mode = mode,
                    requestedChunkConcurrency = normalizedRequested,
                    chunkConcurrency = chunkConcurrency,
                    maxRoutesPerTransport = 2,
                    maxActiveRoutes = 2,
                    dispatcherRequestFloor = 4,
                    dispatcherPerHostFloor = 2,
                    heapBudgetMb = heapBudgetMb,
                    availableHeapMb = availableHeapMb,
                    isLowRamDevice = isLowRamDevice,
                )
        }
    }

    private fun hasSystemProxy(): Boolean {
        val probeUri = URI("https://steamcommunity.com")
        return runCatching {
            ProxySelector.getDefault()
                ?.select(probeUri)
                .orEmpty()
                .any { proxy -> proxy != null && proxy.type() != Proxy.Type.DIRECT }
        }.getOrDefault(false)
    }

    private fun prioritizedTransportModes(
        preference: CdnTransportPreference,
    ): List<Boolean> {
        return when (preference) {
            CdnTransportPreference.PreferDirect -> {
                if (hasSystemProxy()) listOf(true, false) else listOf(true)
            }
            CdnTransportPreference.PreferSystem -> {
                if (hasSystemProxy()) listOf(false, true) else listOf(true)
            }
            CdnTransportPreference.Auto -> {
                if (!hasSystemProxy()) return listOf(true)
                when (lastSuccessfulCdnTransportDirect) {
                    true -> listOf(true, false)
                    false -> listOf(false, true)
                    null -> listOf(false, true)
                }
            }
        }
    }

    private fun getCachedWorkshopContentAccess(
        key: WorkshopContentAccessCacheKey,
    ): WorkshopContentAccess? {
        val cached = workshopContentAccessCache[key] ?: return null
        if (!cached.isFresh(WORKSHOP_CONTENT_ACCESS_CACHE_MS)) {
            workshopContentAccessCache.remove(key)
            return null
        }
        return cached.value
    }

    private suspend fun loadAuthenticatedCdnServers(
        contentHandler: SteamContent,
        taskId: String?,
        appId: Int,
        depotId: Int,
        manifestId: Long,
    ): List<Server> {
        supportDiagnosticsStore.incrementCounter("steam_rpc_authenticated_cdn_servers")
        authenticatedCdnServersCache
            ?.takeIf { it.isFresh(CDN_SERVER_LIST_CACHE_MS) }
            ?.value
            ?.let { cached ->
                return sortAvailableCdnServers(cached, "未找到可用的 Steam CDN 服务器")
            }

        return cdnServerCacheMutex.withLock {
            authenticatedCdnServersCache
                ?.takeIf { it.isFresh(CDN_SERVER_LIST_CACHE_MS) }
                ?.value
                ?.let { cached ->
                    return@withLock sortAvailableCdnServers(cached, "未找到可用的 Steam CDN 服务器")
                }

            val fetched = runDownloadStage(
                taskId = taskId,
                stage = "authenticated_cdn_servers",
                timeoutMs = DOWNLOAD_CDN_SERVER_LIST_TIMEOUT_MS,
                timeoutMessage = "获取 Steam CDN 列表超时，请检查当前网络、代理或 VPN 设置后重试",
                fields = mapOf(
                    "appId" to appId.toString(),
                    "depotId" to depotId.toString(),
                    "manifestId" to manifestId.toString(),
                ),
            ) {
                contentHandler.getServersForSteamPipe(
                    null,
                    CDN_SERVER_CANDIDATE_LIMIT,
                    appScope,
                ).await()
            }
            val servers = sortAvailableCdnServers(fetched, "未找到可用的 Steam CDN 服务器")
            authenticatedCdnServersCache = CachedValue(servers)
            servers
        }
    }

    private suspend fun loadAnonymousCdnServers(
        session: AnonymousContentSession,
    ): List<Server> {
        supportDiagnosticsStore.incrementCounter("steam_rpc_anonymous_cdn_servers")
        anonymousCdnServersCache
            ?.takeIf { it.isFresh(CDN_SERVER_LIST_CACHE_MS) }
            ?.value
            ?.let { cached ->
                return sortAvailableCdnServers(cached, "匿名 Steam 未返回 CDN 服务器")
            }

        return cdnServerCacheMutex.withLock {
            anonymousCdnServersCache
                ?.takeIf { it.isFresh(CDN_SERVER_LIST_CACHE_MS) }
                ?.value
                ?.let { cached ->
                    return@withLock sortAvailableCdnServers(cached, "匿名 Steam 未返回 CDN 服务器")
                }

            val fetched = session.content.getServersForSteamPipe(
                null,
                CDN_SERVER_CANDIDATE_LIMIT,
                session.callbackScope,
            ).await()
            val servers = sortAvailableCdnServers(fetched, "匿名 Steam 未返回 CDN 服务器")
            anonymousCdnServersCache = CachedValue(servers)
            servers
        }
    }

    private fun sortAvailableCdnServers(
        servers: List<Server>,
        emptyMessage: String,
    ): List<Server> {
        val sorted = servers
            .asSequence()
            .filter { !it.host.isNullOrBlank() || !it.vHost.isNullOrBlank() }
            .sortedWith(cdnServerComparator())
            .toList()
        val uniqueSorted = uniqueServersByHost(sorted)
        check(uniqueSorted.isNotEmpty()) { emptyMessage }
        return uniqueSorted
    }

    private fun createSeededServerListProvider(): MemoryServerListProvider {
        return MemoryServerListProvider().apply {
            updateServerList(
                seededCmServers
                    .flatMap { address ->
                        buildList {
                            ServerRecord.tryCreateSocketServer(address)?.let(::add)
                            add(ServerRecord.createWebSocketServer(address))
                        }
                    }
                    .distinct(),
            )
        }
    }

    private fun connectionProfileForAttempt(attempt: Int): ConnectionProfile {
        val orderedProfiles = prioritizedConnectionProfiles()
        return orderedProfiles[attempt.coerceAtMost(orderedProfiles.lastIndex)]
    }

    private fun prioritizedConnectionProfiles(): List<ConnectionProfile> {
        val preferredLabel = preferredConnectionProfileLabel ?: return primaryConnectionProfiles
        val preferredProfile = primaryConnectionProfiles.firstOrNull { it.label == preferredLabel }
            ?: return primaryConnectionProfiles
        return buildList {
            add(preferredProfile)
            addAll(primaryConnectionProfiles.filterNot { it.label == preferredLabel })
        }
    }

    private fun prioritizedAnonymousConnectionProfiles(): List<ConnectionProfile> {
        val tcpFirstOrder = listOf(
            "tcp-directory",
            "tcp-seeded",
            "tcp+ws-directory",
            "ws-directory",
            "ws-seeded",
        )
        val orderedProfiles = tcpFirstOrder.mapNotNull { label ->
            primaryConnectionProfiles.firstOrNull { it.label == label }
        }
        val preferredLabel = preferredConnectionProfileLabel ?: return orderedProfiles
        val preferredProfile = orderedProfiles.firstOrNull { it.label == preferredLabel }
            ?: return orderedProfiles
        return buildList {
            add(preferredProfile)
            addAll(orderedProfiles.filterNot { it.label == preferredLabel })
        }
    }

    private fun cdnProtocolPriority(server: Server): Int {
        return when (server.protocol.name) {
            "HTTPS" -> 0
            "HTTP" -> 1
            else -> 2
        }
    }

    private fun cdnHostPriority(server: Server): Int {
        val host = server.vHost?.takeIf(String::isNotBlank)
            ?: server.host?.takeIf(String::isNotBlank)
            ?: return 9
        val normalized = host.lowercase()
        return when {
            normalized.contains("google2.cdn.steampipe.steamcontent.com") -> 0
            normalized.contains("fastly.cdn.steampipe.steamcontent.com") -> 0
            normalized.contains("akamaized") -> 0
            normalized.contains("alibaba") -> 1
            normalized.contains("steamcontent.com") && !normalized.startsWith("cache") -> 2
            normalized.contains("steamcontent.com") && normalized.startsWith("cache") -> 3
            normalized.contains("steampipe") -> 4
            else -> 5
        }
    }

    private fun cdnPerformanceSamplePriority(server: Server): Int {
        val host = serverIdentity(server) ?: return 1
        val performance = cdnHostPerformanceTracker[host] ?: return 1
        return if (performance.hasSamples()) 0 else 1
    }

    private fun cdnPerformanceLatencyPriority(server: Server): Long {
        val host = serverIdentity(server) ?: return Long.MAX_VALUE
        return cdnHostPerformanceTracker[host]?.averageNanosPerMiB() ?: Long.MAX_VALUE
    }

    private fun cdnFailurePenalty(server: Server): Long {
        val host = serverIdentity(server) ?: return 0L
        return cdnHostPerformanceTracker[host]?.failurePenalty() ?: 0L
    }

    private fun matchesPreferredCdnPool(
        server: Server,
        preference: CdnPoolPreference,
    ): Boolean {
        val host = serverIdentity(server) ?: return false
        return when (preference) {
            CdnPoolPreference.Auto -> true
            CdnPoolPreference.TrustedOnly -> {
                host.contains("google2.cdn.steampipe.steamcontent.com") ||
                    host.contains("fastly.cdn.steampipe.steamcontent.com") ||
                    host.contains("akamaized")
            }
            CdnPoolPreference.PreferGoogle2 -> host.contains("google2.cdn.steampipe.steamcontent.com")
            CdnPoolPreference.PreferFastly -> host.contains("fastly.cdn.steampipe.steamcontent.com")
            CdnPoolPreference.PreferAkamai -> host.contains("akamaized")
        }
    }

    private fun prioritizeServersByUserPreference(
        servers: List<Server>,
        preference: CdnPoolPreference,
    ): List<Server> {
        if (preference == CdnPoolPreference.Auto) return servers
        val preferred = servers.filter { matchesPreferredCdnPool(it, preference) }
        if (preference == CdnPoolPreference.TrustedOnly && preferred.isNotEmpty()) {
            return preferred
        }
        return if (preferred.isNotEmpty()) {
            preferred + servers.filterNot { it in preferred }
        } else {
            servers
        }
    }

    private fun cdnServerComparator(): Comparator<Server> {
        val now = System.currentTimeMillis()
        return compareBy<Server>(
            { cdnCooldownPriority(it, now) },
            ::cdnPerformanceSamplePriority,
            ::cdnPerformanceLatencyPriority,
            ::cdnFailurePenalty,
            ::cdnLastSuccessPriority,
            ::cdnHostPriority,
            ::cdnProtocolPriority,
            { it.weightedLoad },
        )
    }

    private fun cdnCooldownPriority(server: Server, now: Long): Int {
        val host = serverIdentity(server) ?: return 0
        return cdnCooldownPriority(host, now)
    }

    private fun cdnCooldownPriority(hostName: String, now: Long): Int {
        val normalized = hostName.lowercase()
        val blockedUntil = cdnFailureTracker[normalized] ?: return 0
        return if (blockedUntil > now) {
            supportDiagnosticsStore.recordCdnCooldownHit(hostName)
            1
        } else {
            0
        }
    }

    private fun cdnLastSuccessPriority(hostName: String): Int {
        return if (hostName.lowercase() == lastSuccessfulCdnHost) 0 else 1
    }

    private fun cdnLastSuccessPriority(server: Server): Int {
        val host = serverIdentity(server) ?: return 1
        return cdnLastSuccessPriority(host)
    }

    private fun serverIdentity(server: Server): String? {
        return server.vHost?.takeIf(String::isNotBlank)
            ?.lowercase()
            ?: server.host?.takeIf(String::isNotBlank)?.lowercase()
    }

    private fun rememberSuccessfulCdnHost(hostName: String) {
        val normalized = hostName.lowercase()
        lastSuccessfulCdnHost = normalized
        cdnFailureTracker.remove(normalized)
    }

    private fun recordCdnFailure(hostName: String) {
        cdnFailureTracker[hostName.lowercase()] = System.currentTimeMillis() + CDN_FAILURE_COOLDOWN_MS
    }

    private fun findOrCreateTreeDirectory(
        root: DocumentFile,
        pathSegments: List<String>,
    ): DocumentFile {
        var current = root
        pathSegments.forEach { segment ->
            val existing = current.findFile(segment)
            current = when {
                existing == null -> current.createDirectory(segment)
                    ?: error("无法创建目录 $segment")
                existing.isDirectory -> existing
                else -> error("路径冲突：$segment 不是目录")
            }
        }
        return current
    }

    private fun uniqueDirectoryForTree(
        parent: DocumentFile,
        directoryName: String,
    ): DocumentFile {
        if (parent.findFile(directoryName) == null) {
            return parent.createDirectory(directoryName) ?: error("无法创建目录")
        }

        var index = 1
        while (parent.findFile("$directoryName ($index)") != null) {
            index++
        }
        return parent.createDirectory("$directoryName ($index)") ?: error("无法创建目录")
    }

    private suspend fun exportLocalWorkshopRoot(
        localRootDir: File,
        rootName: String,
        treeUri: String?,
        downloadFolderName: String?,
        onPhaseChanged: suspend (String?) -> Unit,
    ): String {
        check(localRootDir.exists() && localRootDir.isDirectory) { "本地暂存目录不存在" }
        return if (!treeUri.isNullOrBlank()) {
            exportLocalWorkshopRootToTree(
                localRootDir = localRootDir,
                rootName = rootName,
                treeUri = treeUri,
                onPhaseChanged = onPhaseChanged,
            )
        } else {
            exportLocalWorkshopRootToMediaStore(
                localRootDir = localRootDir,
                rootName = rootName,
                downloadFolderName = downloadFolderName,
                onPhaseChanged = onPhaseChanged,
            )
        }
    }

    private suspend fun exportLocalWorkshopRootToTree(
        localRootDir: File,
        rootName: String,
        treeUri: String,
        onPhaseChanged: suspend (String?) -> Unit,
    ): String {
        val parent = DocumentFile.fromTreeUri(appContext, Uri.parse(treeUri))
            ?: error("无法访问所选目录")
        val root = uniqueDirectoryForTree(parent, rootName)
        val files = localRootDir.walkTopDown().filter { it.isFile }.toList()
        var lastReportedAt = 0L
        suspend fun maybeReport(exportedFiles: Int, force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force &&
                exportedFiles < files.size &&
                now - lastReportedAt < EXPORT_PROGRESS_UPDATE_INTERVAL_MS
            ) {
                return
            }
            reportExportPhase(
                onPhaseChanged = onPhaseChanged,
                exportedFiles = exportedFiles,
                totalFiles = files.size,
            )
            lastReportedAt = now
        }
        maybeReport(exportedFiles = 0, force = true)
        try {
            files.forEachIndexed { index, source ->
                val relativeSegments = source.relativeTo(localRootDir)
                    .invariantSeparatorsPath
                    .split('/')
                    .filter(String::isNotBlank)
                val parentDir = findOrCreateTreeDirectory(root, relativeSegments.dropLast(1))
                val fileName = relativeSegments.lastOrNull() ?: error("无效的文件路径")
                val document = parentDir.createFile("application/octet-stream", fileName)
                    ?: error("无法创建目标文件")
                copyLocalFileToUri(
                    context = appContext,
                    source = source,
                    targetUri = document.uri,
                    bufferSize = WORKSHOP_FILE_WRITE_BUFFER_SIZE,
                )
                maybeReport(exportedFiles = index + 1)
            }
            onPhaseChanged(null)
            return root.uri.toString()
        } catch (throwable: Throwable) {
            root.delete()
            throw throwable
        }
    }

    private suspend fun exportLocalWorkshopRootToMediaStore(
        localRootDir: File,
        rootName: String,
        downloadFolderName: String?,
        onPhaseChanged: suspend (String?) -> Unit,
    ): String {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "公共下载目录需要 Android 10 及以上，请在设置中选择自定义根目录。"
        }
        val resolvedRootName = uniqueMediaStoreRootName(
            context = appContext,
            folderName = downloadFolderName,
            baseRootName = rootName,
        )
        val files = localRootDir.walkTopDown().filter { it.isFile }.toList()
        var lastReportedAt = 0L
        suspend fun maybeReport(exportedFiles: Int, force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force &&
                exportedFiles < files.size &&
                now - lastReportedAt < EXPORT_PROGRESS_UPDATE_INTERVAL_MS
            ) {
                return
            }
            reportExportPhase(
                onPhaseChanged = onPhaseChanged,
                exportedFiles = exportedFiles,
                totalFiles = files.size,
            )
            lastReportedAt = now
        }
        maybeReport(exportedFiles = 0, force = true)
        files.forEachIndexed { index, source ->
                val relativePath = source.relativeTo(localRootDir).invariantSeparatorsPath
                val uri = createMediaStoreFileUri(
                    context = appContext,
                    folderName = downloadFolderName,
                    rootName = resolvedRootName,
                    relativePath = relativePath,
                    replaceExisting = true,
                )
                try {
                    copyLocalFileToUri(
                        context = appContext,
                        source = source,
                        targetUri = uri,
                        bufferSize = WORKSHOP_FILE_WRITE_BUFFER_SIZE,
                    )
                    finalizeMediaStoreFile(appContext, uri)
                    maybeReport(exportedFiles = index + 1)
                } catch (throwable: Throwable) {
                    appContext.contentResolver.delete(uri, null, null)
                    throw throwable
                }
            }
        onPhaseChanged(null)
        return MEDIASTORE_DOWNLOADS_URI_STRING
    }

    private suspend fun reportExportPhase(
        onPhaseChanged: suspend (String?) -> Unit,
        exportedFiles: Int,
        totalFiles: Int,
    ) {
        val normalizedTotal = totalFiles.coerceAtLeast(1)
        val normalizedDone = exportedFiles.coerceIn(0, normalizedTotal)
        val label = if (normalizedTotal <= 1) {
            "正在整理文件…"
        } else {
            "正在整理文件… $normalizedDone/$normalizedTotal"
        }
        onPhaseChanged(label)
    }

    private interface WorkshopStorageRoot {
        val rootUri: String
        val resumeRef: String

        fun ensureDirectory(relativePath: String)

        fun existingFileSize(relativePath: String): Long

        fun openFile(relativePath: String, append: Boolean): OutputStream

        suspend fun exportResultUri(
            treeUri: String?,
            downloadFolderName: String?,
            onPhaseChanged: suspend (String?) -> Unit,
        ): String
    }

    private suspend fun authenticateWithCredentialsWithRetry(
        username: String,
        password: String,
        rememberSession: Boolean,
    ) = supervisorScope {
        var recoveryAttempts = 0
        while (true) {
            coroutineContext.ensureActive()
            val failure = runCatching {
                async {
                    authenticateWithCredentials(username, password, rememberSession)
                }.await()
            }.exceptionOrNull()
            if (failure == null) {
                lastSubmittedAuthCode = null
                return@supervisorScope
            }

            val recoverableFailure =
                failure is CancellationException ||
                    failure is AsyncJobFailedException ||
                    failure.message?.contains("must be connected", ignoreCase = true) == true
            if (!recoverableFailure || !coroutineContext.isActive) {
                throw failure
            }
            if (recoveryAttempts >= INTERACTIVE_LOGIN_RECOVERY_ATTEMPTS) {
                throw AuthSessionInterruptedException(
                    "Steam 登录会话已中断，请重试",
                    failure,
                )
            }

            recoveryAttempts += 1
            pendingAuthCode = null
            bufferedAuthCode = lastSubmittedAuthCode ?: bufferedAuthCode
            _sessionState.value = _sessionState.value.copy(
                status = SessionStatus.Connecting,
                challenge = null,
                errorMessage = null,
            )
            Log.w(
                LOG_TAG,
                "interactive auth session interrupted; reconnecting attempt=$recoveryAttempts/$INTERACTIVE_LOGIN_RECOVERY_ATTEMPTS hasBufferedCode=${!bufferedAuthCode.isNullOrBlank()}",
                failure,
            )
            connectForInteractiveLogin()
        }
    }

    private suspend fun authenticateWithCredentials(
        username: String,
        password: String,
        rememberSession: Boolean,
    ) {
        Log.i(LOG_TAG, "authenticateWithCredentials remember=$rememberSession")
        _sessionState.value = _sessionState.value.copy(
            status = SessionStatus.Authenticating,
            errorMessage = null,
        )

        val authDetails = AuthSessionDetails().apply {
            this.username = username
            this.password = password
            persistentSession = rememberSession
            authenticator = buildAuthenticator()
            deviceFriendlyName = machineName()
            clientOSType = EOSType.WinUnknown
        }

        val authSession = steamClient?.authentication
            ?.beginAuthSessionViaCredentials(authDetails)
            ?.await()
            ?: error("无法启动 Steam 登录会话")

        val pollResult = authSession.pollingWaitForResult().await()
        check(pollResult.refreshToken.isNotBlank()) { "Steam 未返回 refresh token" }
        val accountName = pollResult.accountName.ifBlank { username }

        preferencesStore.saveSession(
            accountName = accountName,
            refreshToken = pollResult.refreshToken,
            clientId = authSession.clientID,
            steamId64 = _sessionState.value.account?.steamId64 ?: 0L,
            rememberSession = rememberSession,
        )

        runCatching {
            restoreSessionWithRetries(
                accountName = accountName,
                refreshToken = pollResult.refreshToken,
                clientId = authSession.clientID,
                attempts = primaryConnectionProfiles.size,
                restore = true,
            )
        }.getOrElse { throwable ->
            throw IllegalStateException(
                "账号密码已验证，但 Steam 客户端连接失败。请检查代理或 VPN 是否允许 Steam 长连接。",
                throwable,
            )
        }
        lastSubmittedAuthCode = null
    }

    private suspend fun loginWithRefreshTokenInternal(
        accountName: String,
        refreshToken: String,
        clientId: Long,
        restore: Boolean,
    ) {
        Log.i(
            LOG_TAG,
            "loginWithRefreshTokenInternal restore=$restore remember=$pendingRememberSession hasClientId=${clientId > 0L}",
        )
        ensureConnected()

        pendingAccountName = accountName
        pendingRefreshToken = refreshToken
        logonDeferred = CompletableDeferred()
        latestLicenseList = emptyList()
        licenseListDeferred?.cancel()
        licenseListDeferred = CompletableDeferred()
        _sessionState.value = _sessionState.value.copy(
            status = SessionStatus.Authenticating,
            account = SteamAccountSession(accountName, _sessionState.value.account?.steamId64 ?: 0),
            challenge = null,
            errorMessage = null,
            isRestoring = restore,
        )

        steamUser?.logOn(
            LogOnDetails(
                username = accountName,
                accessToken = refreshToken,
                shouldRememberPassword = pendingRememberSession,
                loginID = uniqueLoginId(),
                machineName = machineName(),
                chatMode = ChatMode.NEW_STEAM_CHAT,
            ),
        ) ?: error("SteamUser handler 不可用")

        val result = logonDeferred?.await() ?: error("Steam 登录状态缺失")
        val account = result.getOrThrow()
        Log.i(LOG_TAG, "loginWithRefreshTokenInternal success")

        preferencesStore.saveSession(
            accountName = account.accountName,
            refreshToken = refreshToken,
            clientId = clientId,
            steamId64 = account.steamId64,
            rememberSession = pendingRememberSession,
        )
    }

    private suspend fun restoreSessionWithRetries(
        accountName: String,
        refreshToken: String,
        clientId: Long,
        attempts: Int,
        restore: Boolean,
    ) {
        var lastError: Throwable? = null

        repeat(attempts) { attempt ->
            activeConnectionProfile = connectionProfileForAttempt(attempt)
            Log.i(
                LOG_TAG,
                "restoreSessionWithRetries attempt=${attempt + 1}/$attempts profile=${activeConnectionProfile.label}",
            )
            if (attempt > 0) {
                val delayMs = (attempt * 1_500L).coerceAtMost(4_500L)
                Log.w(
                    LOG_TAG,
                    "restoreSessionWithRetries retry=${attempt + 1}/$attempts after=${lastError?.message ?: "unknown"} delayMs=$delayMs",
                )
                teardownClient()
                delay(delayMs)
            }

            val result = runCatching {
                loginWithRefreshTokenInternal(
                    accountName = accountName,
                    refreshToken = refreshToken,
                    clientId = clientId,
                    restore = restore,
                )
            }
            if (result.isSuccess) {
                return
            }

            lastError = result.exceptionOrNull()
            Log.w(LOG_TAG, "restoreSessionWithRetries failed attempt=${attempt + 1}/$attempts", lastError)
        }

        throw lastError ?: IllegalStateException("Steam 登录恢复失败")
    }

    private suspend fun ensureConnected() {
        ensureClient()
        if (isConnected) return

        val deferred = CompletableDeferred<Unit>().also { connectDeferred = it }
        Log.d(LOG_TAG, "ensureConnected connect start")
        steamClient?.connect() ?: error("Steam 客户端未初始化")
        deferred.await()
        Log.d(LOG_TAG, "ensureConnected connect success")
    }

    private suspend fun connectForInteractiveLogin(attempts: Int = primaryConnectionProfiles.size) {
        var lastError: Throwable? = null
        repeat(attempts) { attempt ->
            activeConnectionProfile = connectionProfileForAttempt(attempt)
            val result = runCatching {
                teardownClient()
                ensureConnected()
            }
            if (result.isSuccess) {
                return
            }

            lastError = result.exceptionOrNull()
            Log.w(
                LOG_TAG,
                "connectForInteractiveLogin failed attempt=${attempt + 1}/$attempts profile=${activeConnectionProfile.label}",
                lastError,
            )
            teardownClient()
            if (attempt < attempts - 1) {
                delay(600L * (attempt + 1))
            }
        }

        throw IllegalStateException(
            "无法连接到 Steam 登录服务，请检查当前网络、代理或 VPN 设置",
            lastError,
        )
    }

    private suspend fun ensureAuthenticated() {
        recoveryJob?.join()
        bootstrapJob?.join()
        if (hasUsableAuthenticatedDownloadSession()) return

        val prefs = preferencesStore.snapshot()
        check(prefs.isLoginFeatureEnabled) { "已在设置中关闭登录功能" }
        val accountName = _sessionState.value.account?.accountName
            ?.takeIf(String::isNotBlank)
            ?: prefs.accountName.takeIf(String::isNotBlank)
        val refreshToken = prefs.refreshToken.takeIf(String::isNotBlank)
            ?: pendingRefreshToken.takeIf(String::isNotBlank)
        Log.d(
            LOG_TAG,
            "ensureAuthenticated status=${_sessionState.value.status} hasAccount=${!accountName.isNullOrBlank()} hasRefresh=${!refreshToken.isNullOrBlank()} remember=${prefs.rememberSession}",
        )
        if (!accountName.isNullOrBlank() && !refreshToken.isNullOrBlank()) {
            pendingRememberSession = prefs.rememberSession
            restoreSessionWithRetries(
                accountName = accountName,
                refreshToken = refreshToken,
                clientId = prefs.clientId,
                attempts = primaryConnectionProfiles.size,
                restore = true,
            )
        }

        if (hasUsableAuthenticatedDownloadSession()) return
        error("当前未登录 Steam")
    }

    private fun currentSteamId64(): Long {
        return steamClient?.steamID?.convertToUInt64() ?: error("SteamID 不可用")
    }

    private suspend fun ensureClient() = sessionMutex.withLock {
        if (steamClient != null) return

        val profile = activeConnectionProfile
        val configuration = SteamConfiguration.create {
            it.withProtocolTypes(profile.protocolTypes)
            it.withConnectionTimeout(profile.connectionTimeoutMs)
            it.withDirectoryFetch(profile.useDirectoryFetch)
            profile.serverListProviderFactory?.invoke()?.let(it::withServerListProvider)
            it.withHttpClient(okHttpClient)
        }
        Log.i(
            LOG_TAG,
            "ensureClient create profile=${profile.label} protocolTypes=${profile.protocolTypes.joinToString("+")} timeout=${profile.connectionTimeoutMs} directoryFetch=${profile.useDirectoryFetch}",
        )

        steamClient = SteamClient(configuration)
        callbackManager = CallbackManager(steamClient!!)
        steamApps = steamClient!!.getHandler(SteamApps::class.java)
        steamContent = steamClient!!.getHandler(SteamContent::class.java)
        steamUser = steamClient!!.getHandler(SteamUser::class.java)
        unifiedMessages = steamClient!!.getHandler(SteamUnifiedMessages::class.java)
        playerService = unifiedMessages!!.createService(Player::class.java)
        publishedFileService = unifiedMessages!!.createService(PublishedFile::class.java)

        callbackSubscriptions += callbackManager!!.subscribe(ConnectedCallback::class.java, ::onConnected)
        callbackSubscriptions += callbackManager!!.subscribe(DisconnectedCallback::class.java, ::onDisconnected)
        callbackSubscriptions += callbackManager!!.subscribe(LicenseListCallback::class.java, ::onLicenseList)
        callbackSubscriptions += callbackManager!!.subscribe(LoggedOnCallback::class.java, ::onLoggedOn)
        callbackSubscriptions += callbackManager!!.subscribe(LoggedOffCallback::class.java, ::onLoggedOff)

        if (!callbackLoopStarted) {
            callbackLoopStarted = true
            appScope.launch {
                while (callbackLoopStarted) {
                    runCatching {
                        callbackManager?.runWaitCallbacks(1000L)
                    }
                }
            }
        }
    }

    private fun teardownClient() {
        Log.d(LOG_TAG, "teardownClient manualLogout=$manualLogout isConnected=$isConnected")
        callbackLoopStarted = false
        callbackSubscriptions.forEach(Closeable::close)
        callbackSubscriptions.clear()
        connectDeferred = null
        logonDeferred = null
        licenseListDeferred?.cancel()
        licenseListDeferred = null
        latestLicenseList = emptyList()
        steamApps = null
        steamContent = null
        steamUser = null
        playerService = null
        publishedFileService = null
        unifiedMessages = null
        callbackManager = null
        steamClient = null
        authenticatedCdnServersCache = null
        isConnected = false
        manualLogout = false
    }

    private fun buildAuthenticator(): IAuthenticator {
        return object : IAuthenticator {
            override fun acceptDeviceConfirmation(): CompletableFuture<Boolean> {
                Log.i(LOG_TAG, "acceptDeviceConfirmation requested; prefer fallback confirmation type")
                return CompletableFuture.completedFuture(false)
            }

            override fun getDeviceCode(previousCodeWasIncorrect: Boolean): CompletableFuture<String> {
                return requestCode(
                    AuthChallenge(
                        type = AuthChallengeType.SteamGuard,
                        previousCodeIncorrect = previousCodeWasIncorrect,
                    ),
                )
            }

            override fun getEmailCode(
                email: String?,
                previousCodeWasIncorrect: Boolean,
            ): CompletableFuture<String> {
                return requestCode(
                    AuthChallenge(
                        type = AuthChallengeType.Email,
                        emailHint = email,
                        previousCodeIncorrect = previousCodeWasIncorrect,
                    ),
                )
            }
        }
    }

    private fun requestCode(challenge: AuthChallenge): CompletableFuture<String> {
        if (challenge.previousCodeIncorrect) {
            bufferedAuthCode = null
            lastSubmittedAuthCode = null
        }
        val existingFuture = pendingAuthCode?.takeUnless { it.isDone || it.isCancelled }
        val future = existingFuture ?: CompletableFuture<String>().also { pendingAuthCode = it }
        val queuedCode = bufferedAuthCode
        if (!queuedCode.isNullOrBlank() && future.complete(queuedCode)) {
            bufferedAuthCode = null
            _sessionState.value = _sessionState.value.copy(
                status = SessionStatus.Authenticating,
                challenge = null,
                errorMessage = null,
            )
            return future
        }
        _sessionState.value = _sessionState.value.copy(
            status = SessionStatus.AwaitingCode,
            challenge = challenge,
            errorMessage = null,
        )
        return future
    }

    private fun onConnected(@Suppress("UNUSED_PARAMETER") callback: ConnectedCallback) {
        lastConnectedAtMs = System.currentTimeMillis()
        isConnected = true
        supportDiagnosticsStore.recordRecoveryEvent(
            action = "connected",
            fields = recoveryContextFields(),
        )
        Log.i(LOG_TAG, "onConnected")
        connectDeferred?.complete(Unit)
    }

    private fun onLicenseList(callback: LicenseListCallback) {
        val licenses = if (callback.result == EResult.OK) callback.licenseList else emptyList()
        latestLicenseList = licenses
        licenseListDeferred?.complete(licenses)
        licenseListDeferred = null
    }

    private fun onDisconnected(callback: DisconnectedCallback) {
        val currentState = _sessionState.value
        val interactiveLoginInProgress =
            interactiveLoginJob?.isActive == true &&
                currentState.status != SessionStatus.Authenticated
        val disconnectCategory = classifyDisconnectCategory(
            userInitiated = callback.isUserInitiated,
            interactiveLoginInProgress = interactiveLoginInProgress,
            restoring = currentState.isRestoring,
        )
        val disconnectDetail = buildString {
            append("transport=")
            append(lastObservedNetworkTransport ?: "unknown")
            append(",networkAvailable=")
            append(networkAvailable?.toString() ?: "unknown")
            append(",networkValidated=")
            append(networkValidated?.toString() ?: "unknown")
        }
        lastDisconnectedAtMs = System.currentTimeMillis()
        lastDisconnectCategory = disconnectCategory
        lastDisconnectDetail = disconnectDetail
        lastDisconnectUserInitiated = callback.isUserInitiated
        lastDisconnectWhileForeground = appInForeground
        supportDiagnosticsStore.recordRecoveryEvent(
            action = "disconnected",
            fields = recoveryContextFields(
                "userInitiated" to callback.isUserInitiated.toString(),
                "manualLogout" to manualLogout.toString(),
                "disconnectCategory" to disconnectCategory,
                "disconnectDetail" to disconnectDetail,
            ),
        )
        Log.w(
            LOG_TAG,
            "onDisconnected userInitiated=${callback.isUserInitiated} manualLogout=$manualLogout category=$disconnectCategory",
        )
        isConnected = false
        connectDeferred?.completeExceptionally(IllegalStateException("Steam 连接中断"))
        connectDeferred = null

        if (manualLogout || callback.isUserInitiated) {
            teardownClient()
            return
        }

        logonDeferred?.complete(Result.failure(IllegalStateException("Steam 连接已断开")))
        logonDeferred = null

        if (interactiveLoginInProgress) {
            Log.w(LOG_TAG, "onDisconnected during interactive login; skip global recovery and let login flow retry")
            return
        }
        val canReconnectOnDemand =
            currentState.status == SessionStatus.Authenticated &&
                !currentState.account?.accountName.isNullOrBlank() &&
                pendingRefreshToken.isNotBlank()
        if (canReconnectOnDemand) {
            pendingForegroundRecovery = !appInForeground
            teardownClient()
            _sessionState.value = currentState.copy(
                status = SessionStatus.Authenticated,
                challenge = null,
                errorMessage = null,
                isRestoring = true,
            )
            if (!appInForeground) {
                Log.i(LOG_TAG, "onDisconnected keep authenticated shell and defer reconnect until foreground")
                return
            }
            Log.i(LOG_TAG, "onDisconnected keep authenticated shell and recover immediately in foreground")
            scheduleSessionRecovery("Steam 连接已断开，正在恢复登录状态")
            return
        }

        if (_sessionState.value.isRestoring) {
            pendingForegroundRecovery = pendingForegroundRecovery || !appInForeground
            Log.w(LOG_TAG, "onDisconnected while restoring; skip nested recovery")
            return
        }

        scheduleSessionRecovery("Steam 连接已断开，正在恢复登录状态")
    }

    private fun onLoggedOn(callback: LoggedOnCallback) {
        supportDiagnosticsStore.recordRecoveryEvent(
            action = "logged_on",
            fields = recoveryContextFields("result" to callback.result.name),
        )
        Log.i(LOG_TAG, "onLoggedOn result=${callback.result}")
        if (callback.result == EResult.OK) {
            preferredConnectionProfileLabel = activeConnectionProfile.label
            appScope.launch {
                preferencesStore.saveLastConnectionProfile(activeConnectionProfile.label)
            }
            val session = SteamAccountSession(
                accountName = pendingAccountName,
                steamId64 = currentSteamId64(),
            )
            if (ownedGamesCacheSteamId64 != 0L && ownedGamesCacheSteamId64 != session.steamId64) {
                clearSessionCaches()
            }
            connectionRevision += 1L
            _sessionState.value = SteamSessionState(
                status = SessionStatus.Authenticated,
                account = session,
                connectionRevision = connectionRevision,
            )
            logonDeferred?.complete(Result.success(session))
            logonDeferred = null
            return
        }

        val message = "Steam 登录失败：${callback.result}"
        val failure = IllegalStateException(message)
        _sessionState.value = SteamSessionState(
            status = SessionStatus.Error,
            account = _sessionState.value.account?.copy(accountName = pendingAccountName),
            errorMessage = message,
            connectionRevision = connectionRevision,
        )
        logonDeferred?.complete(Result.failure(failure))
        logonDeferred = null
    }

    private fun onLoggedOff(callback: LoggedOffCallback) {
        supportDiagnosticsStore.recordRecoveryEvent(
            action = "logged_off",
            fields = recoveryContextFields(
                "result" to callback.result.name,
                "manualLogout" to manualLogout.toString(),
            ),
        )
        Log.w(LOG_TAG, "onLoggedOff result=${callback.result} manualLogout=$manualLogout")
        if (_sessionState.value.isRestoring) {
            Log.w(LOG_TAG, "onLoggedOff while restoring; skip nested recovery")
            return
        }
        if (!manualLogout) {
            scheduleSessionRecovery("Steam 已登出：${callback.result}")
        }
    }

    private fun scheduleSessionRecovery(reason: String) {
        if (recoveryJob?.isActive == true) return
        supportDiagnosticsStore.recordRecoveryEvent(
            action = "schedule_recovery",
            fields = recoveryContextFields("reason" to reason),
        )
        Log.w(LOG_TAG, "scheduleSessionRecovery reason=$reason")

        recoveryJob = appScope.launch {
            pendingForegroundRecovery = false
            val previousAccount = _sessionState.value.account
            val keepAuthenticatedShell =
                _sessionState.value.status == SessionStatus.Authenticated &&
                    (previousAccount?.steamId64 ?: 0L) > 0L
            val prefs = preferencesStore.snapshot()
            if (!prefs.rememberSession || prefs.refreshToken.isBlank() || prefs.accountName.isBlank()) {
                pendingForegroundRecovery = false
                teardownClient()
                _sessionState.value = SteamSessionState(
                    status = SessionStatus.Error,
                    account = previousAccount,
                    errorMessage = reason,
                    connectionRevision = connectionRevision,
                )
                return@launch
            }

            pendingRememberSession = prefs.rememberSession
            teardownClient()
            _sessionState.value = SteamSessionState(
                status = if (keepAuthenticatedShell) {
                    SessionStatus.Authenticated
                } else {
                    SessionStatus.Connecting
                },
                account = previousAccount ?: SteamAccountSession(prefs.accountName, prefs.steamId64),
                isRestoring = true,
                connectionRevision = connectionRevision,
            )

            runCatching {
                restoreSessionWithRetries(
                    accountName = prefs.accountName,
                    refreshToken = prefs.refreshToken,
                    clientId = prefs.clientId,
                    attempts = primaryConnectionProfiles.size,
                    restore = true,
                )
            }.onFailure { throwable ->
                Log.w(LOG_TAG, "scheduleSessionRecovery failed", throwable)
                pendingForegroundRecovery = false
                teardownClient()
                _sessionState.value = SteamSessionState(
                    status = SessionStatus.Error,
                    account = previousAccount ?: SteamAccountSession(prefs.accountName, prefs.steamId64),
                    errorMessage = readableMessage(throwable),
                    connectionRevision = connectionRevision,
                )
            }
        }.also { job ->
            job.invokeOnCompletion {
                if (recoveryJob === job) {
                    recoveryJob = null
                }
            }
        }
    }

    private fun machineName(): String {
        return listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "Workshop Native" }
            .take(32)
    }

    private fun uniqueLoginId(): Int {
        val androidId = Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
        return (androidId ?: "workshop-native").hashCode() and Int.MAX_VALUE
    }

    private suspend fun awaitLicenseList(): List<License> {
        if (latestLicenseList.isNotEmpty()) return latestLicenseList
        return withTimeoutOrNull(3_000L) {
            licenseListDeferred?.await()
        }.orEmpty()
    }

    private fun currentAccountId(): Long {
        return steamClient?.steamID?.accountID ?: error("Steam AccountID 不可用")
    }

    private fun ownerAccountIdToSteamId64(ownerAccountId: Int): Long? {
        if (ownerAccountId <= 0) return null
        val universe = steamClient?.steamID?.accountUniverse ?: return null
        return SteamID(ownerAccountId.toLong(), universe, EAccountType.Individual).convertToUInt64()
    }

    private fun buildWorkshopBrowseUrl(
        appId: Int,
        query: WorkshopBrowseQuery,
    ) = STEAM_COMMUNITY_URL.toHttpUrl().newBuilder().apply {
        val createdDateRange = query.createdDateRange.normalized()
        val updatedDateRange = query.updatedDateRange.normalized()
        addQueryParameter("appid", appId.toString())
        addQueryParameter("l", "schinese")
        addQueryParameter("actualsort", query.sortKey)
        addQueryParameter("browsesort", query.sortKey)
        addQueryParameter("p", query.page.toString())
        addQueryParameter("numperpage", query.pageSize.toString())
        if (query.sectionKey.isNotBlank()) {
            addQueryParameter("section", query.sectionKey)
        }
        if (query.sortKey == WorkshopBrowseQuery.SORT_TREND) {
            addQueryParameter("days", query.periodDays.toString())
        }
        if (query.searchText.isNotBlank()) {
            addQueryParameter("searchtext", query.searchText)
        }
        query.requiredTags.sorted().forEach { tag ->
            addQueryParameter("requiredtags[]", tag)
        }
        query.excludedTags.sorted().forEach { tag ->
            addQueryParameter("excludedtags[]", tag)
        }
        if (query.showIncompatible) {
            addQueryParameter("requiredflags[]", "incompatible")
        }
        appendWorkshopDateRangeQueryParameters(
            prefix = "created",
            range = createdDateRange,
        )
        appendWorkshopDateRangeQueryParameters(
            prefix = "updated",
            range = updatedDateRange,
        )
    }.build()

    private fun workshopBrowseCacheKey(
        appId: Int,
        query: WorkshopBrowseQuery,
    ): String {
        return buildWorkshopBrowseUrl(appId, query).toString()
    }

    private fun workshopSubscriptionsCacheKey(
        steamId64: Long,
        appId: Int,
        page: Int,
        pageSize: Int,
    ): String {
        return "subscriptions:$steamId64:$appId:$page:$pageSize"
    }

    private fun workshopAuthenticatedQueryCacheKey(
        steamId64: Long,
        appId: Int,
        query: WorkshopBrowseQuery,
    ): String {
        return buildString {
            append("auth-query:")
            append(steamId64)
            append(':')
            append(appId)
            append(':')
            append(query.page)
            append(':')
            append(query.pageSize)
            append(':')
            append(query.searchText.trim().lowercase())
        }
    }

    private fun parseWorkshopBrowsePage(
        appId: Int,
        query: WorkshopBrowseQuery,
        html: String,
    ): WorkshopBrowsePage {
        val previewById = WORKSHOP_PREVIEW_REGEX.findAll(html)
            .associate { match ->
                match.groupValues[1].toLong() to decodeHtml(match.groupValues[2])
            }
        val authorById = WORKSHOP_AUTHOR_REGEX.findAll(html)
            .associate { match ->
                match.groupValues[1].toLong() to htmlToPlainText(match.groupValues[2])
            }

        val items = WORKSHOP_HOVER_REGEX.findAll(html)
            .mapNotNull { match ->
                val publishedFileId = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                val payload = runCatching {
                    json.parseToJsonElement(match.groupValues[2]).jsonObject
                }.getOrNull() ?: return@mapNotNull null
                WorkshopItem(
                    publishedFileId = publishedFileId,
                    appId = payload["appid"]?.jsonPrimitive?.intOrNull ?: appId,
                    title = payload["title"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf(String::isNotBlank)
                        ?: "Workshop #$publishedFileId",
                    shortDescription = htmlToPlainText(
                        payload["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    ),
                    description = htmlToPlainText(
                        payload["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    ),
                    previewUrl = previewById[publishedFileId],
                    authorName = authorById[publishedFileId].orEmpty(),
                    detailUrl = "https://steamcommunity.com/sharedfiles/filedetails/?id=$publishedFileId",
                    fileUrl = null,
                    fileName = null,
                    fileSize = 0L,
                    timeUpdated = 0L,
                    subscriptions = 0,
                    creatorSteamId = 0L,
                    contentManifestId = 0L,
                    childPublishedFileIds = emptyList(),
                    tags = emptyList(),
                    isSubscribed = false,
                    isDownloadInfoResolved = false,
                )
            }
            .toList()

        val totalCountFromSummary = parseWorkshopTotalCount(html)
        val maxPage = WORKSHOP_PAGE_LINK_REGEX.findAll(html)
            .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
            .maxOrNull()
            ?.coerceAtLeast(query.page)
            ?: query.page
        val totalCount = totalCountFromSummary
            ?: inferWorkshopTotalCount(
                currentPage = query.page,
                maxPage = maxPage,
                currentPageItemCount = items.size,
                pageSize = query.pageSize,
            )

        return WorkshopBrowsePage(
            items = items,
            totalCount = totalCount,
            page = query.page,
            hasMore = query.page < maxPage || query.page * query.pageSize < totalCount,
            sectionOptions = ensureCurrentWorkshopSection(
                sections = parseWorkshopSections(html),
                currentSectionKey = query.sectionKey,
            ),
            sortOptions = parseWorkshopSortOptions(html),
            periodOptions = parseWorkshopPeriodOptions(html),
            tagGroups = parseWorkshopTagGroups(html),
            supportsIncompatibleFilter = html.contains("incompatibleCheckbox"),
        )
    }

    private fun inferWorkshopTotalCount(
        currentPage: Int,
        maxPage: Int,
        currentPageItemCount: Int,
        pageSize: Int,
    ): Int {
        if (maxPage <= 1) return currentPageItemCount
        val assumedLastPageSize = if (currentPage == maxPage && currentPageItemCount in 1 until pageSize) {
            currentPageItemCount
        } else {
            pageSize
        }
        return ((maxPage - 1) * pageSize) + assumedLastPageSize
    }

    private fun parseWorkshopSections(html: String): List<WorkshopBrowseSectionOption> {
        return WORKSHOP_SECTION_REGEX.findAll(html)
            .map {
                val key = it.groupValues[1]
                val parsedLabel = htmlToPlainText(decodeHtml(it.groupValues[2]))
                WorkshopBrowseSectionOption(
                    key = key,
                    label = parsedLabel.takeIf(String::isNotBlank) ?: currentSectionLabel(key),
                )
            }
            .distinctBy(WorkshopBrowseSectionOption::key)
            .toList()
    }

    private fun ensureCurrentWorkshopSection(
        sections: List<WorkshopBrowseSectionOption>,
        currentSectionKey: String,
    ): List<WorkshopBrowseSectionOption> {
        val mutableSections = sections.toMutableList()
        if (mutableSections.none { it.key == currentSectionKey }) {
            mutableSections.add(
                0,
                WorkshopBrowseSectionOption(
                    key = currentSectionKey,
                    label = currentSectionLabel(currentSectionKey),
                ),
            )
        }
        if (mutableSections.none { it.key == WorkshopBrowseQuery.SECTION_ITEMS }) {
            mutableSections.add(
                0,
                WorkshopBrowseSectionOption(
                    key = WorkshopBrowseQuery.SECTION_ITEMS,
                    label = currentSectionLabel(WorkshopBrowseQuery.SECTION_ITEMS),
                ),
            )
        }
        return mutableSections.distinctBy(WorkshopBrowseSectionOption::key)
    }

    private fun currentSectionLabel(sectionKey: String): String {
        return when (sectionKey) {
            WorkshopBrowseQuery.SECTION_MY_SUBSCRIPTIONS -> "我的订阅"
            WorkshopBrowseQuery.SECTION_ITEMS -> "项目"
            "collections" -> "合集"
            else -> sectionKey
        }
    }

    private fun parseWorkshopSortOptions(html: String): List<WorkshopBrowseSortOption> {
        val decoded = parseWorkshopSortingDropdownHtml(html).firstOrNull().orEmpty()
        return WORKSHOP_SORT_OPTION_REGEX.findAll(decoded)
            .map {
                val key = it.groupValues[1]
                WorkshopBrowseSortOption(
                    key = key,
                    label = htmlToPlainText(it.groupValues[2]),
                    supportsPeriod = key == WorkshopBrowseQuery.SORT_TREND,
                )
            }
            .distinctBy(WorkshopBrowseSortOption::key)
            .toList()
    }

    private fun parseWorkshopPeriodOptions(html: String): List<WorkshopBrowsePeriodOption> {
        val decoded = parseWorkshopSortingDropdownHtml(html).getOrNull(1).orEmpty()
        return WORKSHOP_PERIOD_OPTION_REGEX.findAll(decoded)
            .mapNotNull {
                val days = it.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                WorkshopBrowsePeriodOption(
                    days = days,
                    label = htmlToPlainText(it.groupValues[2]),
                )
            }
            .distinctBy(WorkshopBrowsePeriodOption::days)
            .toList()
    }

    private fun parseWorkshopSortingDropdownHtml(html: String): List<String> {
        val controls = WORKSHOP_SORTING_CONTROLS_REGEX.find(html)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
        if (controls.isBlank()) return emptyList()
        return WORKSHOP_DROPDOWN_HTML_REGEX.findAll(controls)
            .map { match -> decodeHtml(match.groupValues[1]) }
            .toList()
    }

    private fun parseWorkshopTotalCount(html: String): Int? {
        return sequenceOf(
            WORKSHOP_TOTAL_ZH_REGEX.find(html)?.groupValues?.getOrNull(1),
            WORKSHOP_TOTAL_EN_REGEX.find(html)?.groupValues?.getOrNull(1),
        )
            .filterNotNull()
            .map { value -> value.replace(",", "").toIntOrNull() }
            .firstOrNull { value -> value != null }
    }

    private fun okhttp3.HttpUrl.Builder.appendWorkshopDateRangeQueryParameters(
        prefix: String,
        range: WorkshopDateRangeFilter,
    ) {
        if (range.startEpochSeconds > 0L) {
            addQueryParameter("${prefix}_date_range_filter_start", range.startEpochSeconds.toString())
        }
        if (range.endEpochSeconds > 0L) {
            addQueryParameter("${prefix}_date_range_filter_end", range.endEpochSeconds.toString())
        }
    }

    private fun parseWorkshopTagGroups(html: String): List<WorkshopBrowseTagGroup> {
        val document = Jsoup.parse(html)
        val filterRoot = document.selectFirst("div.rightDetailsBlock") ?: return emptyList()
        val groups = mutableListOf<WorkshopBrowseTagGroup>()
        var currentLabel: String? = null
        var currentMode = WorkshopBrowseTagGroupSelectionMode.IncludeExclude
        var currentTags = mutableListOf<WorkshopBrowseTagOption>()

        fun flushCurrentGroup() {
            val label = currentLabel?.takeIf(String::isNotBlank) ?: return
            if (currentTags.isEmpty()) return
            groups += WorkshopBrowseTagGroup(
                label = label,
                tags = currentTags.toList(),
                selectionMode = currentMode,
            )
            currentLabel = null
            currentMode = WorkshopBrowseTagGroupSelectionMode.IncludeExclude
            currentTags = mutableListOf()
        }

        filterRoot.children().forEach { element ->
            when {
                element.hasClass("tag_category_desc") -> {
                    flushCurrentGroup()
                    currentLabel = element.text().trim()
                }

                element.tagName().equals("select", ignoreCase = true) &&
                    element.hasClass("selectTagsFilter") -> {
                    val label = currentLabel?.takeIf(String::isNotBlank) ?: return@forEach
                    val options = element.select("option[value]")
                        .mapNotNull { option ->
                            val value = option.attr("value").trim()
                            if (value.isBlank() || value == "-1") {
                                null
                            } else {
                                WorkshopBrowseTagOption(
                                    value = value,
                                    label = option.text().trim(),
                                )
                            }
                        }
                    if (options.isNotEmpty()) {
                        groups += WorkshopBrowseTagGroup(
                            label = label,
                            tags = options,
                            selectionMode = WorkshopBrowseTagGroupSelectionMode.SingleSelect,
                        )
                    }
                    currentLabel = null
                }

                element.hasClass("filterOption") -> {
                    val input = element.selectFirst("input.inputTagsFilter[name=\"requiredtags[]\"][value]") ?: return@forEach
                    if (currentLabel.isNullOrBlank()) {
                        currentLabel = "标签"
                    }
                    currentMode = WorkshopBrowseTagGroupSelectionMode.IncludeExclude
                    currentTags += WorkshopBrowseTagOption(
                        value = input.attr("value").trim(),
                        label = element.selectFirst("label")?.text()?.trim().orEmpty(),
                    )
                }
            }
        }

        flushCurrentGroup()
        return groups
    }

    private fun parseTagOptions(segment: String): List<WorkshopBrowseTagOption> {
        return WORKSHOP_TAG_OPTION_REGEX.findAll(segment)
            .map {
                WorkshopBrowseTagOption(
                    value = decodeHtml(it.groupValues[1]),
                    label = htmlToPlainText(it.groupValues[2]),
                )
            }
            .distinctBy(WorkshopBrowseTagOption::value)
            .toList()
    }

    private fun decodeHtml(value: String): String {
        return HtmlCompat.fromHtml(value, HtmlCompat.FROM_HTML_MODE_LEGACY)
            .toString()
            .trim()
    }

    private fun htmlToDisplayText(value: String): String {
        val normalized = value
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</(div|p|h[1-6]|li)>"), "\n")
            .replace(Regex("(?i)<li[^>]*>"), "• ")
        return decodeHtml(normalized)
            .replace(Regex("[\\t\\x0B\\f\\r ]+"), " ")
            .replace(Regex(" *\n *"), "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun clearSessionCaches(clearAnonymousContentAccess: Boolean = false) {
        ownedGamesCacheSteamId64 = 0L
        ownedGamesCache = null
        workshopItemCache.clear()
        downloadWorkshopItemCache.clear()
        workshopBrowsePageCache.clear()
        workshopAuthenticatedQueryPageCache.clear()
        workshopSubscriptionsPageCache.clear()
        workshopGamePageCache.clear()
        workshopGameSearchCache.clear()
        if (clearAnonymousContentAccess) {
            anonymousCdnServersCache = null
        }
        workshopContentAccessCache.entries.removeIf { entry ->
            clearAnonymousContentAccess || entry.key.authMode != DownloadAuthMode.Anonymous
        }
    }

    private fun persistOwnedGamesSnapshot(
        steamId64: Long,
        games: List<OwnedGame>,
    ) {
        if (steamId64 <= 0L || games.isEmpty()) return
        appScope.launch {
            runCatching {
                preferencesStore.saveOwnedGamesSnapshot(
                    steamId64 = steamId64,
                    payloadJson = json.encodeToString(games),
                )
            }.onFailure { throwable ->
                Log.w(LOG_TAG, "persistOwnedGamesSnapshot failed", throwable)
            }
        }
    }

    private inline fun <K, V> Iterable<K>.associateWithNotNull(
        valueTransform: (K) -> V?,
    ): Map<K, V> {
        val destination = LinkedHashMap<K, V>()
        for (element in this) {
            valueTransform(element)?.let { value ->
                destination[element] = value
            }
        }
        return destination
    }

    private fun htmlToPlainText(value: String): String {
        return decodeHtml(value)
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun WorkshopItem.needsWorkshopDetailPageFallback(): Boolean {
        return description.isBlank() || title.isBlank() || title.startsWith("Workshop #")
    }

    private fun WorkshopItem.mergeWorkshopDetailPage(
        detailPage: WorkshopDetailPageContent,
    ): WorkshopItem {
        val mergedDescription = when {
            detailPage.description.isBlank() -> description
            description.isBlank() -> detailPage.description
            detailPage.description.length >= description.length -> detailPage.description
            else -> description
        }
        val mergedShortDescription = when {
            shortDescription.isNotBlank() -> shortDescription
            mergedDescription.isBlank() -> shortDescription
            else -> mergedDescription.lineSequence().firstOrNull().orEmpty()
        }
        return copy(
            title = detailPage.title.ifBlank { title },
            shortDescription = mergedShortDescription,
            description = mergedDescription,
        )
    }

    private suspend fun loadWorkshopDetailPageContent(
        item: WorkshopItem,
    ): WorkshopDetailPageContent? = withContext(Dispatchers.IO) {
        val detailUrl = (item.detailUrl
            ?: "https://steamcommunity.com/sharedfiles/filedetails/?id=${item.publishedFileId}")
            .toHttpUrl()
            .newBuilder()
            .setQueryParameter("id", item.publishedFileId.toString())
            .setQueryParameter("l", "schinese")
            .build()
        val request = Request.Builder()
            .url(detailUrl)
            .header("User-Agent", "Mozilla/5.0")
            .get()
            .build()
        runCatching {
            val html = okHttpClient.newCall(request).execute().use { response ->
                check(response.isSuccessful) {
                    "Steam workshop detail page failed: HTTP ${response.code}"
                }
                response.body?.string().orEmpty()
            }
            val document = Jsoup.parse(html)
            val title = document.selectFirst("div.workshopItemTitle")
                ?.text()
                .orEmpty()
            val description = document.selectFirst("div.workshopItemDescription#highlightContent")
                ?.html()
                ?.let(::htmlToDisplayText)
                .orEmpty()
            WorkshopDetailPageContent(
                title = title,
                description = description,
            ).takeIf { it.title.isNotBlank() || it.description.isNotBlank() }
        }.onFailure { throwable ->
            Log.w(
                LOG_TAG,
                "loadWorkshopDetailPageContent failed publishedFileId=${item.publishedFileId}",
                throwable,
            )
        }.getOrNull()
    }

    private fun extractPackageAppIds(packageInfo: PICSProductInfo): Set<Int> {
        val appIdsNode = packageInfo.keyValues.childOrNull("appids") ?: return emptySet()
        return appIdsNode.children
            .mapNotNull { child ->
                val rawValue = child.value
                    ?: runCatching { child.asString() }.getOrNull()
                    ?: child.name
                rawValue?.toIntOrNull()
            }
            .filter { it > 0 }
            .toSet()
    }

    private fun extractAppMetadata(appInfo: PICSProductInfo): AppMetadata? {
        val common = appInfo.keyValues.childOrNull("common") ?: return null
        val name = common.stringOrNull("name") ?: return null
        val type = common.stringOrNull("type").orEmpty().lowercase()
        val iconHash = common.stringOrNull("icon") ?: common.stringOrNull("clienticon").orEmpty()
        return AppMetadata(
            name = name,
            iconHash = iconHash,
            type = type,
        )
    }

    private fun mapPublicPublishedFileDetails(
        details: PublicPublishedFileDetails,
    ): WorkshopItem? {
        if (details.result != 1) return null
        val fileType = details.fileType ?: 0
        if (fileType == 2 || fileType !in setOf(0, 3, 5, 10, 11, 12)) return null

        return WorkshopItem(
            publishedFileId = details.publishedFileId,
            appId = (details.consumerAppId?.takeIf { it > 0 } ?: 0L).toInt(),
            title = details.title.ifBlank { "Workshop #${details.publishedFileId}" },
            shortDescription = details.shortDescription.orEmpty(),
            description = details.fileDescription.orEmpty(),
            previewUrl = details.previewUrl?.takeIf(String::isNotBlank),
            authorName = "",
            detailUrl = "https://steamcommunity.com/sharedfiles/filedetails/?id=${details.publishedFileId}",
            fileUrl = details.fileUrl?.takeIf(String::isNotBlank),
            fileName = details.filename?.takeIf(String::isNotBlank),
            fileSize = details.fileSize ?: 0L,
            timeUpdated = details.timeUpdated ?: 0L,
            subscriptions = (details.subscriptions ?: 0L).toInt(),
            creatorSteamId = details.creator ?: 0L,
            contentManifestId = details.hcontentFile ?: 0L,
            childPublishedFileIds = details.children.orEmpty()
                .mapNotNull { it.publishedFileId }
                .filter { it > 0L }
                .distinct(),
            tags = details.tags.orEmpty()
                .mapNotNull { tag ->
                    when {
                        !tag.displayName.isNullOrBlank() -> tag.displayName
                        !tag.tag.isNullOrBlank() -> tag.tag
                        else -> null
                    }
                }
                .distinct(),
            isSubscribed = false,
            isDownloadInfoResolved = true,
        )
    }

    private fun mapPublishedFileDetails(
        details: SteammessagesPublishedfileSteamclient.PublishedFileDetails,
    ): WorkshopItem {
        val tags = details.tagsList.mapNotNull { tag ->
            when {
                tag.displayName.isNotBlank() -> tag.displayName
                tag.tag.isNotBlank() -> tag.tag
                else -> null
            }
        }

        return WorkshopItem(
            publishedFileId = details.publishedfileid,
            appId = details.consumerAppid,
            title = details.title.ifBlank { "Workshop #${details.publishedfileid}" },
            shortDescription = details.shortDescription,
            description = details.fileDescription,
            previewUrl = details.previewUrl.takeIf(String::isNotBlank),
            authorName = "",
            detailUrl = "https://steamcommunity.com/sharedfiles/filedetails/?id=${details.publishedfileid}",
            fileUrl = details.fileUrl.takeIf(String::isNotBlank),
            fileName = details.filename.takeIf(String::isNotBlank),
            fileSize = details.fileSize,
            timeUpdated = details.timeUpdated.toLong(),
            subscriptions = details.subscriptions,
            creatorSteamId = details.creator,
            contentManifestId = details.hcontentFile,
            childPublishedFileIds = details.childrenList
                .map { it.publishedfileid }
                .filter { it > 0L }
                .distinct(),
            tags = tags.distinct(),
            isSubscribed = details.timeSubscribed > 0,
            isDownloadInfoResolved = true,
        )
    }

    private fun KeyValue.childOrNull(name: String): KeyValue? {
        val child = runCatching { get(name) }.getOrNull() ?: return null
        return child.takeUnless { it == KeyValue.INVALID }
    }

    private fun KeyValue.stringOrNull(name: String): String? {
        return childOrNull(name)?.asString()?.takeIf(String::isNotBlank)
    }

    private data class AppMetadata(
        val name: String,
        val iconHash: String,
        val type: String,
    ) {
        fun shouldDisplayInLibrary(): Boolean {
            return type.isBlank() || type in setOf("game", "application", "tool", "demo", "mod")
        }
    }

    private fun readableMessage(throwable: Throwable): String {
        return when (throwable) {
            is AuthSessionInterruptedException -> throwable.message ?: "Steam 登录会话已中断，请重试"
            is AuthenticationException -> {
                val rawMessage = throwable.message?.trim().orEmpty()
                when {
                    rawMessage.contains("AcceptDeviceConfirmation returned false", ignoreCase = true) -> {
                        "当前账号需要在 Steam App 中确认登录，此应用暂不支持直接处理该验证方式"
                    }

                    rawMessage.contains("This account requires an authenticator", ignoreCase = true) -> {
                        "当前账号需要额外的 Steam 验证方式，请先在 Steam 官方客户端完成确认后重试"
                    }

                    else -> throwable.result?.name ?: throwable.toUserMessage("Steam 认证失败")
                }
            }

            else -> throwable.toUserMessage(throwable::class.java.simpleName)
        }
    }

    @Serializable
    private data class PublicPublishedFileDetailsEnvelope(
        val response: PublicPublishedFileDetailsResponse = PublicPublishedFileDetailsResponse(),
    )

    @Serializable
    private data class PublicPublishedFileDetailsResponse(
        @SerialName("publishedfiledetails")
        val publishedFileDetails: List<PublicPublishedFileDetails> = emptyList(),
    )

    @Serializable
    private data class WorkshopSearchGameDto(
        @SerialName("appid") val appId: Int,
        val name: String,
        val icon: String? = null,
        val logo: String? = null,
    )

    @Serializable
    private data class WorkshopExploreAjaxResponse(
        val success: Boolean = false,
        @SerialName("pagesize") val pageSize: Int? = null,
        @SerialName("total_count") val totalCount: Int? = null,
        val start: Int? = null,
        @SerialName("results_html") val resultsHtml: String = "",
    )

    @Serializable
    private data class PublicPublishedFileDetails(
        val result: Int = 0,
        @SerialName("publishedfileid")
        val publishedFileId: Long = 0L,
        val title: String = "",
        val filename: String? = null,
        @SerialName("file_url")
        val fileUrl: String? = null,
        @SerialName("file_size")
        val fileSize: Long? = null,
        @SerialName("file_type")
        val fileType: Int? = null,
        @SerialName("hcontent_file")
        val hcontentFile: Long? = null,
        @SerialName("consumer_app_id")
        val consumerAppId: Long? = null,
        @SerialName("preview_url")
        val previewUrl: String? = null,
        @SerialName("short_description")
        val shortDescription: String? = null,
        @SerialName("file_description")
        val fileDescription: String? = null,
        @SerialName("time_updated")
        val timeUpdated: Long? = null,
        val subscriptions: Long? = null,
        val creator: Long? = null,
        val tags: List<PublicWorkshopTag>? = null,
        val children: List<PublicWorkshopChild>? = null,
    )

    @Serializable
    private data class PublicWorkshopTag(
        val tag: String? = null,
        @SerialName("display_name")
        val displayName: String? = null,
    )

    @Serializable
    private data class PublicWorkshopChild(
        @SerialName("publishedfileid")
        val publishedFileId: Long? = null,
    )

}
