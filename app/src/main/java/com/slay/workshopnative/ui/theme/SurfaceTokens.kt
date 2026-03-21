package com.slay.workshopnative.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

val LocalWorkshopDarkTheme = staticCompositionLocalOf { false }

@Composable
@ReadOnlyComposable
fun workshopAdaptiveSurfaceColor(
    light: Color = Color.White.copy(alpha = 0.84f),
    dark: Color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
): Color {
    return if (LocalWorkshopDarkTheme.current) dark else light
}

@Composable
@ReadOnlyComposable
fun workshopAdaptiveBorderColor(
    light: Color = Color.White.copy(alpha = 0.42f),
    dark: Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f),
): Color {
    return if (LocalWorkshopDarkTheme.current) dark else light
}

@Composable
@ReadOnlyComposable
fun workshopAdaptiveOverlayColor(
    light: Color = Color.White.copy(alpha = 0.18f),
    dark: Color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.34f),
): Color {
    return if (LocalWorkshopDarkTheme.current) dark else light
}

@Composable
@ReadOnlyComposable
fun workshopAdaptiveGradientBrush(
    lightStart: Color = Color.White.copy(alpha = 0.97f),
    lightEnd: Color = Color(0xFFF7EEE4).copy(alpha = 0.92f),
    darkStart: Color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.98f),
    darkEnd: Color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
): Brush {
    return Brush.horizontalGradient(
        listOf(
            if (LocalWorkshopDarkTheme.current) darkStart else lightStart,
            if (LocalWorkshopDarkTheme.current) darkEnd else lightEnd,
        ),
    )
}
