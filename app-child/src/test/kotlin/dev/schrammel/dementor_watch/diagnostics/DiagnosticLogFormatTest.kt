package dev.schrammel.dementor_watch.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class DiagnosticLogFormatTest {

    private val utc: ZoneId = ZoneId.of("UTC")
    private val at: Instant = Instant.parse("2026-08-23T18:04:05Z")

    private fun entry(
        event: DiagnosticEvent = DiagnosticEvent.EVALUATED,
        counts: Map<String, Long> = mapOf("usedMinutes" to 42L),
    ) = DiagnosticEntry(at, event, counts)

    // ---------- the privacy rule ----------

    @Test
    fun `every rendered line is only an event name and numbers`() {
        // This is the guarantee that makes the log safe to read and safe to share.
        // If someone later interpolates a package name, a request reason or a prize
        // into a diagnostic, this fails rather than shipping.
        val entries = DiagnosticEvent.entries.map { event ->
            entry(event, mapOf("count" to 3L, "other" to -1L))
        }

        DiagnosticLogFormat.render(entries, "header", "empty", utc)
            .lines()
            .drop(2)
            .filter { it.isNotBlank() }
            .forEach { line ->
                assertTrue(
                    "line carries something other than an event and numbers: $line",
                    DiagnosticLogFormat.RENDERED_LINE.matches(line),
                )
            }
    }

    @Test
    fun `an entry with no counts still renders cleanly`() {
        val line = DiagnosticLogFormat.renderLine(entry(counts = emptyMap()), utc)

        assertEquals("2026-08-23 18:04:05  EVALUATED", line)
        assertTrue(DiagnosticLogFormat.RENDERED_LINE.matches(line))
    }

    @Test
    fun `counts can only ever be numbers`() {
        // Enforced by the type of DiagnosticEntry.counts; this pins the rendering
        // so a negative or large value cannot break the shape either.
        val line = DiagnosticLogFormat.renderLine(
            entry(counts = mapOf("a" to Long.MAX_VALUE, "b" to -5L)),
            utc,
        )

        assertTrue(DiagnosticLogFormat.RENDERED_LINE.matches(line))
    }

    // ---------- round trip ----------

    @Test
    fun `an entry survives encoding and decoding`() {
        assertEquals(entry(), DiagnosticLogFormat.decode(DiagnosticLogFormat.encode(entry())))
    }

    @Test
    fun `several entries survive together`() {
        val entries = listOf(
            entry(DiagnosticEvent.SERVICE_STARTED, emptyMap()),
            entry(DiagnosticEvent.EVALUATED, mapOf("wouldSuspend" to 3L)),
        )

        assertEquals(entries, DiagnosticLogFormat.decodeAll(DiagnosticLogFormat.encodeAll(entries)))
    }

    // ---------- resilience ----------

    @Test
    fun `a malformed line is skipped, not fatal`() {
        listOf("", "garbage", "123", "notanumber|EVALUATED", "123|NOT_AN_EVENT").forEach {
            assertNull("accepted: $it", DiagnosticLogFormat.decode(it))
        }
    }

    @Test
    fun `one bad line does not lose the others`() {
        val good = DiagnosticLogFormat.encode(entry())
        val raw = "garbage\n$good\nalso garbage"

        assertEquals(1, DiagnosticLogFormat.decodeAll(raw).size)
    }

    @Test
    fun `an unknown event from a newer build is dropped`() {
        assertNull(DiagnosticLogFormat.decode("1000|SOMETHING_FUTURE|"))
    }

    // ---------- bounding ----------

    @Test
    fun `the log stops growing at the limit`() {
        var raw: String? = null
        repeat(DiagnosticLogFormat.MAX_ENTRIES * 2) { index ->
            raw = DiagnosticLogFormat.append(raw, entry(counts = mapOf("n" to index.toLong())))
        }

        val entries = DiagnosticLogFormat.decodeAll(raw)
        assertEquals(DiagnosticLogFormat.MAX_ENTRIES, entries.size)
        // The most recent are the ones kept.
        assertEquals(
            (DiagnosticLogFormat.MAX_ENTRIES * 2 - 1).toLong(),
            entries.last().counts["n"],
        )
    }

    @Test
    fun `an empty log says so rather than rendering nothing`() {
        val text = DiagnosticLogFormat.render(emptyList(), "header", "nothing yet", utc)

        assertTrue(text.contains("header"))
        assertTrue(text.contains("nothing yet"))
    }
}
