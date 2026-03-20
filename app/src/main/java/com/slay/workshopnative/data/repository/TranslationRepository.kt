package com.slay.workshopnative.data.repository

import com.slay.workshopnative.data.preferences.TranslationProvider

data class TranslationResult(
    val translatedText: String,
    val detectedSourceLanguage: String?,
    val detectedSourceLanguageLabel: String?,
    val provider: TranslationProvider,
)

interface TranslationRepository {
    suspend fun translateToChinese(
        sourceText: String,
        forceRefresh: Boolean = false,
    ): Result<TranslationResult>
}
