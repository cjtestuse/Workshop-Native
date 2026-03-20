package com.slay.workshopnative.data.preferences

import java.util.Locale

const val DEFAULT_AZURE_TRANSLATOR_ENDPOINT = "https://api.cognitive.microsofttranslator.com"

enum class TranslationProvider {
    Disabled,
    AzureTranslator,
    GoogleCloudTranslate,
    GoogleWebTranslate,
    MicrosoftEdgeTranslate,
}

val DEFAULT_TRANSLATION_PROVIDER = TranslationProvider.MicrosoftEdgeTranslate

fun TranslationProvider.displayLabel(): String {
    return when (this) {
        TranslationProvider.Disabled -> "关闭"
        TranslationProvider.AzureTranslator -> "Azure Translator"
        TranslationProvider.GoogleCloudTranslate -> "Google Cloud Translation"
        TranslationProvider.GoogleWebTranslate -> "Google Web 翻译"
        TranslationProvider.MicrosoftEdgeTranslate -> "Microsoft Edge 翻译"
    }
}

fun TranslationProvider.attributionLabel(): String {
    return when (this) {
        TranslationProvider.Disabled -> "翻译服务"
        TranslationProvider.AzureTranslator -> "Azure Translator"
        TranslationProvider.GoogleCloudTranslate -> "Google 翻译"
        TranslationProvider.GoogleWebTranslate -> "Google 翻译"
        TranslationProvider.MicrosoftEdgeTranslate -> "Microsoft 翻译"
    }
}

fun TranslationProvider.isExperimental(): Boolean {
    return when (this) {
        TranslationProvider.Disabled,
        TranslationProvider.AzureTranslator,
        TranslationProvider.GoogleCloudTranslate -> false
        TranslationProvider.GoogleWebTranslate,
        TranslationProvider.MicrosoftEdgeTranslate -> true
    }
}

fun TranslationProvider.isReady(
    azureEndpoint: String,
    azureApiKey: String,
    googleApiKey: String,
): Boolean {
    return when (this) {
        TranslationProvider.Disabled -> false
        TranslationProvider.AzureTranslator ->
            azureEndpoint.isNotBlank() && azureApiKey.isNotBlank()
        TranslationProvider.GoogleCloudTranslate ->
            googleApiKey.isNotBlank()
        TranslationProvider.GoogleWebTranslate,
        TranslationProvider.MicrosoftEdgeTranslate -> true
    }
}

fun TranslationProvider.settingsStatusLabel(isConfigured: Boolean): String {
    return when {
        this == TranslationProvider.Disabled -> "未启用"
        isExperimental() -> "实验模式"
        isConfigured -> "已配置"
        else -> "未配置"
    }
}

fun translationLanguageLabel(code: String?): String? {
    val normalized = code
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.takeIf(String::isNotBlank)
        ?: return null

    return when (normalized) {
        "zh", "zh-cn", "zh-hans", "zh-sg" -> "中文"
        "zh-hant", "zh-hk", "zh-tw" -> "繁体中文"
        "en" -> "英语"
        "ja" -> "日语"
        "ko" -> "韩语"
        "fr" -> "法语"
        "de" -> "德语"
        "es" -> "西班牙语"
        "pt", "pt-br", "pt-pt" -> "葡萄牙语"
        "it" -> "意大利语"
        "ru" -> "俄语"
        "uk" -> "乌克兰语"
        "pl" -> "波兰语"
        "tr" -> "土耳其语"
        "cs" -> "捷克语"
        "nl" -> "荷兰语"
        "sv" -> "瑞典语"
        "da" -> "丹麦语"
        "fi" -> "芬兰语"
        "no" -> "挪威语"
        "hu" -> "匈牙利语"
        "ro" -> "罗马尼亚语"
        "bg" -> "保加利亚语"
        "el" -> "希腊语"
        "th" -> "泰语"
        "vi" -> "越南语"
        "id" -> "印度尼西亚语"
        "ms" -> "马来语"
        "ar" -> "阿拉伯语"
        "hi" -> "印地语"
        else -> code.uppercase(Locale.ROOT)
    }
}
