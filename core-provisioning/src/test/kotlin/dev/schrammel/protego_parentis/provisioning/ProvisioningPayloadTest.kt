package dev.schrammel.protego_parentis.provisioning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvisioningPayloadTest {

    private fun payload(
        adminComponent: String = "dev.schrammel.protego_parentis/.dpc.DementorDeviceAdminReceiver",
        apkDownloadUrl: String = "https://dl.dementorwatch.app/dpc/1.0.0.apk",
        signatureChecksum: String = "gJD2YwtOiWJHkSMkkIfLRlj-quNqG1fb6v100QmzM9w",
        wifiSsid: String? = null,
        wifiPassword: String? = null,
    ) = ProvisioningPayload(
        adminComponent = adminComponent,
        apkDownloadUrl = apkDownloadUrl,
        signatureChecksum = signatureChecksum,
        wifiSsid = wifiSsid,
        wifiPassword = wifiPassword,
    )

    // ---------- extras ----------

    @Test
    fun `the payload carries the keys the setup wizard reads`() {
        val extras = payload().toExtras()

        assertEquals(
            "dev.schrammel.protego_parentis/.dpc.DementorDeviceAdminReceiver",
            extras[ProvisioningPayload.EXTRA_ADMIN_COMPONENT],
        )
        assertEquals(
            "https://dl.dementorwatch.app/dpc/1.0.0.apk",
            extras[ProvisioningPayload.EXTRA_DOWNLOAD_LOCATION],
        )
        assertEquals(
            "gJD2YwtOiWJHkSMkkIfLRlj-quNqG1fb6v100QmzM9w",
            extras[ProvisioningPayload.EXTRA_SIGNATURE_CHECKSUM],
        )
    }

    @Test
    fun `system apps are left enabled so the phone keeps its camera and dialer`() {
        assertEquals(true, payload().toExtras()[ProvisioningPayload.EXTRA_LEAVE_SYSTEM_APPS])
    }

    @Test
    fun `no family credential travels in the payload`() {
        // #278: nothing on the phone reads one, so a QR carrying it would leave
        // a credential lying around for no purpose.
        assertFalse(payload().toQrJson().contains("PROVISIONING_ADMIN_EXTRAS_BUNDLE"))
    }

    @Test
    fun `wifi details are omitted entirely when not supplied`() {
        val extras = payload().toExtras()

        assertFalse(extras.containsKey(ProvisioningPayload.EXTRA_WIFI_SSID))
        assertFalse(extras.containsKey(ProvisioningPayload.EXTRA_WIFI_PASSWORD))
    }

    @Test
    fun `wifi details are included when supplied`() {
        val extras = payload(wifiSsid = "Home", wifiPassword = "hunter2").toExtras()

        assertEquals("Home", extras[ProvisioningPayload.EXTRA_WIFI_SSID])
        assertEquals("hunter2", extras[ProvisioningPayload.EXTRA_WIFI_PASSWORD])
    }

    // ---------- json ----------

    @Test
    fun `the qr json is a single object`() {
        val json = payload().toQrJson()

        assertTrue(json.startsWith("{"))
        assertTrue(json.endsWith("}"))
        assertTrue(json.contains("\"android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME\""))
        assertTrue(json.contains("\"android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED\":true"))
    }

    @Test
    fun `json escapes characters that would break the payload`() {
        val json = payload(wifiSsid = "a\"b\\c").toQrJson()

        assertTrue(json.contains("""a\"b\\c"""))
    }

    @Test
    fun `the json is byte-stable across runs`() {
        assertEquals(payload().toQrJson(), payload().toQrJson())
    }

    // ---------- validation ----------

    @Test
    fun `an http download location is rejected`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            payload(apkDownloadUrl = "http://dl.dementorwatch.app/dpc.apk")
        }
        assertTrue(error.message!!.contains("HTTPS"))
    }

    @Test
    fun `a padded checksum is rejected`() {
        // Standard base64 padding is the classic cause of a QR that scans and
        // then silently does nothing.
        assertThrows(IllegalArgumentException::class.java) {
            payload(signatureChecksum = "gJD2YwtOiWJHkSMkkIfLRlj+quNqG1fb6v100QmzM9w=")
        }
    }

    @Test
    fun `a malformed admin component is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            payload(adminComponent = "dev.schrammel.protego_parentis.DementorDeviceAdminReceiver")
        }
    }

    @Test
    fun `a wifi password without an ssid is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            payload(wifiSsid = null, wifiPassword = "hunter2")
        }
    }
}

class SignatureChecksumTest {

    @Test
    fun `a digest encodes to unpadded url-safe base64`() {
        val digest = ByteArray(32) { it.toByte() }
        val encoded = SignatureChecksum.encode(digest)

        assertFalse("padding must be stripped", encoded.contains("="))
        assertFalse("must use the url-safe alphabet", encoded.contains("+"))
        assertFalse("must use the url-safe alphabet", encoded.contains("/"))
        assertTrue(SignatureChecksum.isWellFormed(encoded))
    }

    @Test
    fun `encoding is stable`() {
        val digest = ByteArray(32) { (it * 7).toByte() }

        assertEquals(SignatureChecksum.encode(digest), SignatureChecksum.encode(digest))
    }

    @Test
    fun `standard base64 is not well formed`() {
        assertFalse(SignatureChecksum.isWellFormed("ab+cd/ef="))
        assertFalse(SignatureChecksum.isWellFormed(""))
        assertFalse(SignatureChecksum.isWellFormed("   "))
    }

    @Test
    fun `an empty digest is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            SignatureChecksum.encode(ByteArray(0))
        }
    }
}
