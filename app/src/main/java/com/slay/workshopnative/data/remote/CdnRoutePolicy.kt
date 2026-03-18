package com.slay.workshopnative.data.remote

import android.security.NetworkSecurityPolicy

internal object CdnRoutePolicy {
    fun isServerAllowed(
        protocolName: String,
        hostName: String,
    ): Boolean {
        if (!protocolName.equals("HTTP", ignoreCase = true)) return true
        return NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(hostName)
    }
}
