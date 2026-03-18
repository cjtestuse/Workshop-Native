package com.slay.workshopnative.core.util

import android.content.Context
import android.content.Intent
import android.net.Uri

fun Context.openUrlWithChooser(
    url: String,
    chooserTitle: String = "打开链接",
): Boolean {
    val normalizedUrl = url.trim()
    if (normalizedUrl.isBlank()) return false

    val targetUri = runCatching { Uri.parse(normalizedUrl) }.getOrNull() ?: return false
    val viewIntent = Intent(Intent.ACTION_VIEW, targetUri).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val chooserIntent = Intent.createChooser(viewIntent, chooserTitle).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    return runCatching {
        startActivity(chooserIntent)
        true
    }.getOrDefault(false)
}
