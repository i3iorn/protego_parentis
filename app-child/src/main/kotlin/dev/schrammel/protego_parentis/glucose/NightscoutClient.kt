package dev.schrammel.protego_parentis.glucose

import android.util.Log
import dev.schrammel.protego_parentis.model.GlucoseReading
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The two reads the app makes from a Nightscout server, with a read-only
 * token passed as `?token=`, the form Nightscout still accepts
 * (`https://token@host` stopped working in 2024).
 *
 * Blocking: call off the main thread. Never logs a URL, since the token is
 * in it.
 */
internal class NightscoutClient(private val baseUrl: String, private val token: String) {

    sealed interface Result {
        data class Read(val reading: GlucoseReading) : Result
        data class Failed(val problem: NightscoutProblem) : Result
    }

    fun latestReading(): Result {
        val body = when (val response = get("/api/v1/entries/sgv.json?count=$ENTRIES")) {
            is Response.Body -> response.text
            is Response.Error -> return Result.Failed(response.problem)
        }
        val reading = NightscoutParser.latestReading(body) ?: return Result.Failed(NightscoutProblem.NO_READINGS)
        return Result.Read(reading)
    }

    fun units(): GlucoseUnit? = (get("/api/v1/status.json") as? Response.Body)?.let { NightscoutParser.units(it.text) }

    private sealed interface Response {
        data class Body(val text: String) : Response
        data class Error(val problem: NightscoutProblem) : Response
    }

    private fun get(pathAndQuery: String): Response = runCatching {
        val separator = if ('?' in pathAndQuery) '&' else '?'
        val url = "$baseUrl$pathAndQuery${separator}token=${URLEncoder.encode(token, "UTF-8")}"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            // A redirect could only lead somewhere the token wasn't meant for.
            instanceFollowRedirects = false
            connectTimeout = TIMEOUT_MILLIS
            readTimeout = TIMEOUT_MILLIS
            setRequestProperty("Accept", "application/json")
        }
        try {
            when (connection.responseCode) {
                in 200..299 -> Response.Body(readCapped(connection))
                401, 403 -> Response.Error(NightscoutProblem.NOT_ALLOWED)
                else -> Response.Error(NightscoutProblem.UNREACHABLE)
            }
        } finally {
            connection.disconnect()
        }
    }.getOrElse {
        Log.d(TAG, "Nightscout request failed: ${it.javaClass.simpleName}")
        Response.Error(NightscoutProblem.UNREACHABLE)
    }

    /** Bounded, so a misbehaving server can't hand the enforcement process a huge body. */
    private fun readCapped(connection: HttpURLConnection): String {
        val bytes = connection.inputStream.use { stream ->
            val buffer = ByteArray(MAX_BYTES + 1)
            var total = 0
            while (total < buffer.size) {
                val read = stream.read(buffer, total, buffer.size - total)
                if (read < 0) break
                total += read
            }
            require(total <= MAX_BYTES) { "response too large" }
            buffer.copyOf(total)
        }
        return String(bytes, Charsets.UTF_8)
    }

    private companion object {
        const val TAG = "DementorGlucose"
        const val ENTRIES = 3
        const val MAX_BYTES = 64 * 1024
        const val TIMEOUT_MILLIS = 10_000
    }
}
