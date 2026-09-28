package dev.schrammel.protego_parentis.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * The `init` checks on model types. Rules, places and requests arrive from
 * other phones, and the codecs rely on these checks to turn a malformed value
 * into a dropped record rather than a rule that means something else. So each
 * boundary is pinned here: the last value accepted and the first refused.
 */
class ModelInvariantsTest {

    private val now: Instant = Instant.parse("2026-09-28T10:00:00Z")

    @Test
    fun `a glucose level must be a plausible reading`() {
        Condition.GlucoseBelow(20)
        Condition.GlucoseAbove(600)
        assertThrows(IllegalArgumentException::class.java) { Condition.GlucoseBelow(19) }
        assertThrows(IllegalArgumentException::class.java) { Condition.GlucoseAbove(601) }
        assertEquals(20..600, Condition.GLUCOSE_RANGE)
    }

    @Test
    fun `a missing-glucose gap runs from five minutes to a day`() {
        Condition.GlucoseStale(5)
        Condition.GlucoseStale(24 * 60)
        assertThrows(IllegalArgumentException::class.java) { Condition.GlucoseStale(4) }
        assertThrows(IllegalArgumentException::class.java) { Condition.GlucoseStale(24 * 60 + 1) }
    }

    @Test
    fun `a battery threshold is a percentage`() {
        Condition.BatteryBelow(1)
        Condition.BatteryBelow(100)
        assertThrows(IllegalArgumentException::class.java) { Condition.BatteryBelow(0) }
        assertThrows(IllegalArgumentException::class.java) { Condition.BatteryBelow(101) }
    }

    @Test
    fun `spent minutes and at-least counts cannot go below their floor`() {
        Condition.AllowanceSpent(0)
        assertThrows(IllegalArgumentException::class.java) { Condition.AllowanceSpent(-1) }
        Condition.AtLeast(1, emptyList())
        assertThrows(IllegalArgumentException::class.java) { Condition.AtLeast(0, emptyList()) }
    }

    @Test
    fun `a rule needs an id, a name and something to do`() {
        rule()
        assertThrows(IllegalArgumentException::class.java) { rule(id = " ") }
        assertThrows(IllegalArgumentException::class.java) { rule(name = "") }
        assertThrows(IllegalArgumentException::class.java) { rule(actions = emptyList()) }
    }

    @Test
    fun `a rule applies while its condition holds and its exception does not`() {
        val plain = rule()
        assertEquals(Condition.Always, plain.activeWhen)

        val excepted = rule(exceptWhen = Condition.BatteryBelow(20))
        assertEquals(
            Condition.AllOf(listOf(Condition.Always, Condition.Not(Condition.BatteryBelow(20)))),
            excepted.activeWhen,
        )
    }

    @Test
    fun `a place has a name, real coordinates and a radius GPS can hold`() {
        place()
        assertThrows(IllegalArgumentException::class.java) { place(name = " ") }
        assertThrows(IllegalArgumentException::class.java) { place(latitude = 90.1) }
        assertThrows(IllegalArgumentException::class.java) { place(longitude = -180.1) }
        assertThrows(IllegalArgumentException::class.java) {
            place(radiusMetres = Place.MINIMUM_RADIUS_METRES - 1)
        }
    }

    @Test
    fun `a request asks for time, expires after it is made, and records when it settled`() {
        request()
        assertThrows(IllegalArgumentException::class.java) { request(amount = Duration.ZERO) }
        assertThrows(IllegalArgumentException::class.java) { request(expiresAt = now) }
        assertThrows(IllegalArgumentException::class.java) {
            request(state = RequestState.DENIED, decidedAt = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            request(state = RequestState.DENIED, decidedAt = now, autoApprovedBy = "rule-1")
        }
        request(state = RequestState.APPROVED, decidedAt = now, autoApprovedBy = "rule-1")
    }

    @Test
    fun `only an open request expires`() {
        val open = request()
        assertFalse(open.hasExpired(now))
        assertTrue(open.hasExpired(now.plusSeconds(3600)))
        assertFalse(request(state = RequestState.APPROVED, decidedAt = now).hasExpired(now.plusSeconds(3600)))
    }

    @Test
    fun `an auto-approval has to be able to grant something`() {
        autoApproval()
        assertThrows(IllegalArgumentException::class.java) { autoApproval(maxAmount = Duration.ZERO) }
        assertThrows(IllegalArgumentException::class.java) { autoApproval(maxPerWindow = 0) }
        assertThrows(IllegalArgumentException::class.java) { autoApproval(name = "") }
    }

    @Test
    fun `a private DNS filter is a bare hostname`() {
        DnsFilter("family.cloudflare-dns.com")
        assertThrows(IllegalArgumentException::class.java) { DnsFilter("") }
        assertThrows(IllegalArgumentException::class.java) { DnsFilter("https://dns.example") }
        assertThrows(IllegalArgumentException::class.java) { DnsFilter("dns.example:853") }
        assertThrows(IllegalArgumentException::class.java) { DnsFilter("dns example") }
        assertThrows(IllegalArgumentException::class.java) { DnsFilter("a".repeat(254)) }
    }

    private fun rule(
        id: String = "rule-1",
        name: String = "Bedtime",
        actions: List<RuleAction> = listOf(RuleAction(RuleEffect.Block(), RuleTarget.Everything)),
        exceptWhen: Condition? = null,
    ) = ContextRule(id, name, enabled = true, condition = Condition.Always, actions = actions, exceptWhen = exceptWhen)

    private fun place(
        name: String = "School",
        latitude: Double = 59.33,
        longitude: Double = 18.06,
        radiusMetres: Int = Place.MINIMUM_RADIUS_METRES,
    ) = Place(PlaceId("school"), name, latitude, longitude, radiusMetres)

    private fun request(
        amount: Duration = 15.minutes,
        state: RequestState = RequestState.PENDING,
        expiresAt: Instant = now.plusSeconds(600),
        decidedAt: Instant? = null,
        autoApprovedBy: String? = null,
    ) = Request(
        id = RequestId("req-1"),
        target = GrantTarget.Device,
        amount = amount,
        reason = "Homework",
        state = state,
        createdAt = now,
        expiresAt = expiresAt,
        decidedAt = decidedAt,
        autoApprovedBy = autoApprovedBy,
    )

    private fun autoApproval(
        name: String = "Homework",
        maxAmount: Duration = 30.minutes,
        maxPerWindow: Int = 3,
    ) = AutoApproval(id = "auto-1", name = name, maxAmount = maxAmount, maxPerWindow = maxPerWindow)
}
