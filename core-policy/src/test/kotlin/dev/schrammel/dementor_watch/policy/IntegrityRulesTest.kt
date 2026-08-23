package dev.schrammel.dementor_watch.policy

import dev.schrammel.dementor_watch.model.Heartbeat
import dev.schrammel.dementor_watch.model.IntegrityProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    ) = Heartbeat(
        sentAt = sentAt,
        policyVersion = 7L,
        batteryPercent = battery,
        managed = managed,
        usageAccessGranted = usageAccess,
        suspendedCount = 0,
    )
}
