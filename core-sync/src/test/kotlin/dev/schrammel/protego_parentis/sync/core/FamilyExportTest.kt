package dev.schrammel.protego_parentis.sync.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

private const val FAMILY_PASSPHRASE = "correct-horse-battery"
private const val EXPORT_PASSPHRASE = "export-only-passphrase"

class FamilyExportTest {

    private fun export(
        relayToken: String = "",
        relayUsername: String = "",
        relayPassword: String = "",
        deviceName: String = "Pixel 7",
    ) = FamilyExport(
        token = FamilyToken(TransportKind.NTFY, "https://ntfy.example.org", "dementor-abc123"),
        passphrase = FAMILY_PASSPHRASE,
        relayToken = relayToken,
        relayUsername = relayUsername,
        relayPassword = relayPassword,
        deviceName = deviceName,
    )

    @Test
    fun `a sealed export opens back with every field intact`() {
        val body = FamilyExports.seal(export(), EXPORT_PASSPHRASE)

        assertEquals(export(), FamilyExports.open(body, EXPORT_PASSPHRASE))
    }

    @Test
    fun `a sealed export with relay credentials opens back intact`() {
        val withToken = export(relayToken = "tk_example123456")
        val withBasic = export(relayUsername = "protego", relayPassword = "relay password")

        assertEquals(withToken, FamilyExports.open(FamilyExports.seal(withToken, EXPORT_PASSPHRASE), EXPORT_PASSPHRASE))
        assertEquals(withBasic, FamilyExports.open(FamilyExports.seal(withBasic, EXPORT_PASSPHRASE), EXPORT_PASSPHRASE))
    }

    @Test
    fun `sealing twice produces different files`() {
        // A fresh salt and nonce every time, so one leaked file cannot speed
        // up an attack against any other.
        val first = FamilyExports.seal(export(), EXPORT_PASSPHRASE)
        val second = FamilyExports.seal(export(), EXPORT_PASSPHRASE)

        assertNotEquals(first, second)
    }

    @Test
    fun `the wrong export passphrase opens nothing`() {
        val body = FamilyExports.seal(export(), EXPORT_PASSPHRASE)

        assertNull(FamilyExports.open(body, "a-different-passphrase"))
    }

    @Test
    fun `an altered file opens nothing`() {
        val body = FamilyExports.seal(export(), EXPORT_PASSPHRASE)
        val altered = body.dropLast(4) + "AAAA"

        assertNull(FamilyExports.open(altered, EXPORT_PASSPHRASE))
    }

    @Test
    fun `a body that is not an export opens nothing`() {
        assertNull(FamilyExports.open("not an export at all", EXPORT_PASSPHRASE))
        assertNull(FamilyExports.open("DW1-XXXXXXXX-XXXX", EXPORT_PASSPHRASE))
    }

    @Test
    fun `values containing separators and escapes survive the round trip`() {
        val awkward = export(deviceName = "Pixel \\ 7 \u001F (parent)")

        assertEquals(
            awkward,
            FamilyExports.open(FamilyExports.seal(awkward, EXPORT_PASSPHRASE), EXPORT_PASSPHRASE),
        )
    }

    @Test
    fun `a token the format cannot express is refused by the token type itself`() {
        assertThrows(IllegalArgumentException::class.java) {
            FamilyToken(TransportKind.NTFY, "https://ntfy.schrammel.dev", "has space")
        }
    }

    @Test
    fun `a too-short export passphrase is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            FamilyExports.seal(export(), "short")
        }
    }

    @Test
    fun `a one-sided basic-auth pair is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            FamilyExports.seal(export(relayUsername = "protego"), EXPORT_PASSPHRASE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FamilyExports.seal(export(relayPassword = "secret"), EXPORT_PASSPHRASE)
        }
    }

    @Test
    fun `a payload with an under-length family passphrase decodes to nothing`() {
        // The decoding side trusts nothing about who wrote the file: a writer
        // that skips the passphrase check cannot produce an export that joins
        // a family that could not exist. The envelope is built here with the
        // same crypto the format uses, but with the passphrase check skipped.
        val weak = listOf(
            TransportKind.NTFY.name,
            "https://ntfy.schrammel.dev",
            "dementor-abc123",
            "short",
            "", "", "", "",
        ).joinToString("\u001F")
        val saltText = "AAAAAAAAAAAAAAAAAAAAAA"
        val sealed = MessageCrypto.seal(
            MessageCrypto.deriveKey(EXPORT_PASSPHRASE, saltText),
            weak,
        )

        assertNull(FamilyExports.open("DEP1:$saltText:$sealed", EXPORT_PASSPHRASE))
    }
}
