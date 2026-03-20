package com.slay.workshopnative.ui

data class InlineTranslationState(
    val sourceFingerprint: String = "",
    val translatedText: String? = null,
    val providerLabel: String? = null,
    val sourceLanguageLabel: String? = null,
    val isTranslating: Boolean = false,
    val showTranslated: Boolean = false,
    val errorMessage: String? = null,
)
