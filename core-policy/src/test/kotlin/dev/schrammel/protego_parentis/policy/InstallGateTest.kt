package dev.schrammel.protego_parentis.policy

import dev.schrammel.protego_parentis.model.ChildId
import dev.schrammel.protego_parentis.model.InstallPolicy
import dev.schrammel.protego_parentis.model.Policy
import dev.schrammel.protego_parentis.model.UsageSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** The install gate and its escape hatch (AC-3). */
class InstallGateTest {

    private val zone: ZoneId = ZoneId.of("Europe/Stockholm")
    private val now: Instant = Instant.parse("2026-08-23T14:00:00Z")

    @Test
    fun `notify never blocks installs`() {
        assertFalse(evaluate(policy(InstallPolicy.NOTIFY)).installsBlocked)
    }

    @Test
    fun `gated blocks installs with no window open`() {
        assertTrue(evaluate(policy(InstallPolicy.GATED)).installsBlocked)
    }

    @Test
    fun `an open window lets installs through`() {
        val open = policy(InstallPolicy.GATED, until = now.plusSeconds(600))

        assertFalse(evaluate(open).installsBlocked)
    }

    @Test
    fun `a closed window blocks again`() {
        val closed = policy(InstallPolicy.GATED, until = now.minusSeconds(1))

        assertTrue(evaluate(closed).installsBlocked)
    }

    @Test
    fun `the window is closed at the moment it expires`() {
        val expiring = policy(InstallPolicy.GATED, until = now)

        assertTrue(evaluate(expiring).installsBlocked)
    }

    /** A window left over from a policy that is no longer gated changes nothing. */
    @Test
    fun `a stale window does not unblock a notify policy`() {
        val odd = policy(InstallPolicy.NOTIFY, until = now.plusSeconds(600))

        assertFalse(evaluate(odd).installsBlocked)
    }

    /**
     * Nothing else wakes the device when a window runs out, so without this the
     * gate would stay open until some unrelated change happened to trigger a tick.
     */
    @Test
    fun `the tick wakes up when the window closes`() {
        val closesAt = now.plusSeconds(600)

        assertEquals(closesAt, evaluate(policy(InstallPolicy.GATED, until = closesAt)).nextChangeAt)
    }

    private fun evaluate(policy: Policy) = PolicyEngine.evaluate(
        policy = policy,
        installedPackages = emptySet(),
        usage = UsageSnapshot.empty(
            PolicyClock.policyDay(now, zone, policy.dayBoundaryHour),
        ),
        now = now,
    )

    private fun policy(installPolicy: InstallPolicy, until: Instant? = null) = Policy(
        version = 1L,
        childId = ChildId("test"),
        timeZone = zone,
        installPolicy = installPolicy,
        installWindowUntil = until,
    )
}
