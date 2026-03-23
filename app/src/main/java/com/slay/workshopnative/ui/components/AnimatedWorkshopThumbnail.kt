package com.slay.workshopnative.ui.components

import android.content.Context
import android.net.Uri
import android.os.Build
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
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.disk.DiskCache
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.serviceLoaderEnabled
import com.slay.workshopnative.ui.theme.workshopAdaptiveBorderColor
import okio.Path.Companion.toOkioPath

@Composable
fun AnimatedWorkshopThumbnail(
    imageUrl: String?,
    fallbackText: String,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    val candidateUrls = remember(imageUrl) {
        buildList {
            val normalizedUrl = normalizeAnimatedWorkshopArtworkUrl(imageUrl)
            if (!normalizedUrl.isNullOrBlank()) {
                add(normalizedUrl)
            }
        }
    }
    var candidateIndex by remember(candidateUrls) { mutableStateOf(0) }
    val resolvedUrl = candidateUrls.getOrNull(candidateIndex)
    val imageLoader = rememberAnimatedWorkshopImageLoader(context)

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
                    imageLoader = imageLoader,
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

@Composable
private fun rememberAnimatedWorkshopImageLoader(context: Context): ImageLoader {
    val appContext = context.applicationContext
    return remember(appContext) {
        AnimatedWorkshopImageLoaderHolder.get(appContext)
    }
}

private object AnimatedWorkshopImageLoaderHolder {
    @Volatile
    private var imageLoader: ImageLoader? = null

    fun get(context: Context): ImageLoader {
        return imageLoader ?: synchronized(this) {
            imageLoader ?: buildImageLoader(context).also { imageLoader = it }
        }
    }

    private fun buildImageLoader(context: Context): ImageLoader {
        return ImageLoader.Builder(context)
            .serviceLoaderEnabled(false)
            .components {
                add(OkHttpNetworkFetcherFactory())
                if (Build.VERSION.SDK_INT >= 28) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.08)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("coil-animated-workshop-cache").toOkioPath())
                    .maxSizeBytes(128L * 1024L * 1024L)
                    .build()
            }
            .build()
    }
}

private fun normalizeAnimatedWorkshopArtworkUrl(url: String?): String? {
    if (url.isNullOrBlank()) return null
    return runCatching {
        val parsed = Uri.parse(url)
        if (parsed.scheme.equals("http", ignoreCase = true)) {
            parsed.buildUpon().scheme("https").build().toString()
        } else {
            url.trim()
        }
    }.getOrDefault(url.trim())
}
