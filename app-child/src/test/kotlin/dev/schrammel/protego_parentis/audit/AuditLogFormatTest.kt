package dev.schrammel.protego_parentis.audit

import dev.schrammel.protego_parentis.model.AuditAction
import dev.schrammel.protego_parentis.model.AuditActor
import dev.schrammel.protego_parentis.model.AuditChain
import dev.schrammel.protego_parentis.model.AuditEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
        var log = AuditLogFormat.append(null, AuditChain.GENESIS, entry(id = "e0"))
        (1 until 3).forEach { index ->
            log = AuditLogFormat.append(log.log, log.anchor, entry(id = "e$index"))
        }

        assertEquals(listOf("e0", "e1", "e2"), AuditLogFormat.decodeAll(log.log).map { it.id })
    }

    /** Every appended entry links to the one before it, with no help from callers. */
    @Test
    fun `appending builds a verifiable chain`() {
        var log = AuditLogFormat.append(null, AuditChain.GENESIS, entry(id = "e0"))
        (1 until 6).forEach { index ->
            log = AuditLogFormat.append(log.log, log.anchor, entry(id = "e$index"))
        }

        assertTrue(AuditChain.verify(AuditLogFormat.decodeAll(log.log), log.anchor).isIntact)
    }

    @Test
    fun `drops the oldest entries once the cap is reached`() {
        var log = AuditLogFormat.append(null, AuditChain.GENESIS, entry(id = "e0"))
        (1 until AuditLogFormat.MAX_ENTRIES + 10).forEach { index ->
            log = AuditLogFormat.append(log.log, log.anchor, entry(id = "e$index"))
        }

        val decoded = AuditLogFormat.decodeAll(log.log)
        assertEquals(AuditLogFormat.MAX_ENTRIES, decoded.size)
        assertEquals("e10", decoded.first().id)
        assertEquals("e${AuditLogFormat.MAX_ENTRIES + 9}", decoded.last().id)
    }

    /**
     * Trimming moves the point the chain starts from. Without carrying the anchor
     * forward, an ordinary trim would be indistinguishable from someone deleting
     * the first page of the history.
     */
    @Test
    fun `a trimmed log still verifies against its anchor`() {
        var log = AuditLogFormat.append(null, AuditChain.GENESIS, entry(id = "e0"))
        (1 until AuditLogFormat.MAX_ENTRIES + 5).forEach { index ->
            log = AuditLogFormat.append(log.log, log.anchor, entry(id = "e$index"))
        }

        assertNotEquals(AuditChain.GENESIS, log.anchor)
        assertTrue(AuditChain.verify(AuditLogFormat.decodeAll(log.log), log.anchor).isIntact)
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
