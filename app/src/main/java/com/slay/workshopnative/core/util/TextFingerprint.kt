package com.slay.workshopnative.core.util

import java.security.MessageDigest

fun textFingerprint(value: String): String {
    val bytes = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
    return buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            append("%02x".format(byte))
        }
    }
}
