package com.slay.workshopnative.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.slay.workshopnative.ui.theme.LocalWorkshopDarkTheme

@Composable
fun WorkshopBackdrop(
    content: @Composable () -> Unit,
) {
    val base = MaterialTheme.colorScheme.background
    val isDarkTheme = LocalWorkshopDarkTheme.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.linearGradient(
                    if (isDarkTheme) {
                        listOf(
                            MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.96f),
                            base,
                            MaterialTheme.colorScheme.surfaceContainerLowest,
                        )
                    } else {
                        listOf(
                            Color(0xFFF7F1EA),
                            base,
                            MaterialTheme.colorScheme.surfaceContainerLowest,
                        )
                    },
                ),
            ),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .graphicsLayer { rotationZ = -8f }
                .clip(RoundedCornerShape(bottomEnd = 72.dp, bottomStart = 36.dp, topEnd = 28.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0x36E96D43),
                            if (isDarkTheme) Color(0x08E96D43) else Color(0x12E96D43),
                        ),
                    ),
                )
                .fillMaxSize(0.34f),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .graphicsLayer { rotationZ = 10f }
                .clip(RoundedCornerShape(topStart = 88.dp, topEnd = 32.dp, bottomStart = 28.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            if (isDarkTheme) Color(0x1800B8A9) else Color(0x2000B8A9),
                            if (isDarkTheme) Color(0x0800B8A9) else Color(0x1200B8A9),
                        ),
                    ),
                )
                .fillMaxSize(0.3f),
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .graphicsLayer { rotationZ = -14f }
                .clip(RoundedCornerShape(40.dp))
                .background(
                    Brush.linearGradient(
                        if (isDarkTheme) {
                            listOf(
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                Color.Transparent,
                            )
                        } else {
                            listOf(
                                Color(0x18FFFFFF),
                                Color(0x00FFFFFF),
                            )
                        },
                    ),
                )
                .fillMaxSize(0.22f),
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .clip(RoundedCornerShape(bottomStart = 40.dp, bottomEnd = 40.dp))
                .background(
                    if (isDarkTheme) {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                    } else {
                        Color.White.copy(alpha = 0.14f)
                    },
                )
                .fillMaxSize(0.06f),
        )
        content()
    }
}
