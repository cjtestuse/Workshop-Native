package com.slay.workshopnative.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightScheme = lightColorScheme(
    primary = Copper500,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = Copper100,
    onPrimaryContainer = Slate950,
    secondary = Teal500,
    onSecondary = androidx.compose.ui.graphics.Color.White,
    secondaryContainer = Teal100,
    onSecondaryContainer = Slate950,
    tertiary = Sand300,
    onTertiary = Slate950,
    tertiaryContainer = androidx.compose.ui.graphics.Color(0xFFF6EBDD),
    onTertiaryContainer = Slate950,
    background = Sand50,
    onBackground = Slate950,
    surface = androidx.compose.ui.graphics.Color(0xFFFFFCF7),
    onSurface = Slate950,
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFFF3E9DD),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF6E6255),
    surfaceContainerHigh = Sand100,
    surfaceContainerLowest = androidx.compose.ui.graphics.Color(0xFFF9F1E6),
    outline = androidx.compose.ui.graphics.Color(0xFFCDBBA6),
    outlineVariant = androidx.compose.ui.graphics.Color(0xFFE6D6C3),
)

private val DarkScheme = darkColorScheme(
    primary = Copper500,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = androidx.compose.ui.graphics.Color(0xFF5A2B1A),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFFFDCCB),
    secondary = Teal500,
    onSecondary = Slate950,
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFF163D39),
    onSecondaryContainer = Teal100,
    tertiary = Sand300,
    onTertiary = Slate950,
    tertiaryContainer = androidx.compose.ui.graphics.Color(0xFF413528),
    onTertiaryContainer = androidx.compose.ui.graphics.Color(0xFFF7E9D7),
    background = Slate950,
    onBackground = Slate100,
    surface = Slate900,
    onSurface = Slate100,
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF283142),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFB6C0D1),
    surfaceContainerHigh = Slate800,
    surfaceContainerLowest = Slate950,
    outline = androidx.compose.ui.graphics.Color(0xFF4A5568),
    outlineVariant = androidx.compose.ui.graphics.Color(0xFF303A4D),
)

@Composable
fun WorkshopNativeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = WorkshopTypography,
        content = content,
    )
}
