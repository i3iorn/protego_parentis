package dev.schrammel.protego_parentis.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

/**
 * #85's own bug, found only after it shipped: a full app inventory — every
 * launchable app's package name and label, JSON-encoded, signed, then
 * AES-GCM-sealed and base64'd — cleared this file's old 16,384-character
 * line bound on any phone with a realistic number of installed apps. The
 * line was then silently dropped by [NtfyTransport.readEnvelopes] before it
 * was even parsed: no error, no [dev.schrammel.protego_parentis.diagnostics.Diagnostics]
 * entry, nothing to say why a parent's "request list" tap never produced a
 * list. This pins that a line the size a real large inventory produces
 * survives the filter now, and that the filter still has a floor somewhere.
 */
class NtfyTransportTest {

    private val transport = NtfyTransport(serverUrl = "https://example.invalid", topic = "test-topic")

    private fun ntfyLine(message: String): String =
        JSONObject().apply {
            put(NtfyTransport.FIELD_EVENT, NtfyTransport.EVENT_MESSAGE)
            put(NtfyTransport.FIELD_MESSAGE, message)
        }.toString()

    private fun readLines(vararg lines: String): List<String> =
        transport.readEnvelopes(BufferedReader(StringReader(lines.joinToString("\n"))))

    @Test
    fun `a line the size of a large real app inventory is not dropped`() {
        // Roughly the size a signed, sealed, base64'd SyncMessage carrying a
        // few hundred AppInventoryEntry objects comes out to — comfortably
        // past the old 16,384-character bound that used to drop it.
        val bigEnvelope = "x".repeat(40_000)

        val result = readLines(ntfyLine(bigEnvelope))

        assertEquals(listOf(bigEnvelope), result)
    }

    @Test
    fun `a line past the new bound is still dropped rather than read into memory`() {
        val tooBig = "x".repeat(NtfyTransport.MAX_LINE_CHARS + 1)

        val result = readLines(ntfyLine(tooBig))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `an ordinary short message still round-trips`() {
        val result = readLines(ntfyLine("short-envelope"))

        assertEquals(listOf("short-envelope"), result)
    }
}
