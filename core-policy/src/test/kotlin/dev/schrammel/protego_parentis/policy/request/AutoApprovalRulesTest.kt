package dev.schrammel.protego_parentis.policy.request

import dev.schrammel.protego_parentis.model.AutoApproval
import dev.schrammel.protego_parentis.model.AutoApprovalWindow
import dev.schrammel.protego_parentis.model.CategoryId
import dev.schrammel.protego_parentis.model.GrantTarget
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.Request
import dev.schrammel.protego_parentis.model.RequestId
import dev.schrammel.protego_parentis.model.RequestState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration.Companion.minutes

/**
 * Standing answers to requests (RQ-1).
 *
 * The rule that runs through all of these: a rule is a cap, and anything outside
 * the cap falls through to a person rather than being refused. An auto-approval
 * that could deny would quietly turn the negotiation loop into a wall.
 */
class AutoApprovalRulesTest {

    private val now: Instant = Instant.parse("2026-08-26T18:00:00Z")
    private val dayStart: Instant = Instant.parse("2026-08-26T02:00:00Z")
    private val weekStart: Instant = Instant.parse("2026-08-20T02:00:00Z")

    private val rule = AutoApproval(
        id = "evenings",
        name = "A little extra",
        maxAmount = 15.minutes,
        maxPerWindow = 2,
        window = AutoApprovalWindow.WEEK,
    )

    @Test
    fun `answers a request inside the cap`() {
        val decision = decide(request(minutes = 15), listOf(rule), emptyList())

        assertEquals(AutoDecision.Approve(rule), decision)
    }

    @Test
    fun `a larger ask goes to a person`() {
        assertEquals(AutoDecision.Ask, decide(request(minutes = 16), listOf(rule), emptyList()))
    }

    @Test
    fun `a disabled rule answers nothing`() {
        val off = rule.copy(enabled = false)

        assertEquals(AutoDecision.Ask, decide(request(), listOf(off), emptyList()))
    }

    @Test
    fun `no rules means a person answers`() {
        assertEquals(AutoDecision.Ask, decide(request(), emptyList(), emptyList()))
    }

    // ---------- the window ----------

    @Test
    fun `stops once the window is spent`() {
        val spent = listOf(
            approvedBy(rule, at = weekStart.plusSeconds(3600)),
            approvedBy(rule, at = weekStart.plusSeconds(7200)),
        )

        assertEquals(AutoDecision.Ask, decide(request(), listOf(rule), spent))
    }

    @Test
    fun `still answers with room left in the window`() {
        val spent = listOf(approvedBy(rule, at = weekStart.plusSeconds(3600)))

        assertEquals(AutoDecision.Approve(rule), decide(request(), listOf(rule), spent))
    }

    @Test
    fun `approvals before the window do not count`() {
        val old = listOf(
            approvedBy(rule, at = weekStart.minusSeconds(1)),
            approvedBy(rule, at = weekStart.minusSeconds(86_400)),
        )

        assertEquals(AutoDecision.Approve(rule), decide(request(), listOf(rule), old))
    }

    @Test
    fun `a daily rule counts only today`() {
        val daily = rule.copy(window = AutoApprovalWindow.DAY, maxPerWindow = 1)
        val yesterday = listOf(approvedBy(daily, at = dayStart.minusSeconds(3600)))

        assertEquals(AutoDecision.Approve(daily), decide(request(), listOf(daily), yesterday))
    }

    /**
     * A parent saying yes by hand does not spend the standing allowance. The
     * allowance bounds what happens *without* them, so their own answers are
     * beside the point.
     */
    @Test
    fun `a parent's own approvals do not spend the allowance`() {
        val byHand = listOf(
            approvedBy(by = "parent", at = weekStart.plusSeconds(3600)),
            approvedBy(by = "parent", at = weekStart.plusSeconds(7200)),
            approvedBy(by = "parent", at = weekStart.plusSeconds(10_800)),
        )

        assertEquals(AutoDecision.Approve(rule), decide(request(), listOf(rule), byHand))
    }

    /** Two rules, two allowances. One does not eat the other's. */
    @Test
    fun `another rule's approvals do not spend this one's`() {
        val other = rule.copy(id = "weekends", name = "Weekend top-up")
        val spent = listOf(
            approvedBy(other, at = weekStart.plusSeconds(3600)),
            approvedBy(other, at = weekStart.plusSeconds(7200)),
        )

        assertEquals(AutoDecision.Approve(rule), decide(request(), listOf(rule), spent))
    }

    @Test
    fun `the request being decided does not count against itself`() {
        val pending = request()
        val history = listOf(
            pending.copy(
                state = RequestState.APPROVED,
                decidedAt = now,
                decidedBy = AutoApprovalRules.decidedBy(rule),
                autoApprovedBy = rule.id,
            ),
        )

        // Same id, so it must be ignored rather than counted as a prior use.
        assertEquals(
            AutoDecision.Approve(rule),
            AutoApprovalRules.decide(pending, listOf(rule), history, dayStart, weekStart),
        )
    }

    // ---------- targets ----------

    @Test
    fun `a rule with no target answers for anything`() {
        val anyTarget = rule.copy(target = null)

        assertEquals(
            AutoDecision.Approve(anyTarget),
            decide(request(target = GrantTarget.App(PackageName("com.example"))), listOf(anyTarget), emptyList()),
        )
    }

    @Test
    fun `a targeted rule answers only for its target`() {
        val games = GrantTarget.Category(CategoryId("games"))
        val targeted = rule.copy(target = games)

        assertEquals(AutoDecision.Approve(targeted), decide(request(target = games), listOf(targeted), emptyList()))
        assertEquals(AutoDecision.Ask, decide(request(target = GrantTarget.Device), listOf(targeted), emptyList()))
    }

    // ---------- overlaps ----------

    /**
     * Two rules that both fit means the family expressed two intentions. Honouring
     * the tighter one is the reading that cannot surprise them.
     */
    @Test
    fun `the tightest applicable rule wins`() {
        val generous = rule.copy(id = "generous", maxAmount = 30.minutes)
        val tight = rule.copy(id = "tight", maxAmount = 10.minutes)

        assertEquals(
            AutoDecision.Approve(tight),
            decide(request(minutes = 10), listOf(generous, tight), emptyList()),
        )
    }

    @Test
    fun `falls back to the wider rule when the tight one does not fit`() {
        val generous = rule.copy(id = "generous", maxAmount = 30.minutes)
        val tight = rule.copy(id = "tight", maxAmount = 10.minutes)

        assertEquals(
            AutoDecision.Approve(generous),
            decide(request(minutes = 20), listOf(generous, tight), emptyList()),
        )
    }

    @Test
    fun `falls back to the wider rule when the tight one is spent`() {
        val generous = rule.copy(id = "generous", maxAmount = 30.minutes)
        val tight = rule.copy(id = "tight", maxAmount = 10.minutes, maxPerWindow = 1)
        val spent = listOf(approvedBy(tight, at = weekStart.plusSeconds(60)))

        assertEquals(
            AutoDecision.Approve(generous),
            decide(request(minutes = 10), listOf(generous, tight), spent),
        )
    }

    // ---------- signatures ----------

    @Test
    fun `an automatic approval is distinguishable from a person's`() {
        val automatic = approvedBy(rule, at = now)
        val byHand = approvedBy(by = "parent", at = now)

        assertTrue(AutoApprovalRules.wasAutomatic(automatic))
        assertFalse(AutoApprovalRules.wasAutomatic(byHand))
    }

    @Test
    fun `the signature names the rule that answered`() {
        assertTrue(AutoApprovalRules.decidedBy(rule).contains(rule.id))
    }

    // ---------- invariants ----------

    @Test
    fun `a rule that grants nothing is refused at construction`() {
        listOf(
            { rule.copy(maxAmount = 0.minutes) },
            { rule.copy(maxPerWindow = 0) },
            { rule.copy(name = " ") },
            { rule.copy(id = "") },
        ).forEach { build ->
            try {
                build()
                throw AssertionError("expected an invalid rule to be refused")
            } catch (_: IllegalArgumentException) {
                // Expected.
            }
        }
    }

    // ---------- helpers ----------

    private fun decide(
        request: Request,
        rules: List<AutoApproval>,
        history: List<Request>,
    ) = AutoApprovalRules.decide(request, rules, history, dayStart, weekStart)

    private fun request(
        minutes: Int = 15,
        target: GrantTarget = GrantTarget.Device,
        id: String = "r1",
    ) = Request(
        id = RequestId(id),
        target = target,
        amount = minutes.minutes,
        reason = "please",
        state = RequestState.PENDING,
        createdAt = now,
        expiresAt = now.plusSeconds(7200),
    )

    private fun approvedBy(
        rule: AutoApproval? = null,
        by: String? = null,
        at: Instant,
    ) = Request(
        id = RequestId("past-${at.toEpochMilli()}-${rule?.id ?: by}"),
        target = GrantTarget.Device,
        amount = 15.minutes,
        reason = "please",
        state = RequestState.APPROVED,
        createdAt = at.minusSeconds(60),
        expiresAt = at.plusSeconds(7200),
        decidedAt = at,
        decidedBy = by ?: AutoApprovalRules.decidedBy(checkNotNull(rule)),
        autoApprovedBy = rule?.id,
    )
}
