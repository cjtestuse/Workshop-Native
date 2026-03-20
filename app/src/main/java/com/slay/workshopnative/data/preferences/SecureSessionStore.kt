package com.slay.workshopnative.data.preferences

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.slay.workshopnative.core.logging.AppLog as Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Singleton
class SecureSessionStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {
    private val secretCache = HashMap<String, String>()

    fun readActiveRefreshToken(): String = readSecret(ACTIVE_REFRESH_TOKEN_KEY)

    fun writeActiveRefreshToken(value: String) {
        writeSecret(ACTIVE_REFRESH_TOKEN_KEY, value)
    }

    fun clearActiveRefreshToken() {
        removeSecret(ACTIVE_REFRESH_TOKEN_KEY)
    }

    fun readActiveSessionProfile(): PersistedActiveSteamSession {
        val payload = readSecret(ACTIVE_SESSION_PROFILE_KEY)
        if (payload.isBlank()) return PersistedActiveSteamSession()
        return runCatching {
            json.decodeFromString<PersistedActiveSteamSession>(payload)
        }.getOrElse {
            removeSecret(ACTIVE_SESSION_PROFILE_KEY)
            PersistedActiveSteamSession()
        }
    }

    fun writeActiveSessionProfile(profile: PersistedActiveSteamSession) {
        if (profile.accountName.isBlank() && profile.clientId <= 0L && profile.steamId64 <= 0L) {
            removeSecret(ACTIVE_SESSION_PROFILE_KEY)
            return
        }
        writeSecret(ACTIVE_SESSION_PROFILE_KEY, json.encodeToString(profile))
    }

    fun clearActiveSessionProfile() {
        removeSecret(ACTIVE_SESSION_PROFILE_KEY)
    }

    fun readSavedAccountsMetadata(): List<PersistedSavedSteamAccount> {
        val payload = readSecret(SAVED_ACCOUNTS_METADATA_KEY)
        if (payload.isBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<PersistedSavedSteamAccount>>(payload)
        }.getOrElse {
            removeSecret(SAVED_ACCOUNTS_METADATA_KEY)
            emptyList()
        }
    }

    fun writeSavedAccountsMetadata(accounts: List<PersistedSavedSteamAccount>) {
        if (accounts.isEmpty()) {
            removeSecret(SAVED_ACCOUNTS_METADATA_KEY)
            return
        }
        writeSecret(SAVED_ACCOUNTS_METADATA_KEY, json.encodeToString(accounts))
    }

    fun clearSavedAccountsMetadata() {
        removeSecret(SAVED_ACCOUNTS_METADATA_KEY)
    }

    fun readOwnedGamesSnapshotPayload(): String = readSecret(OWNED_GAMES_SNAPSHOT_PAYLOAD_KEY)

    fun writeOwnedGamesSnapshotPayload(value: String) {
        writeSecret(OWNED_GAMES_SNAPSHOT_PAYLOAD_KEY, value)
    }

    fun clearOwnedGamesSnapshotPayload() {
        removeSecret(OWNED_GAMES_SNAPSHOT_PAYLOAD_KEY)
    }

    fun readTranslationAzureApiKey(): String = readSecret(TRANSLATION_AZURE_API_KEY)

    fun writeTranslationAzureApiKey(value: String) {
        writeSecret(TRANSLATION_AZURE_API_KEY, value)
    }

    fun clearTranslationAzureApiKey() {
        removeSecret(TRANSLATION_AZURE_API_KEY)
    }

    fun readTranslationGoogleApiKey(): String = readSecret(TRANSLATION_GOOGLE_API_KEY)

    fun writeTranslationGoogleApiKey(value: String) {
        writeSecret(TRANSLATION_GOOGLE_API_KEY, value)
    }

    fun clearTranslationGoogleApiKey() {
        removeSecret(TRANSLATION_GOOGLE_API_KEY)
    }

    fun readSavedAccountRefreshToken(accountIdentityKey: String): String {
        val currentStorageKey = savedAccountRefreshTokenKey(accountIdentityKey)
        val currentValue = readSecret(currentStorageKey)
        if (currentValue.isNotBlank()) {
            return currentValue
        }
        val legacyStorageKey = legacySavedAccountRefreshTokenKey(accountIdentityKey)
        val legacyValue = readSecret(legacyStorageKey)
        if (legacyValue.isBlank()) {
            return ""
        }
        writeSecret(currentStorageKey, legacyValue)
        removeSecret(legacyStorageKey)
        return legacyValue
    }

    fun writeSavedAccountRefreshToken(
        accountIdentityKey: String,
        value: String,
    ) {
        writeSecret(savedAccountRefreshTokenKey(accountIdentityKey), value)
    }

    fun removeSavedAccountRefreshToken(accountIdentityKey: String) {
        removeSecret(savedAccountRefreshTokenKey(accountIdentityKey))
        removeSecret(legacySavedAccountRefreshTokenKey(accountIdentityKey))
    }

    private val preferences by lazy {
        context.getSharedPreferences(PREFERENCES_FILE_NAME, Context.MODE_PRIVATE)
    }

    private val lock = Any()

    private fun readSecret(storageKey: String): String = synchronized(lock) {
        secretCache[storageKey]?.let { cached ->
            return@synchronized cached
        }
        val payload = preferences.getString(storageKey, null).orEmpty()
        if (payload.isBlank()) {
            secretCache[storageKey] = ""
            return@synchronized ""
        }
        return@synchronized runCatching {
            decrypt(payload)
        }.getOrElse { throwable ->
            Log.w(LOG_TAG, "Failed to decrypt secure value", throwable)
            preferences.edit().remove(storageKey).commit()
            secretCache[storageKey] = ""
            ""
        }.also { decrypted ->
            secretCache[storageKey] = decrypted
        }
    }

    private fun writeSecret(
        storageKey: String,
        value: String,
    ) = synchronized(lock) {
        if (value.isBlank()) {
            preferences.edit().remove(storageKey).commit()
            secretCache[storageKey] = ""
            return@synchronized
        }
        val payload = encrypt(value)
        check(preferences.edit().putString(storageKey, payload).commit()) {
            "Failed to persist secure session value"
        }
        secretCache[storageKey] = value
    }

    private fun removeSecret(storageKey: String) = synchronized(lock) {
        preferences.edit().remove(storageKey).commit()
        secretCache[storageKey] = ""
    }

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        check(iv.size == GCM_IV_SIZE_BYTES) { "Unexpected GCM IV size: ${iv.size}" }
        val cipherText = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
        return PAYLOAD_PREFIX + Base64.encodeToString(iv + cipherText, Base64.NO_WRAP)
    }

    private fun decrypt(payload: String): String {
        check(payload.startsWith(PAYLOAD_PREFIX)) { "Unsupported secure payload format" }
        val decoded = Base64.decode(payload.removePrefix(PAYLOAD_PREFIX), Base64.NO_WRAP)
        check(decoded.size > GCM_IV_SIZE_BYTES) { "Invalid encrypted payload" }
        val iv = decoded.copyOfRange(0, GCM_IV_SIZE_BYTES)
        val cipherText = decoded.copyOfRange(GCM_IV_SIZE_BYTES, decoded.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(GCM_TAG_SIZE_BITS, iv),
        )
        val plainText = cipher.doFinal(cipherText)
        return plainText.toString(StandardCharsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return keyGenerator.generateKey()
    }

    private fun savedAccountRefreshTokenKey(accountIdentityKey: String): String {
        return SAVED_ACCOUNT_REFRESH_TOKEN_KEY_PREFIX + hashedKey(accountIdentityKey)
    }

    private fun legacySavedAccountRefreshTokenKey(accountIdentityKey: String): String {
        return SAVED_ACCOUNT_REFRESH_TOKEN_KEY_PREFIX + accountIdentityKey
    }

    private fun hashedKey(rawValue: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(rawValue.toByteArray(StandardCharsets.UTF_8))
        return buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                append("%02x".format(byte))
            }
        }
    }

    private companion object {
        const val LOG_TAG = "SecureSessionStore"
        const val PREFERENCES_FILE_NAME = "workshop_native.secure"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "workshop_native.session_key"
        const val PAYLOAD_PREFIX = "v1:"
        const val ACTIVE_REFRESH_TOKEN_KEY = "active_refresh_token"
        const val ACTIVE_SESSION_PROFILE_KEY = "active_session_profile"
        const val SAVED_ACCOUNTS_METADATA_KEY = "saved_accounts_metadata"
        const val OWNED_GAMES_SNAPSHOT_PAYLOAD_KEY = "owned_games_snapshot_payload"
        const val TRANSLATION_AZURE_API_KEY = "translation_azure_api_key"
        const val TRANSLATION_GOOGLE_API_KEY = "translation_google_api_key"
        const val SAVED_ACCOUNT_REFRESH_TOKEN_KEY_PREFIX = "saved_account_refresh_token."
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_SIZE_BYTES = 12
        const val GCM_TAG_SIZE_BITS = 128
    }
}
