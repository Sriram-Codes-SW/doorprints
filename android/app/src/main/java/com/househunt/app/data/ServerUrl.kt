package com.househunt.app.data

import java.net.URI

/**
 * Validates the server URL typed in Settings (threat model F-02, SEC-004): HTTPS is required, except for the
 * local development hosts that res/xml/network_security_config.xml also allows in cleartext.
 */
object ServerUrl {
    val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2")

    sealed interface Result {
        data class Ok(val url: String) : Result
        data object Empty : Result
        data object Invalid : Result
        data object NotHttps : Result
    }

    fun check(raw: String): Result {
        val text = raw.trim().trimEnd('/')
        if (text.isEmpty()) return Result.Empty
        val uri = runCatching { URI(text) }.getOrNull() ?: return Result.Invalid
        val scheme = uri.scheme?.lowercase() ?: return Result.Invalid
        val host = uri.host?.lowercase() ?: return Result.Invalid
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return Result.Invalid
        return when (scheme) {
            "https" -> Result.Ok(text)
            "http" -> if (host in LOCAL_HOSTS) Result.Ok(text) else Result.NotHttps
            else -> Result.Invalid
        }
    }
}
