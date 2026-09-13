package dev.schrammel.protego_parentis.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManifestTest {

    private fun json(
        versionCode: String = "42",
        versionName: String = "\"1.2.0\"",
        apkSha256: String = "\"${"a".repeat(64)}\"",
        apkSize: String = "1234567",
    ) = """
        {
          "versionCode": $versionCode,
          "versionName": $versionName,
          "apkSha256": $apkSha256,
          "apkSize": $apkSize
        }
    """.trimIndent()

    @Test
    fun `a well-formed manifest parses`() {
        val manifest = UpdateManifest.parse(json())

        assertEquals(
            UpdateManifest(versionCode = 42, versionName = "1.2.0", apkSha256 = "a".repeat(64), apkSize = 1234567),
            manifest,
        )
    }

    @Test
    fun `the checksum is normalised to lowercase`() {
        val manifest = UpdateManifest.parse(json(apkSha256 = "\"${"A".repeat(64)}\""))

        assertEquals("a".repeat(64), manifest?.apkSha256)
    }

    @Test
    fun `not JSON at all parses to null`() {
        assertNull(UpdateManifest.parse("this is not json"))
        assertNull(UpdateManifest.parse(""))
    }

    @Test
    fun `a missing field parses to null`() {
        assertNull(UpdateManifest.parse("""{"versionName": "1.0", "apkSha256": "${"a".repeat(64)}", "apkSize": 1}"""))
        assertNull(UpdateManifest.parse("""{"versionCode": 1, "apkSha256": "${"a".repeat(64)}", "apkSize": 1}"""))
        assertNull(UpdateManifest.parse("""{"versionCode": 1, "versionName": "1.0", "apkSize": 1}"""))
        assertNull(UpdateManifest.parse("""{"versionCode": 1, "versionName": "1.0", "apkSha256": "${"a".repeat(64)}"}"""))
    }

    @Test
    fun `a checksum that is not 64 hex characters parses to null`() {
        assertNull(UpdateManifest.parse(json(apkSha256 = "\"not-a-checksum\"")))
        assertNull(UpdateManifest.parse(json(apkSha256 = "\"${"a".repeat(63)}\"")))
        assertNull(UpdateManifest.parse(json(apkSha256 = "\"${"g".repeat(64)}\""))) // not hex
    }

    @Test
    fun `a non-positive version code or size parses to null`() {
        assertNull(UpdateManifest.parse(json(versionCode = "0")))
        assertNull(UpdateManifest.parse(json(versionCode = "-1")))
        assertNull(UpdateManifest.parse(json(apkSize = "0")))
        assertNull(UpdateManifest.parse(json(apkSize = "-1")))
    }

    @Test
    fun `a blank version name parses to null`() {
        assertNull(UpdateManifest.parse(json(versionName = "\"\"")))
        assertNull(UpdateManifest.parse(json(versionName = "\"   \"")))
    }

    @Test
    fun `isNewerThan compares version codes, not names`() {
        val manifest = UpdateManifest.parse(json())!!

        assertTrue(manifest.isNewerThan(41))
        assertFalse(manifest.isNewerThan(42))
        assertFalse(manifest.isNewerThan(43))
    }
}
