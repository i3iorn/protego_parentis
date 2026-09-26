package dev.schrammel.protego_parentis.provisioning

import java.util.Base64

/**
 * The contents of the setup QR code a parent shows to a factory-reset phone.
 *
 * Android's setup wizard reads this after six taps on the welcome screen, downloads
 * the APK from [apkDownloadUrl], verifies it against [signatureChecksum], and
 * installs it as device owner.
 *
 * A wrong checksum fails provisioning *silently* — the wizard simply moves on —
 * so the value is validated here and should be pinned in CI against the release
 * signing certificate.
 *
 * It carries no family credential (#278). Joining the family happens after
 * setup, in JoinFamilyActivity; a join code riding along in the admin extras
 * is for QR provisioning (#274) to add once it is built.
 */
data class ProvisioningPayload(
    /** `package/fully.qualified.ReceiverClass`, the app's `DeviceAdminReceiver`. */
    val adminComponent: String,
    /** HTTPS location of the DPC APK. Not served by Play — we host it. */
    val apkDownloadUrl: String,
    /** URL-safe base64 of the signing certificate, unpadded. See [SignatureChecksum]. */
    val signatureChecksum: String,
    val wifiSsid: String? = null,
    val wifiPassword: String? = null,
    val wifiSecurityType: String? = null,
    /**
     * Consumer phones should keep their system apps. Enterprise deployments default
     * this to false, which is wrong for us — it would strip the camera and dialer.
     */
    val leaveAllSystemAppsEnabled: Boolean = true,
    val skipEncryption: Boolean = false,
) {
    init {
        require(adminComponent.count { it == '/' } == 1) {
            "adminComponent must be 'package/ReceiverClass', was '$adminComponent'"
        }
        require(apkDownloadUrl.startsWith("https://")) {
            "the APK download location must be HTTPS"
        }
        require(signatureChecksum.isNotBlank()) { "signature checksum is required" }
        require(SignatureChecksum.isWellFormed(signatureChecksum)) {
            "signature checksum must be unpadded URL-safe base64, was '$signatureChecksum'"
        }
        require(wifiPassword == null || wifiSsid != null) {
            "a wifi password without an SSID cannot be used"
        }
    }

    /**
     * The exact extras the Android setup wizard expects.
     *
     * Keys are the string values of the `DevicePolicyManager.EXTRA_PROVISIONING_*`
     * constants. They are spelled out rather than referenced so this module stays
     * off the Android classpath and the parent app can generate QR codes without
     * a device SDK.
     */
    fun toExtras(): Map<String, Any> = buildMap {
        put(EXTRA_ADMIN_COMPONENT, adminComponent)
        put(EXTRA_DOWNLOAD_LOCATION, apkDownloadUrl)
        put(EXTRA_SIGNATURE_CHECKSUM, signatureChecksum)
        put(EXTRA_LEAVE_SYSTEM_APPS, leaveAllSystemAppsEnabled)
        put(EXTRA_SKIP_ENCRYPTION, skipEncryption)
        wifiSsid?.let { put(EXTRA_WIFI_SSID, it) }
        wifiPassword?.let { put(EXTRA_WIFI_PASSWORD, it) }
        wifiSecurityType?.let { put(EXTRA_WIFI_SECURITY_TYPE, it) }
    }

    /** The string that gets encoded into the QR image. */
    fun toQrJson(): String = Json.obj(toExtras())

    companion object {
        const val EXTRA_ADMIN_COMPONENT =
            "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME"
        const val EXTRA_DOWNLOAD_LOCATION =
            "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION"
        const val EXTRA_SIGNATURE_CHECKSUM =
            "android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM"
        const val EXTRA_LEAVE_SYSTEM_APPS =
            "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED"
        const val EXTRA_SKIP_ENCRYPTION =
            "android.app.extra.PROVISIONING_SKIP_ENCRYPTION"
        const val EXTRA_WIFI_SSID = "android.app.extra.PROVISIONING_WIFI_SSID"
        const val EXTRA_WIFI_PASSWORD = "android.app.extra.PROVISIONING_WIFI_PASSWORD"
        const val EXTRA_WIFI_SECURITY_TYPE = "android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE"
    }
}

/**
 * Encoding for the DPC's signing certificate.
 *
 * Android expects the SHA-256 of the signing certificate as URL-safe base64 with
 * the padding stripped. Getting this wrong is the single most common cause of a
 * QR that scans cleanly and then does nothing.
 *
 * Internal: [ProvisioningPayload] validates a checksum's shape against
 * [isWellFormed] on construction; nothing outside this module currently
 * generates one to validate.
 */
internal object SignatureChecksum {

    private val WELL_FORMED = Regex("^[A-Za-z0-9_-]+$")

    /** Encodes a raw SHA-256 digest into the form the setup wizard expects. */
    fun encode(digest: ByteArray): String {
        require(digest.isNotEmpty()) { "digest must not be empty" }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    /** True when [value] has no padding and uses the URL-safe alphabet. */
    fun isWellFormed(value: String): Boolean =
        value.isNotBlank() && WELL_FORMED.matches(value)
}
