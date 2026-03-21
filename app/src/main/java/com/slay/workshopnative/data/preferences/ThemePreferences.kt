package com.slay.workshopnative.data.preferences

enum class AppThemeMode {
    System,
    Light,
    Dark,
}

val DEFAULT_APP_THEME_MODE = AppThemeMode.System

fun AppThemeMode.displayLabel(): String {
    return when (this) {
        AppThemeMode.System -> "跟随系统"
        AppThemeMode.Light -> "浅色"
        AppThemeMode.Dark -> "深色"
    }
}

fun AppThemeMode.descriptionLabel(): String {
    return when (this) {
        AppThemeMode.System -> "应用外观跟随系统深浅色切换。"
        AppThemeMode.Light -> "始终使用浅色主题。"
        AppThemeMode.Dark -> "始终使用深色主题。"
    }
}
