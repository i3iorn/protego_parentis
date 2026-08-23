package dev.schrammel.dementor_watch.audit

import dev.schrammel.dementor_watch.model.AuditAction
import dev.schrammel.dementor_watch.model.AuditActor
import dev.schrammel.dementor_watch.model.AuditEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AuditLogFormatTest {

    @Test
    fun `round-trips an entry with every field set`() {
        val entry = entry(
            action = AuditAction.APP_LIMIT_CHANGED,
            subject = "Instagram",
            before = "45",
            after = "30",
        )

        val decoded = AuditLogFormat.decodeAll(AuditLogFormat.encode(listOf(entry)))

        assertEquals(listOf(entry), decoded)
    }

    @Test
    fun `round-trips an entry with no values`() {
        val entry = entry(action = AuditAction.PIN_CHANGED)

        val decoded = AuditLogFormat.decodeAll(AuditLogFormat.encode(listOf(entry)))

        assertEquals(listOf(entry), decoded)
    }

    /** Subjects are family text: app names, category names, whatever was typed. */
    @Test
    fun `survives quotes and newlines in a subject`() {
        val entry = entry(
            action = AuditAction.CATEGORY_SAVED,
            subject = "\"Games\"\n\tand things",
            after = "60",
        )

        val decoded = AuditLogFormat.decodeAll(AuditLogFormat.encode(listOf(entry)))

        assertEquals(listOf(entry), decoded)
    }

    @Test
    fun `appends newest last and keeps order`() {
        var raw: String? = null
        repeat(3) { index ->
            raw = AuditLogFormat.append(raw, entry(id = "e$index", at = Instant.ofEpochSecond(index.toLong())))
        }

        assertEquals(listOf("e0", "e1", "e2"), AuditLogFormat.decodeAll(raw).map { it.id })
    }

    @Test
    fun `drops the oldest entries once the cap is reached`() {
        var raw: String? = null
        repeat(AuditLogFormat.MAX_ENTRIES + 10) { index ->
            raw = AuditLogFormat.append(raw, entry(id = "e$index"))
        }

        val decoded = AuditLogFormat.decodeAll(raw)
        assertEquals(AuditLogFormat.MAX_ENTRIES, decoded.size)
        assertEquals("e10", decoded.first().id)
        assertEquals("e${AuditLogFormat.MAX_ENTRIES + 9}", decoded.last().id)
    }

    /**
     * A phone that shared a family with a newer build, then downgraded, must still
     * open its own history rather than crashing on a row it cannot read.
     */
    @Test
    fun `skips a row written by a newer build`() {
        val raw = """[{"id":"a","at":1000,"actor":"PARENT","action":"SOMETHING_NEW"},""" +
            """{"id":"b","at":2000,"actor":"CHILD","action":"PIN_CHANGED"}]"""

        val decoded = AuditLogFormat.decodeAll(raw)

        assertEquals(listOf("b"), decoded.map { it.id })
    }

    @Test
    fun `drops rows missing a timestamp or an id`() {
        val raw = """[{"id":"a","actor":"PARENT","action":"PIN_CHANGED"},""" +
            """{"at":2000,"actor":"PARENT","action":"PIN_CHANGED"}]"""

        assertTrue(AuditLogFormat.decodeAll(raw).isEmpty())
    }

    @Test
    fun `treats unreadable storage as an empty log`() {
        assertTrue(AuditLogFormat.decodeAll("not json at all").isEmpty())
        assertTrue(AuditLogFormat.decodeAll(null).isEmpty())
        assertTrue(AuditLogFormat.decodeAll("").isEmpty())
    }

    private fun entry(
        id: String = "id",
        at: Instant = Instant.ofEpochSecond(1_700_000_000),
        actor: AuditActor = AuditActor.PARENT,
        action: AuditAction = AuditAction.DAILY_LIMIT_CHANGED,
        subject: String? = null,
        before: String? = null,
        after: String? = null,
    ) = AuditEntry(id, at, actor, action, subject, before, after)
}
