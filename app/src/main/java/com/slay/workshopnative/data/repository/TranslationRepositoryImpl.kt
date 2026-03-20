package com.slay.workshopnative.data.repository

import androidx.core.text.HtmlCompat
import com.slay.workshopnative.core.util.textFingerprint
import com.slay.workshopnative.data.preferences.DEFAULT_AZURE_TRANSLATOR_ENDPOINT
import com.slay.workshopnative.data.preferences.TranslationProvider
import com.slay.workshopnative.data.preferences.UserPreferencesStore
import com.slay.workshopnative.data.preferences.isReady
import com.slay.workshopnative.data.preferences.translationLanguageLabel
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Singleton
class TranslationRepositoryImpl @Inject constructor(
    private val preferencesStore: UserPreferencesStore,
    private val okHttpClient: OkHttpClient,
    private val json: Json,
) : TranslationRepository {
    private companion object {
        const val GOOGLE_TRANSLATE_URL = "https://translation.googleapis.com/language/translate/v2"
        const val GOOGLE_WEB_TRANSLATE_URL = "https://translate.googleapis.com/translate_a/single"
        const val GOOGLE_WEB_REFERER = "https://translate.google.com/"
        const val AZURE_TRANSLATE_API_VERSION = "3.0"
        const val MICROSOFT_EDGE_AUTH_URL = "https://edge.microsoft.com/translate/auth"
        const val MICROSOFT_EDGE_ACCESS_TOKEN_TTL_MS = 8 * 60 * 1000L
        const val TARGET_LANGUAGE = "zh-Hans"
        const val TEXT_FORMAT = "text"
        const val MAX_CHUNK_LENGTH = 3_000
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    @Serializable
    private data class AzureTranslateBody(
        val text: String,
    )

    @Serializable
    private data class AzureDetectedLanguage(
        val language: String? = null,
    )

    @Serializable
    private data class AzureTranslationEntry(
        val text: String? = null,
    )

    @Serializable
    private data class AzureTranslationResponseItem(
        @SerialName("detectedLanguage")
        val detectedLanguage: AzureDetectedLanguage? = null,
        val translations: List<AzureTranslationEntry> = emptyList(),
    )

    @Serializable
    private data class AzureErrorEnvelope(
        val error: AzureErrorPayload? = null,
    )

    @Serializable
    private data class AzureErrorPayload(
        val code: String? = null,
        val message: String? = null,
    )

    @Serializable
    private data class GoogleTranslationResponse(
        val data: GoogleTranslationData? = null,
    )

    @Serializable
    private data class GoogleTranslationData(
        val translations: List<GoogleTranslationEntry> = emptyList(),
    )

    @Serializable
    private data class GoogleTranslationEntry(
        @SerialName("translatedText")
        val translatedText: String? = null,
        @SerialName("detectedSourceLanguage")
        val detectedSourceLanguage: String? = null,
    )

    @Serializable
    private data class GoogleWebTranslationResponse(
        val sentences: List<GoogleWebSentence> = emptyList(),
        @SerialName("src")
        val detectedSourceLanguage: String? = null,
    )

    @Serializable
    private data class GoogleWebSentence(
        @SerialName("trans")
        val translatedText: String? = null,
    )

    @Serializable
    private data class GoogleErrorEnvelope(
        val error: GoogleErrorPayload? = null,
    )

    @Serializable
    private data class GoogleErrorPayload(
        val code: Int? = null,
        val message: String? = null,
        val status: String? = null,
    )

    private data class EffectiveTranslationConfig(
        val provider: TranslationProvider,
        val azureEndpoint: String,
        val azureRegion: String,
        val azureApiKey: String,
        val googleApiKey: String,
    )

    private val translationCache = ConcurrentHashMap<String, TranslationResult>()
    @Volatile
    private var microsoftEdgeAccessToken: String? = null
    @Volatile
    private var microsoftEdgeAccessTokenExpireAtMs: Long = 0L
    private val microsoftEdgeTokenLock = Any()

    override suspend fun translateToChinese(
        sourceText: String,
        forceRefresh: Boolean,
    ): Result<TranslationResult> {
        return runCatching {
            val normalized = normalizeForTranslation(sourceText)
            check(normalized.isNotBlank()) { "没有可翻译的内容" }

            val config = preferencesStore.snapshot().let { prefs ->
                EffectiveTranslationConfig(
                    provider = prefs.translationProvider,
                    azureEndpoint = prefs.translationAzureEndpoint.ifBlank { DEFAULT_AZURE_TRANSLATOR_ENDPOINT },
                    azureRegion = prefs.translationAzureRegion,
                    azureApiKey = prefs.translationAzureApiKey,
                    googleApiKey = prefs.translationGoogleApiKey,
                )
            }
            validateConfiguration(config)

            val cacheKey = buildCacheKey(config, normalized)
            if (!forceRefresh) {
                translationCache[cacheKey]?.let { return@runCatching it }
            }

            val chunks = splitForTranslation(normalized)
            val result = withContext(Dispatchers.IO) {
                when (config.provider) {
                    TranslationProvider.Disabled -> error("请先在设置页配置翻译服务")
                    TranslationProvider.AzureTranslator -> translateWithAzure(config, chunks)
                    TranslationProvider.GoogleCloudTranslate -> translateWithGoogle(config, chunks)
                    TranslationProvider.GoogleWebTranslate -> translateWithGoogleWeb(chunks)
                    TranslationProvider.MicrosoftEdgeTranslate -> translateWithMicrosoftEdge(chunks)
                }
            }
            translationCache[cacheKey] = result
            result
        }
    }

    private fun validateConfiguration(config: EffectiveTranslationConfig) {
        if (config.provider.isReady(
                azureEndpoint = config.azureEndpoint,
                azureApiKey = config.azureApiKey,
                googleApiKey = config.googleApiKey,
            )
        ) {
            return
        }

        when (config.provider) {
            TranslationProvider.Disabled -> error("请先在设置页配置翻译服务")
            TranslationProvider.AzureTranslator -> {
                check(config.azureEndpoint.isNotBlank()) { "Azure Translator endpoint 未填写" }
                check(config.azureApiKey.isNotBlank()) { "Azure Translator API Key 未填写" }
            }

            TranslationProvider.GoogleCloudTranslate -> {
                check(config.googleApiKey.isNotBlank()) { "Google Cloud Translation API Key 未填写" }
            }

            TranslationProvider.GoogleWebTranslate,
            TranslationProvider.MicrosoftEdgeTranslate -> Unit
        }
    }

    private fun translateWithAzure(
        config: EffectiveTranslationConfig,
        chunks: List<String>,
    ): TranslationResult {
        val endpoint = config.azureEndpoint.removeSuffix("/")
        val requestBody = json.encodeToString(chunks.map(::AzureTranslateBody))
            .toRequestBody(JSON_MEDIA_TYPE)
        val url = "$endpoint/translate".let { baseUrl ->
            baseUrl.toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("api-version", AZURE_TRANSLATE_API_VERSION)
                ?.addQueryParameter("to", TARGET_LANGUAGE)
                ?.build()
                ?: error("Azure Translator endpoint 无效")
        }
        val requestBuilder = Request.Builder()
            .url(url)
            .header("Ocp-Apim-Subscription-Key", config.azureApiKey)
            .header("Content-Type", JSON_MEDIA_TYPE.toString())
            .header("X-ClientTraceId", UUID.randomUUID().toString())
            .post(requestBody)
        if (config.azureRegion.isNotBlank()) {
            requestBuilder.header("Ocp-Apim-Subscription-Region", config.azureRegion)
        }
        val payload = okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            check(response.isSuccessful) {
                buildAzureErrorMessage(response.code, payload)
            }
            payload
        }
        val items = json.decodeFromString<List<AzureTranslationResponseItem>>(payload)
        check(items.isNotEmpty()) { "Azure Translator 未返回可用结果" }
        val detectedSourceLanguage = items.firstOrNull()?.detectedLanguage?.language
        val translatedText = items.map { item ->
            decodeHtmlEntities(item.translations.firstOrNull()?.text.orEmpty())
        }.joinToString("\n\n").trim()
        check(translatedText.isNotBlank()) { "Azure Translator 未返回译文" }
        return TranslationResult(
            translatedText = translatedText,
            detectedSourceLanguage = detectedSourceLanguage,
            detectedSourceLanguageLabel = translationLanguageLabel(detectedSourceLanguage),
            provider = TranslationProvider.AzureTranslator,
        )
    }

    private fun translateWithGoogle(
        config: EffectiveTranslationConfig,
        chunks: List<String>,
    ): TranslationResult {
        val formBodyBuilder = FormBody.Builder()
            .add("target", "zh-CN")
            .add("format", TEXT_FORMAT)
        chunks.forEach { chunk ->
            formBodyBuilder.add("q", chunk)
        }
        val url = GOOGLE_TRANSLATE_URL.toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("key", config.googleApiKey)
            ?.build()
            ?: error("Google Cloud Translation endpoint 无效")
        val request = Request.Builder()
            .url(url)
            .post(formBodyBuilder.build())
            .build()
        val payload = okHttpClient.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            check(response.isSuccessful) {
                buildGoogleErrorMessage(response.code, payload)
            }
            payload
        }
        val parsed = json.decodeFromString<GoogleTranslationResponse>(payload)
        val translations = parsed.data?.translations.orEmpty()
        check(translations.isNotEmpty()) { "Google Cloud Translation 未返回可用结果" }
        val detectedSourceLanguage = translations.firstOrNull()?.detectedSourceLanguage
        val translatedText = translations.map { entry ->
            decodeHtmlEntities(entry.translatedText.orEmpty())
        }.joinToString("\n\n").trim()
        check(translatedText.isNotBlank()) { "Google Cloud Translation 未返回译文" }
        return TranslationResult(
            translatedText = translatedText,
            detectedSourceLanguage = detectedSourceLanguage,
            detectedSourceLanguageLabel = translationLanguageLabel(detectedSourceLanguage),
            provider = TranslationProvider.GoogleCloudTranslate,
        )
    }

    private fun translateWithGoogleWeb(
        chunks: List<String>,
    ): TranslationResult {
        val responses = chunks.map { chunk ->
            val url = GOOGLE_WEB_TRANSLATE_URL.toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("client", "gtx")
                ?.addQueryParameter("sl", "auto")
                ?.addQueryParameter("tl", "zh-CN")
                ?.addQueryParameter("dt", "t")
                ?.addQueryParameter("dj", "1")
                ?.addQueryParameter("q", chunk)
                ?.build()
                ?: error("Google Web 翻译地址无效")
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Referer", GOOGLE_WEB_REFERER)
                .build()
            val payload = okHttpClient.newCall(request).execute().use { response ->
                val payload = response.body?.string().orEmpty()
                check(response.isSuccessful) {
                    buildGoogleWebErrorMessage(response.code, payload)
                }
                payload
            }
            json.decodeFromString<GoogleWebTranslationResponse>(payload)
        }
        val detectedSourceLanguage = responses.firstNotNullOfOrNull { it.detectedSourceLanguage }
        val translatedText = responses.joinToString("\n\n") { response ->
            decodeHtmlEntities(
                response.sentences.joinToString(separator = "") { sentence ->
                    sentence.translatedText.orEmpty()
                },
            )
        }.trim()
        check(translatedText.isNotBlank()) { "Google Web 翻译未返回译文" }
        return TranslationResult(
            translatedText = translatedText,
            detectedSourceLanguage = detectedSourceLanguage,
            detectedSourceLanguageLabel = translationLanguageLabel(detectedSourceLanguage),
            provider = TranslationProvider.GoogleWebTranslate,
        )
    }

    private fun translateWithMicrosoftEdge(
        chunks: List<String>,
    ): TranslationResult {
        val requestBody = json.encodeToString(chunks.map(::AzureTranslateBody))
            .toRequestBody(JSON_MEDIA_TYPE)
        val url = DEFAULT_AZURE_TRANSLATOR_ENDPOINT.removeSuffix("/")
            .let { baseUrl ->
                "$baseUrl/translate".toHttpUrlOrNull()
                    ?.newBuilder()
                    ?.addQueryParameter("api-version", AZURE_TRANSLATE_API_VERSION)
                    ?.addQueryParameter("to", TARGET_LANGUAGE)
                    ?.build()
                    ?: error("Microsoft Edge 翻译地址无效")
            }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${getMicrosoftEdgeAccessToken()}")
            .header("Content-Type", JSON_MEDIA_TYPE.toString())
            .header("X-ClientTraceId", UUID.randomUUID().toString())
            .post(requestBody)
            .build()
        val payload = okHttpClient.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            check(response.isSuccessful) {
                buildMicrosoftEdgeTranslateErrorMessage(response.code, payload)
            }
            payload
        }
        val items = json.decodeFromString<List<AzureTranslationResponseItem>>(payload)
        check(items.isNotEmpty()) { "Microsoft Edge 翻译未返回可用结果" }
        val detectedSourceLanguage = items.firstOrNull()?.detectedLanguage?.language
        val translatedText = items.map { item ->
            decodeHtmlEntities(item.translations.firstOrNull()?.text.orEmpty())
        }.joinToString("\n\n").trim()
        check(translatedText.isNotBlank()) { "Microsoft Edge 翻译未返回译文" }
        return TranslationResult(
            translatedText = translatedText,
            detectedSourceLanguage = detectedSourceLanguage,
            detectedSourceLanguageLabel = translationLanguageLabel(detectedSourceLanguage),
            provider = TranslationProvider.MicrosoftEdgeTranslate,
        )
    }

    private fun buildCacheKey(
        config: EffectiveTranslationConfig,
        normalizedText: String,
    ): String {
        val configSeed = buildString {
            append(config.provider.name)
            append('|')
            when (config.provider) {
                TranslationProvider.AzureTranslator -> {
                    append(config.azureEndpoint.trim())
                    append('|')
                    append(config.azureRegion.trim())
                    append('|')
                    append(config.azureApiKey)
                }

                TranslationProvider.GoogleCloudTranslate -> {
                    append(config.googleApiKey)
                }

                TranslationProvider.Disabled,
                TranslationProvider.GoogleWebTranslate,
                TranslationProvider.MicrosoftEdgeTranslate -> Unit
            }
        }
        return "${textFingerprint(configSeed)}|${textFingerprint(normalizedText)}"
    }

    private fun normalizeForTranslation(text: String): String {
        return text
            .replace(Regex("[\\t\\x0B\\f\\r ]+"), " ")
            .replace(Regex(" *\n *"), "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun splitForTranslation(
        text: String,
        maxChunkLength: Int = MAX_CHUNK_LENGTH,
    ): List<String> {
        if (text.length <= maxChunkLength) return listOf(text)

        val chunks = mutableListOf<String>()
        val paragraphs = text
            .split(Regex("\n{2,}"))
            .flatMap { paragraph ->
                if (paragraph.length <= maxChunkLength) {
                    listOf(paragraph)
                } else {
                    paragraph.chunked(maxChunkLength)
                }
            }
            .filter(String::isNotBlank)

        var current = StringBuilder()
        paragraphs.forEach { paragraph ->
            val separator = if (current.isEmpty()) "" else "\n\n"
            if (current.length + separator.length + paragraph.length <= maxChunkLength) {
                current.append(separator).append(paragraph)
            } else {
                if (current.isNotEmpty()) {
                    chunks += current.toString()
                }
                current = StringBuilder(paragraph)
            }
        }
        if (current.isNotEmpty()) {
            chunks += current.toString()
        }
        return chunks.ifEmpty { listOf(text) }
    }

    private fun decodeHtmlEntities(value: String): String {
        return HtmlCompat.fromHtml(value, HtmlCompat.FROM_HTML_MODE_LEGACY)
            .toString()
            .trim()
    }

    private fun getMicrosoftEdgeAccessToken(): String {
        val now = System.currentTimeMillis()
        microsoftEdgeAccessToken?.takeIf { now < microsoftEdgeAccessTokenExpireAtMs }?.let { token ->
            return token
        }

        synchronized(microsoftEdgeTokenLock) {
            val refreshedNow = System.currentTimeMillis()
            microsoftEdgeAccessToken?.takeIf { refreshedNow < microsoftEdgeAccessTokenExpireAtMs }?.let { token ->
                return token
            }

            val request = Request.Builder()
                .url(MICROSOFT_EDGE_AUTH_URL)
                .get()
                .build()
            val token = okHttpClient.newCall(request).execute().use { response ->
                val payload = response.body?.string().orEmpty().trim()
                check(response.isSuccessful) {
                    buildMicrosoftEdgeAuthErrorMessage(response.code, payload)
                }
                check(payload.count { it == '.' } == 2) {
                    "Microsoft Edge 翻译认证失败，未拿到有效令牌"
                }
                payload
            }
            microsoftEdgeAccessToken = token
            microsoftEdgeAccessTokenExpireAtMs = System.currentTimeMillis() + MICROSOFT_EDGE_ACCESS_TOKEN_TTL_MS
            return token
        }
    }

    private fun buildAzureErrorMessage(
        statusCode: Int,
        payload: String,
    ): String {
        val remoteMessage = runCatching {
            json.decodeFromString<AzureErrorEnvelope>(payload).error?.message
        }.getOrNull()?.trim().orEmpty()

        return when {
            statusCode == 401 || statusCode == 403 ->
                "Azure Translator 鉴权失败，请检查 API Key、Region 和资源权限是否匹配"
            statusCode == 429 ->
                "Azure Translator 配额已用尽或请求过于频繁，请稍后再试"
            statusCode == 400 && remoteMessage.contains("invalid", ignoreCase = true) ->
                "Azure Translator 配置无效，请检查 Endpoint 和 Region 是否填写正确"
            remoteMessage.isNotBlank() -> remoteMessage
            else -> "Azure Translator 请求失败：HTTP $statusCode"
        }
    }

    private fun buildGoogleErrorMessage(
        statusCode: Int,
        payload: String,
    ): String {
        val remoteMessage = runCatching {
            json.decodeFromString<GoogleErrorEnvelope>(payload).error?.message
        }.getOrNull()?.trim().orEmpty()

        return when {
            statusCode == 400 && remoteMessage.contains("API key not valid", ignoreCase = true) ->
                "Google Cloud Translation API Key 无效，请检查密钥是否复制完整"
            statusCode == 400 && remoteMessage.contains("billing", ignoreCase = true) ->
                "Google Cloud Translation 尚未启用结算，请先在 Google Cloud 控制台开启 Billing"
            statusCode == 403 ->
                "Google Cloud Translation 无权访问，请检查 API 是否启用、Key 限制和项目结算状态"
            statusCode == 429 ->
                "Google Cloud Translation 配额已用尽或请求过于频繁，请稍后再试"
            remoteMessage.isNotBlank() -> remoteMessage
            else -> "Google Cloud Translation 请求失败：HTTP $statusCode"
        }
    }

    private fun buildGoogleWebErrorMessage(
        statusCode: Int,
        payload: String,
    ): String {
        return when {
            statusCode == 403 ->
                "Google Web 翻译当前不可用，可能被服务端限制或需要更换网络环境"
            statusCode == 429 ->
                "Google Web 翻译请求过于频繁，请稍后再试"
            payload.isNotBlank() && payload.length < 120 ->
                payload
            else -> "Google Web 翻译请求失败：HTTP $statusCode"
        }
    }

    private fun buildMicrosoftEdgeAuthErrorMessage(
        statusCode: Int,
        payload: String,
    ): String {
        return when {
            statusCode == 403 || statusCode == 429 ->
                "Microsoft Edge 翻译认证接口当前不可用，可能被服务端限制"
            payload.isNotBlank() && payload.length < 120 ->
                payload
            else -> "Microsoft Edge 翻译认证失败：HTTP $statusCode"
        }
    }

    private fun buildMicrosoftEdgeTranslateErrorMessage(
        statusCode: Int,
        payload: String,
    ): String {
        val remoteMessage = runCatching {
            json.decodeFromString<AzureErrorEnvelope>(payload).error?.message
        }.getOrNull()?.trim().orEmpty()

        return when {
            statusCode == 401 || statusCode == 403 ->
                "Microsoft Edge 翻译通道认证失败，可能已被微软调整或暂时不可用"
            statusCode == 429 ->
                "Microsoft Edge 翻译请求过于频繁，请稍后再试"
            remoteMessage.isNotBlank() -> remoteMessage
            else -> "Microsoft Edge 翻译请求失败：HTTP $statusCode"
        }
    }
}
