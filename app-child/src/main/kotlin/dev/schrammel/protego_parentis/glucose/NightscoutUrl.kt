package dev.schrammel.protego_parentis.glucose

import java.net.URI

/**
 * The server address a parent typed, as the base URL the client builds on.
 *
 * HTTPS only: the token travels in the query string, and the app refuses
 * cleartext anyway (network_security_config.xml). A pasted API path, a
 * trailing slash or credentials in the address are cleaned up or refused
 * here rather than producing a URL that fails later for a reason nobody sees.
 */
internal object NightscoutUrl {

    fun normalise(input: String): String? {
        val trimmed = input.trim().trimEnd('/')
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.host.isNullOrBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null) return null
        val path = (uri.path ?: "").trimEnd('/').removeSuffix("/api/v1").trimEnd('/')
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return "https://${uri.host.lowercase()}$port$path"
    }

    /** A read token has no spaces in it; one that does was pasted with something else. */
    fun isPlausibleToken(token: String): Boolean = token.isNotBlank() && token.none(Char::isWhitespace)
}
