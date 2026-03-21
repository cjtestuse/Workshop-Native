package com.slay.workshopnative.ui.components

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.slay.workshopnative.ui.theme.workshopAdaptiveBorderColor

@Composable
fun ArtworkThumbnail(
    imageUrl: String?,
    alternateImageUrl: String? = null,
    fallbackText: String,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    val candidateUrls = remember(imageUrl, alternateImageUrl) {
        buildList {
            fun addCandidate(url: String?) {
                val normalizedUrl = normalizeArtworkUrl(url)
                if (!normalizedUrl.isNullOrBlank()) add(normalizedUrl)
                if (!normalizedUrl.isNullOrBlank() && normalizedUrl.contains("/capsule_616x353.jpg")) {
                    add(normalizedUrl.replace("/capsule_616x353.jpg", "/header.jpg"))
                }
            }
            addCandidate(imageUrl)
            addCandidate(alternateImageUrl)
        }.distinct()
    }
    var candidateIndex by remember(candidateUrls) { mutableStateOf(0) }
    val resolvedUrl = candidateUrls.getOrNull(candidateIndex)

    Surface(
        modifier = modifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, workshopAdaptiveBorderColor(light = Color.White.copy(alpha = 0.35f))),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.tertiaryContainer,
                        ),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = fallbackText.trim().take(1).ifBlank { "W" },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            if (!resolvedUrl.isNullOrBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(resolvedUrl)
                        .build(),
                    contentDescription = fallbackText,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(shape),
                    contentScale = contentScale,
                    onError = {
                        if (candidateIndex < candidateUrls.lastIndex) {
                            candidateIndex += 1
                        }
                    },
                )
            }
        }
    }
}

fun steamCapsuleUrl(appId: Int): String? {
    if (appId <= 0) return null
    return "https://shared.cloudflare.steamstatic.com/store_item_assets/steam/apps/$appId/capsule_616x353.jpg"
}

private fun normalizeArtworkUrl(url: String?): String? {
    if (url.isNullOrBlank()) return null
    return runCatching {
        val parsed = Uri.parse(url)
        if (parsed.scheme.equals("http", ignoreCase = true)) {
            parsed.buildUpon().scheme("https").build().toString()
        } else {
            url.trim()
        }
    }.getOrDefault(url)
}
