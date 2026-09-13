package dev.schrammel.protego_parentis.update

import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Fetches `update.json` and the release APK it describes.
 *
 * Both come from GitHub's `/releases/latest/download/<asset>` aliases —
 * plain, unauthenticated static file downloads rather than the GitHub REST
 * API, so there is no rate limit to run into regardless of how many phones
 * happen to check at once. GitHub answers that URL with a redirect (still
 * `https://`, just a different host), which [HttpURLConnection] already
 * follows on its own — see `docs/RELEASING.md` for why that did not need a
 * manual redirect handler.
 */
class UpdateClient(private val repoUrl: String = DEFAULT_REPO_URL) {

    fun fetchManifest(): UpdateManifest? {
        val raw = get("$repoUrl/releases/latest/download/update.json") ?: return null
        return UpdateManifest.parse(raw)
    }

    /**
     * Downloads the release APK into [destination], verifying it against
     * [manifest] as the bytes arrive rather than only after the fact.
     *
     * Returns false — and deletes whatever was written — for anything short
     * of an exact size and checksum match. A family's managed phone is about
     * to silently install whatever this returns true for, so a truncated or
     * substituted download is treated exactly as seriously as one that failed
     * outright: not installed, not left around for a later retry to trust.
     */
    fun downloadApk(manifest: UpdateManifest, destination: File): Boolean {
        val ok = runCatching {
            val connection = open("$repoUrl/releases/latest/download/app-child-release.apk")
            try {
                if (connection.responseCode !in 200..299) return@runCatching false

                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                destination.outputStream().use { out ->
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            total += read
                        }
                    }
                }

                val checksum = digest.digest().joinToString("") { "%02x".format(it) }
                total == manifest.apkSize && checksum == manifest.apkSha256
            } finally {
                connection.disconnect()
            }
        }.onFailure { Log.d(TAG, "Update download failed", it) }.getOrDefault(false)

        if (!ok) destination.delete()
        return ok
    }

    private fun get(url: String): String? = runCatching {
        val connection = open(url)
        try {
            if (connection.responseCode !in 200..299) null
            else connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }.onFailure { Log.d(TAG, "Update check failed", it) }.getOrNull()

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = TIMEOUT_MILLIS
            readTimeout = TIMEOUT_MILLIS
        }

    private companion object {
        const val TAG = "DementorUpdate"
        const val DEFAULT_REPO_URL = "https://github.com/i3iorn/protego_parentis"
        const val BUFFER_BYTES = 8192
        val TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(30).toInt()
    }
}
