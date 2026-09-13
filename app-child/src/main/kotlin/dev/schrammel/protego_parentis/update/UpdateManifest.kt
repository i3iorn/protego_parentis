package dev.schrammel.protego_parentis.update

import org.json.JSONObject

/**
 * What one GitHub release says about itself.
 *
 * Published as `update.json`, alongside the signed release APK it describes,
 * by the release workflow (see `docs/RELEASING.md`) — this app runs no server
 * of its own, so a small static file next to the artifact it names is the
 * whole update channel. [apkSha256] is computed once, at release time, by the
 * same workflow that signs the APK; [UpdateClient] re-computes it while
 * downloading and refuses to install anything that does not match.
 */
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val apkSha256: String,
    val apkSize: Long,
) {
    /** Whether this release is actually newer than [currentVersionCode]. */
    fun isNewerThan(currentVersionCode: Int): Boolean = versionCode > currentVersionCode

    companion object {
        private const val KEY_VERSION_CODE = "versionCode"
        private const val KEY_VERSION_NAME = "versionName"
        private const val KEY_APK_SHA256 = "apkSha256"
        private const val KEY_APK_SIZE = "apkSize"

        private val SHA256_HEX = Regex("[0-9a-f]{64}")

        /**
         * Parses [raw], or null for anything malformed.
         *
         * A file fetched over plain HTTPS is still input from outside this
         * app's control — a corrupted download, a redirect to something that
         * is not JSON at all, a release published by a workflow that changes
         * shape later — and none of that may throw out of a background
         * update check.
         */
        fun parse(raw: String): UpdateManifest? {
            val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null

            val versionCode = json.optInt(KEY_VERSION_CODE, -1).takeIf { it > 0 } ?: return null
            val versionName = json.optString(KEY_VERSION_NAME).takeIf(String::isNotBlank) ?: return null
            val apkSize = json.optLong(KEY_APK_SIZE, -1).takeIf { it > 0 } ?: return null
            val apkSha256 = json.optString(KEY_APK_SHA256).trim().lowercase()
            if (!SHA256_HEX.matches(apkSha256)) return null

            return UpdateManifest(versionCode, versionName, apkSha256, apkSize)
        }
    }
}
