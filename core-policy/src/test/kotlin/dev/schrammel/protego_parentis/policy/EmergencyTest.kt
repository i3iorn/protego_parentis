package dev.schrammel.protego_parentis.policy

import dev.schrammel.protego_parentis.model.AppRule
import dev.schrammel.protego_parentis.model.ChildId
import dev.schrammel.protego_parentis.model.DeviceState
import dev.schrammel.protego_parentis.model.DnsFilter
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.Policy
import dev.schrammel.protego_parentis.model.UsageSnapshot
import dev.schrammel.protego_parentis.model.WeeklyBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Duration.Companion.minutes

/**
 * What an SOS does to the rules (LOC-3).
 *
 * The whole feature is one sentence — while it is raised, nothing is restricted —
 * and the value of testing it is that the sentence stays true as the engine grows
 * new ways to restrict things.
 */
class EmergencyTest {

    private val zone: ZoneId = ZoneId.of("Europe/Stockholm")
    private val now: Instant = Instant.parse("2026-08-24T21:00:00Z")

    private val game = PackageName("com.example.game")
    private val chat = PackageName("com.example.chat")

    /** A policy that would suspend everything: the allowance is spent. */
    private val spent = Policy(
        version = 1L,
        childId = ChildId("test"),
        timeZone = zone,
        deviceBudget = WeeklyBudget.flat(30.minutes),
        appRules = mapOf(game to AppRule.Blocked(hidden = true)),
        networkBlockedPackages = setOf(chat),
        dnsFilter = DnsFilter("family.cloudflare-dns.com"),
    )

    @Test
    fun `everything is restricted before the button is pressed`() {
        val calm = evaluate(emergency = false)

        assertTrue(calm.suspend.containsKey(game))
        assertTrue(calm.suspend.containsKey(chat))
        assertEquals(setOf(game), calm.hide)
        assertEquals(setOf(chat), calm.networkBlocked)
        assertFalse(calm.emergency)
    }

    @Test
    fun `an emergency lifts every suspension`() {
        assertTrue(evaluate(emergency = true).suspend.isEmpty())
    }

    /** An icon that is not there is no use to anybody in an emergency. */
    @Test
    fun `an emergency puts hidden apps back`() {
        assertTrue(evaluate(emergency = true).hide.isEmpty())
    }

    /**
     * Lifted, not deferred. A deferred suspension is still coming, and a child who
     * pressed the button must not find the phone closing down again while they are
     * still in whatever made them press it.
     */
    @Test
    fun `an emergency lifts rather than defers`() {
        val enforcement = evaluate(emergency = true)

        assertTrue(enforcement.deferred.isEmpty())
        assertTrue(enforcement.suspend.isEmpty())
    }

    @Test
    fun `a live route still only defers`() {
        val navigating = PolicyEngine.evaluate(
            policy = spent,
            installedPackages = setOf(game, chat),
            usage = usage(),
            deviceState = DeviceState(navigatingPackage = game),
            now = now,
        )

        assertTrue(game !in navigating.suspend)
        assertEquals(setOf(game), navigating.deferred.keys)
    }

    /** A phone that cannot reach the network cannot reach anyone on it. */
    @Test
    fun `an emergency restores the network to every app`() {
        assertTrue(evaluate(emergency = true).networkBlocked.isEmpty())
    }

    /**
     * Filtering stays. It blocks sites, not calls or maps, so lifting it would buy
     * nothing and cost the one protection with no bearing on getting help.
     */
    @Test
    fun `an emergency leaves web filtering alone`() {
        assertEquals("family.cloudflare-dns.com", evaluate(emergency = true).dnsHost)
    }

    @Test
    fun `the emergency is visible on the enforcement itself`() {
        assertTrue(evaluate(emergency = true).emergency)
    }

    private fun evaluate(emergency: Boolean) = PolicyEngine.evaluate(
        policy = spent,
        installedPackages = setOf(game, chat),
        usage = usage(),
        deviceState = DeviceState(emergency = emergency),
        now = now,
    )

    private fun usage() = UsageSnapshot.empty(
        PolicyClock.policyDay(now, zone, spent.dayBoundaryHour),
    ).copy(deviceTotal = 60.minutes)
}
