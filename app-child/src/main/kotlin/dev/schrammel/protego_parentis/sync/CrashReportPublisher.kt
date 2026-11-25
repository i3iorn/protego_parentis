package dev.schrammel.protego_parentis.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dev.schrammel.protego_parentis.diagnostics.CrashCapture
import dev.schrammel.protego_parentis.diagnostics.CrashReporter
import dev.schrammel.protego_parentis.diagnostics.UncaughtCrash
import dev.schrammel.protego_parentis.sync.core.TransportKind
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/**
 * Sends a crash report to the family's diagnostics topic on the same ntfy
 * relay the sync transport already uses — no second backend, no second
 * account.
 *
 * The report is deliberately *not* a family sync message. Those are
 * end-to-end encrypted with the family key, which would make reading a crash
 * require the full sync protocol; here the goal is the opposite, a plain
 * fetch with the relay credentials and the family code. What stands in for
 * the encryption is the topic name: the diagnostics topic is derived from the
 * family code *and* the passphrase (an HMAC, not a hash of the passphrase),
 * so it reads as a capability — a stranger who knows the relay, or even the
 * family code alone, derives a topic that simply has nothing on it.
 *
 * Fail-soft everywhere: called from inside the crash handler, and again as a
 * pending flush on the next start when the network was unreachable. Nothing
 * here may throw out of [report].
 */
class CrashReportPublisher(
    private val context: Context,
    private val publish: (UncaughtCrash) -> Boolean = { crash -> publishViaRelay(context, crash) },
) : CrashReporter {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override fun report(thread: String, throwable: Throwable) {
        // No network here: this runs on the crashing thread inside the crash
        // handler, where a blocking publish could delay recovery (and trip an
        // ANR on a main-thread crash). CrashRecovery has already recorded the
        // crash into CrashCapture before calling this; the pending marker
        // makes the next start flush it via [flushPending].
        markPending()
    }

    /**
     * The crash recorded by the crash handler, if the write never happened.
     *
     * Runs synchronously on the caller thread (an app start flush), not on a
     * spawned thread: this only runs when a crash is pending, so it never
     * delays an ordinary boot.
     */
    fun flushPending() {
        if (!prefs.getBoolean(KEY_PENDING, false)) return
        val recorded = CrashCapture.lastUncaught(appContext)
        if (recorded == null) {
            clearPending()
            return
        }
        if (publish(recorded)) {
            clearPending()
            CrashCapture.clearUncaught(appContext)
        } else {
            markPending()
        }
    }

    /** True when a crash is waiting to be flushed on the next start (tests). */
    internal fun hasPending(): Boolean = prefs.getBoolean(KEY_PENDING, false)

    private fun markPending() = prefs.edit { putBoolean(KEY_PENDING, true) }

    private fun clearPending() = prefs.edit { putBoolean(KEY_PENDING, false) }

    private companion object {
        const val FILE_NAME = "crash_report"
        const val KEY_PENDING = "pending"
    }
}

private const val TIMEOUT_MILLIS = 3_000


/** Body of an uncaught-crash report sent via [publishViaRelay].
 *
 * @param context the application context (used for packageName / version info)
 * @param settings sync configuration loaded from the family's config store
 * @param crash   the recorded uncaught exception
 * @return the JSON payload written to the relay
 */
internal fun body(
    context: Context,
    settings: SyncSettings,
    crash: UncaughtCrash,
): String =
    JSONObject().apply {
        put("crash", true)
        put("device", settings.deviceName)
        put("app", context.packageName)
        put("versionName", appVersionName(context))
        put("versionCode", appVersionCode(context))
        put("thread", crash.thread)
        put("at", crash.atMillis / 1000)
        put("trace", crash.trace)
    }.toString()

internal fun post(
    serverUrl: String,
    topic: String,
    credentials: NtfyCredentials,
    body: String,
) {
    val url = URI(serverUrl).resolve(topic).toURL()
    val connection = url.openConnection() as HttpURLConnection
    try {
        connection.requestMethod = "POST"
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(body.toByteArray(Charsets.UTF_8).size)
        connection.setRequestProperty("Content-Type", "text/plain")
        val authorization = authorizationHeader(credentials) ?: return
        connection.setRequestProperty("Authorization", authorization)
        connection.outputStream.use { output ->
            output.write(body.toByteArray(Charsets.UTF_8))
        }
        val code = connection.responseCode
        check(code in 200..299) { "relay returned $code" }
    } finally {
        connection.disconnect()
    }
}

internal fun publishViaRelay(context: Context, crash: UncaughtCrash): Boolean {
    val settings = SyncConfigStore(context).load()
    if (!settings.isUsable || settings.transport != TransportKind.NTFY) return false
    return runCatching {
        post(
            serverUrl = settings.serverUrl,
            topic = diagnosticsTopic(settings.topic, settings.passphrase),
            credentials = settings.credentials,
            body = body(context, settings, crash),
        )
    }.isSuccess
}

/**
 * The diagnostics topic for a family: the family's code plus an HMAC of that
 * code keyed by the family passphrase, base32-encoded.
 *
 * Shared with `tools/crash_cli.py`, which derives the same name from the
 * same two inputs — a test pins the wire format, so the two can never drift.
 */
internal fun diagnosticsTopic(familyTopic: String, passphrase: String): String {
    val code = familyTopic.removePrefix(FAMILY_TOPIC_PREFIX)
    val mac = Mac.getInstance(HMAC_ALGORITHM)
    mac.init(SecretKeySpec(passphrase.toByteArray(Charsets.UTF_8), HMAC_ALGORITHM))
    val digest = mac.doFinal(code.toByteArray(Charsets.UTF_8))
    return "$FAMILY_TOPIC_PREFIX$code-diag-${base32(digest).take(12)}"
}

/** RFC 4648 base32, lower case, no padding — the first 16 characters suffice. */
private fun base32(digest: ByteArray): String {
    val alphabet = "abcdefghijklmnopqrstuvwxyz234567"
    var buffer = 0
    var bits = 0
    val output = StringBuilder()
    for (byte in digest) {
        buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
        bits += 8
        while (bits >= 5) {
            output.append(alphabet[(buffer ushr (bits - 5)) and 31])
            bits -= 5
        }
    }
    return output.toString()
}

/** ntfy accepts a bearer token, or a username/password pair, or nothing. */
private fun authorizationHeader(credentials: NtfyCredentials): String? = when (credentials) {
    is NtfyCredentials.Token -> "Bearer ${credentials.token}"
    is NtfyCredentials.Basic ->
        "Basic " + Base64.getEncoder().encodeToString(
            "${credentials.username}:${credentials.password}".toByteArray(Charsets.UTF_8),
        )
    NtfyCredentials.None -> null
}

private const val FAMILY_TOPIC_PREFIX = "dementor-"
private const val HMAC_ALGORITHM = "HmacSHA256"
