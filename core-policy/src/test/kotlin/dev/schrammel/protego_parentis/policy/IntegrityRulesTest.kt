package dev.schrammel.protego_parentis.policy

import dev.schrammel.protego_parentis.model.Heartbeat
import dev.schrammel.protego_parentis.model.IntegrityProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class IntegrityRulesTest {

    private val now: Instant = Instant.parse("2026-08-23T18:00:00Z")

    @Test
    fun `a healthy recent heartbeat reports nothing`() {
        assertTrue(IntegrityRules.problems(heartbeat(), now).isEmpty())
    }

    @Test
    fun `never hearing from the device is distinct from silence`() {
        assertEquals(
            listOf(IntegrityProblem.NEVER_HEARD),
            IntegrityRules.problems(null, now),
        )
    }

    @Test
    fun `a heartbeat older than the silence window is silence`() {
        val stale = heartbeat(sentAt = now.minusSeconds(3 * 60 * 60))

        assertEquals(listOf(IntegrityProblem.SILENT), IntegrityRules.problems(stale, now))
    }

    /**
     * The stale heartbeat's contents describe the device hours ago. Reporting them
     * next to "we have not heard from this phone" would send a parent after the
     * wrong problem.
     */
    @Test
    fun `silence hides whatever the stale heartbeat said`() {
        val stale = heartbeat(
            sentAt = now.minusSeconds(3 * 60 * 60),
            usageAccess = false,
            managed = false,
            battery = 4,
        )

        assertEquals(listOf(IntegrityProblem.SILENT), IntegrityRules.problems(stale, now))
    }

    @Test
    fun `reports every live problem at once`() {
        val struggling = heartbeat(usageAccess = false, managed = false, battery = 5)

        assertEquals(
            listOf(
                IntegrityProblem.USAGE_ACCESS_MISSING,
                IntegrityProblem.NOT_MANAGED,
                IntegrityProblem.BATTERY_LOW,
            ),
            IntegrityRules.problems(struggling, now),
        )
    }

    @Test
    fun `an unreadable battery level is not a low battery`() {
        assertTrue(IntegrityRules.problems(heartbeat(battery = -1), now).isEmpty())
    }

    /**
     * The clock on the child's device is one a motivated teenager can change, so a
     * heartbeat from the future must not be able to buy silence.
     */
    @Test
    fun `a heartbeat from the future counts as silence`() {
        val ahead = heartbeat(sentAt = now.plusSeconds(60 * 60))

        assertNull(IntegrityRules.quietFor(ahead, now))
        assertEquals(listOf(IntegrityProblem.SILENT), IntegrityRules.problems(ahead, now))
    }

    @Test
    fun `the silence window is exclusive at its edge`() {
        val exactly = heartbeat(sentAt = now.minusSeconds(2 * 60 * 60))
        val justInside = heartbeat(sentAt = now.minusSeconds(2 * 60 * 60 - 1))

        assertEquals(listOf(IntegrityProblem.SILENT), IntegrityRules.problems(exactly, now))
        assertTrue(IntegrityRules.problems(justInside, now).isEmpty())
    }

    @Test
    fun `reports how long the device has been quiet`() {
        val quiet = heartbeat(sentAt = now.minusSeconds(90 * 60))

        assertEquals(90.minutes, IntegrityRules.quietFor(quiet, now))
    }

    @Test
    fun `honours a caller-supplied silence window`() {
        val quiet = heartbeat(sentAt = now.minusSeconds(45 * 60))

        assertTrue(IntegrityRules.problems(quiet, now, silenceAfter = 1.hours).isEmpty())
        assertEquals(
            listOf(IntegrityProblem.SILENT),
            IntegrityRules.problems(quiet, now, silenceAfter = 30.minutes),
        )
    }

    private fun heartbeat(
        sentAt: Instant = now.minusSeconds(60),
        usageAccess: Boolean = true,
        managed: Boolean = true,
        battery: Int = 80,
        historyCount: Long = 0L,
        historyHead: String = "",
    ) = Heartbeat(
        sentAt = sentAt,
        policyVersion = 7L,
        batteryPercent = battery,
        managed = managed,
        usageAccessGranted = usageAccess,
        suspendedCount = 0,
        historyHead = historyHead,
        historyCount = historyCount,
    )

    // ---------- the rewrite that grows the log ----------

    /**
     * The blind spot: removing entries and adding more leaves a *higher* count
     * and a different head, which is exactly what honest progress looks like.
     *
     * Nothing in the pair of heartbeats can separate them. What can is evidence
     * the rewriting phone does not control — the entries of its own that already
     * reached somebody else's replica, through a section that replicates
     * append-only and refuses to let a later write revise an earlier one.
     */
    @Test
    fun `a rewrite that grows the log is caught by what the other phone already holds`() {
        val before = heartbeat(historyCount = 40, historyHead = "aaa")
        // Five removed, six added: the count went up, so both older checks pass.
        val after = heartbeat(historyCount = 41, historyHead = "bbb")

        assertFalse(IntegrityRules.historyRewritten(before, after))
        assertTrue(IntegrityRules.historyRewritten(before, after, heldFromDevice = 45))
    }

    @Test
    fun `holding fewer entries than were reported is ordinary`() {
        // The usual case: the replica prunes, the local log counts every entry
        // ever written, so the floor sits well below the reported total.
        val before = heartbeat(historyCount = 100, historyHead = "aaa")
        val after = heartbeat(historyCount = 101, historyHead = "bbb")

        assertFalse(IntegrityRules.historyRewritten(before, after, heldFromDevice = 12))
    }

    @Test
    fun `a phone that has reported nothing yet is not accused`() {
        val after = heartbeat(historyCount = 0, historyHead = "")

        assertFalse(IntegrityRules.historyRewritten(null, after, heldFromDevice = 30))
    }

    @Test
    fun `wiping the whole history is caught even without floor evidence`() {
        // The most obvious rewrite of all: delete everything. A device that had
        // reported real history and now reports none has shrunk its log to
        // nothing, which must not be excused just because zero also happens to
        // be what a device that has never reported yet would say.
        val before = heartbeat(historyCount = 40, historyHead = "aaa")
        val wiped = heartbeat(historyCount = 0, historyHead = "")

        assertTrue(IntegrityRules.historyRewritten(before, wiped))
    }

    @Test
    fun `the floor applies even on the first heartbeat ever seen`() {
        // No previous heartbeat to compare against, but the replica already holds
        // entries this phone wrote — so a lower total is still impossible.
        val first = heartbeat(historyCount = 3, historyHead = "aaa")

        assertTrue(IntegrityRules.historyRewritten(null, first, heldFromDevice = 30))
    }
}