package dev.schrammel.protego_parentis.policy

import dev.schrammel.protego_parentis.model.Heartbeat
import dev.schrammel.protego_parentis.model.IntegrityProblem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Catching a history that was rebuilt rather than appended to (TR-3).
 *
 * A hash chain kept only on the phone that writes it proves nothing against
 * whoever holds that phone: they can recompute it. The parents' copy of the head
 * and the running count is the part that lives out of reach.
 */
class HistoryWitnessTest {

    private val now: Instant = Instant.parse("2026-08-25T09:00:00Z")

    @Test
    fun `a growing history is not suspicious`() {
        val before = heartbeat(head = "aaa", count = 10)
        val after = heartbeat(head = "bbb", count = 11)

        assertFalse(IntegrityRules.historyRewritten(before, after))
    }

    @Test
    fun `an unchanged history is not suspicious`() {
        val before = heartbeat(head = "aaa", count = 10)

        assertFalse(IntegrityRules.historyRewritten(before, heartbeat(head = "aaa", count = 10)))
    }

    /** Entries were removed: an honest log only ever gains them. */
    @Test
    fun `a count that goes backwards is suspicious`() {
        val before = heartbeat(head = "aaa", count = 10)
        val after = heartbeat(head = "bbb", count = 4)

        assertTrue(IntegrityRules.historyRewritten(before, after))
    }

    /** Entries were rewritten in place: the head moved without the log growing. */
    @Test
    fun `a head that moves without the count moving is suspicious`() {
        val before = heartbeat(head = "aaa", count = 10)
        val after = heartbeat(head = "bbb", count = 10)

        assertTrue(IntegrityRules.historyRewritten(before, after))
    }

    @Test
    fun `a first sighting says nothing`() {
        assertFalse(IntegrityRules.historyRewritten(null, heartbeat(head = "aaa", count = 10)))
    }

    /** An older child build reports no history at all; that is not an accusation. */
    @Test
    fun `a device that reports no history says nothing`() {
        val before = heartbeat(head = "aaa", count = 10)

        assertFalse(IntegrityRules.historyRewritten(before, heartbeat(head = "", count = 0)))
        assertFalse(
            IntegrityRules.historyRewritten(
                heartbeat(head = "", count = 0),
                heartbeat(head = "aaa", count = 3),
            ),
        )
    }

    @Test
    fun `a rewritten history is reported alongside the rest`() {
        val problems = IntegrityRules.problems(
            heartbeat = heartbeat(head = "aaa", count = 10),
            now = now,
            historyRewritten = true,
        )

        assertTrue(IntegrityProblem.HISTORY_REWRITTEN in problems)
    }

    /** Urgent: unlike a flat battery, this is a thing somebody did. */
    @Test
    fun `a rewritten history is worth interrupting a parent for`() {
        assertTrue(IntegrityProblem.HISTORY_REWRITTEN.isUrgent)
        assertFalse(IntegrityProblem.BATTERY_LOW.isUrgent)
    }

    /** A silent device reports silence and nothing else, as ever. */
    @Test
    fun `silence still hides everything behind it`() {
        val problems = IntegrityRules.problems(
            heartbeat = heartbeat(head = "aaa", count = 10, sentAt = now.minusSeconds(4 * 3600)),
            now = now,
            historyRewritten = true,
        )

        assertTrue(problems == listOf(IntegrityProblem.SILENT))
    }

    private fun heartbeat(
        head: String,
        count: Long,
        sentAt: Instant = now.minusSeconds(60),
    ) = Heartbeat(
        sentAt = sentAt,
        policyVersion = 3L,
        batteryPercent = 80,
        managed = true,
        usageAccessGranted = true,
        suspendedCount = 0,
        historyHead = head,
        historyCount = count,
    )
}
