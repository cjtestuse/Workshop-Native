package com.slay.workshopnative.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.slay.workshopnative.ui.theme.workshopAdaptiveBorderColor
import com.slay.workshopnative.ui.theme.workshopAdaptiveSurfaceColor

@Composable
fun TranslatableDescriptionCard(
    title: String,
    originalText: String,
    translatedText: String?,
    providerLabel: String?,
    sourceLanguageLabel: String?,
    isTranslating: Boolean,
    showTranslated: Boolean,
    errorMessage: String?,
    collapsedMaxLines: Int,
    modifier: Modifier = Modifier,
    onTranslate: () -> Unit,
    onShowOriginal: () -> Unit,
    onShowTranslated: (() -> Unit)? = null,
) {
    val displayText = if (showTranslated && !translatedText.isNullOrBlank()) {
        translatedText
    } else {
        originalText
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = workshopAdaptiveSurfaceColor(
            light = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f),
        ),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, workshopAdaptiveBorderColor()),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!translatedText.isNullOrBlank()) {
                        if (showTranslated) {
                            TextButton(onClick = onShowOriginal, enabled = !isTranslating) {
                                Text("查看原文")
                            }
                        } else if (onShowTranslated != null) {
                            TextButton(onClick = onShowTranslated, enabled = !isTranslating) {
                                Text("查看译文")
                            }
                        }
                    }
                    TextButton(onClick = onTranslate, enabled = !isTranslating) {
                        if (isTranslating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text(if (translatedText.isNullOrBlank()) "翻译成中文" else "重新翻译")
                        }
                    }
                }
            }

            if (isTranslating) {
                Text(
                    text = "正在翻译，当前仍显示原文。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!errorMessage.isNullOrBlank()) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (showTranslated && (!providerLabel.isNullOrBlank() || !sourceLanguageLabel.isNullOrBlank())) {
                val metadata = buildList {
                    if (!sourceLanguageLabel.isNullOrBlank()) {
                        add("识别原文：$sourceLanguageLabel")
                    }
                    if (!providerLabel.isNullOrBlank()) {
                        add("译文由 $providerLabel 提供")
                    }
                }.joinToString(" · ")
                Text(
                    text = metadata,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (showTranslated && providerLabel?.contains("Google", ignoreCase = true) == true) {
                Text(
                    text = "使用 Google 翻译，仅供阅读参考。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            ExpandableBodyText(
                text = displayText,
                collapsedMaxLines = collapsedMaxLines,
            )
        }
    }
}
