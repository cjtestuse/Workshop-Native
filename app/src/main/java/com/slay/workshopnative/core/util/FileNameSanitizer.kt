package com.slay.workshopnative.core.util

private val reservedChars = Regex("""[\\/:*?"<>|]""")
private val whitespace = Regex("\\s+")

fun sanitizeFileName(raw: String, fallback: String = "workshop-item"): String {
    val cleaned = raw
        .replace(reservedChars, " ")
        .replace(whitespace, " ")
        .trim()
        .take(120)

    return cleaned.ifBlank { fallback }
}
