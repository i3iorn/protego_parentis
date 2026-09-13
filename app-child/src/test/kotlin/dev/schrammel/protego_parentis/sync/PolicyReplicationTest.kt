package dev.schrammel.protego_parentis.sync

import android.content.Context
import dev.schrammel.protego_parentis.sync.core.*
import dev.schrammel.protego_parentis.model.BrowserPolicy
import dev.schrammel.protego_parentis.model.Condition
import dev.schrammel.protego_parentis.model.ContextRule
import dev.schrammel.protego_parentis.model.DailyGoal
import dev.schrammel.protego_parentis.model.RuleEffect
import dev.schrammel.protego_parentis.model.RuleTarget
import dev.schrammel.protego_parentis.rules.ContextRuleCodec
import dev.schrammel.protego_parentis.rules.ContextRuleStore
import dev.schrammel.protego_parentis.rules.DnsProfile
import dev.schrammel.protego_parentis.rules.Feature
import dev.schrammel.protego_parentis.rules.LocalPolicyStore
import dev.schrammel.protego_parentis.security.DeviceRole
import dev.schrammel.protego_parentis.security.RoleStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.LocalDate
import kotlin.time.Duration.Companion.minutes
import dev.schrammel.protego_parentis.sync.core.DeviceId
import dev.schrammel.protego_parentis.security.LocalDeviceId

/**
 * What the managed phone publishes about its own rules, and when it decides not
 * to bother.
 *
 * These tests exist because of [LocalDeviceId]. Everything here goes through
 * `PolicyReplication.write`, which asks who this phone is and returns an empty
 * list if it cannot find out — and under Robolectric it never could, because
 * `KeystoreSigner` needs a keystore provider Robolectric does not implement. A
 * test written before the seam would have watched every one of these produce
 * nothing and called it a pass.
 */
@RunWith(RobolectricTestRunner::class)
class PolicyReplicationTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    /**
     * Spelled out rather than built from [PolicyReplication]'s own constants,
     * and not only because they are private.
     *
     * This is a path on the wire. Phones already in the field hold it in their
     * replicas and compare it in every digest, so renaming it is a compatibility
     * break rather than a refactor. A test written in terms of the constant
     * would follow such a rename without a word; this one does not.
     */
    private val dailyLimitPath = "policy/THISPHONE/dailyLimitMinutes"

    private val thisChildId = DeviceId("THISPHONE")

    @Before
    fun useAKnownIdentity() {
        LocalDeviceId.useInTests("THISPHONE")
        FamilyData.forget(context)
    }

    /** The override outlives the test if it is not put back. */
    @After
    fun restore() {
        FamilyData.forget(context)
        LocalDeviceId.useInTests(null)
    }

    private fun rules() = LocalPolicyStore(context)

    private fun push() = PolicyReplication(context).pushFromLocalPolicy(thisChildId)

    /** A fully decodable [ContextRule], flattened the way a parent's write would replicate it. */
    private fun remoteRuleOps(ruleId: String, name: String): Map<String, String?> = mapOf(
        "policy/THISPHONE/contextRules/$ruleId/name" to name,
        "policy/THISPHONE/contextRules/$ruleId/enabled" to "true",
        "policy/THISPHONE/contextRules/$ruleId/condition" to
            ContextRuleCodec.encodeConditionToString(Condition.Always),
        "policy/THISPHONE/contextRules/$ruleId/effect" to
            ContextRuleCodec.encodeEffectToString(RuleEffect.Block()),
        "policy/THISPHONE/contextRules/$ruleId/target" to
            ContextRuleCodec.encodeTargetToString(RuleTarget.Everything),
    )

    // ---------- the gate ----------

    @Test
    fun `the first push sends the rules`() {
        rules().dailyLimitMinutes = 90

        val ops = push()

        assertTrue(ops.isNotEmpty())
        assertEquals("90", ReplicatedStateStore(context).value(dailyLimitPath))
    }

    /**
     * The enforcement tick runs every few seconds and pushes each time.
     *
     * Flattening the whole policy into two hundred paths to conclude nothing
     * changed is a poor thing to do all day on a battery, so the version is
     * checked first.
     */
    @Test
    fun `pushing again with nothing changed sends nothing`() {
        rules().dailyLimitMinutes = 90
        push()

        assertTrue(push().isEmpty())
    }

    @Test
    fun `changing a rule makes the next push send again`() {
        rules().dailyLimitMinutes = 90
        push()

        rules().dailyLimitMinutes = 45

        assertTrue(push().isNotEmpty())
        assertEquals("45", ReplicatedStateStore(context).value(dailyLimitPath))
    }

    // ---------- the gate against the wipe ----------

    /**
     * Leaving a family and joining another must not leave the new one with no
     * rules at all.
     *
     * This is the defect the gate introduced and the reason it needed a
     * companion. Leaving wipes the replica, but the rules themselves have not
     * changed, so their version has not moved — and the gate reads the version.
     * Without [PolicyReplication.forgetPushed] the managed phone would join its
     * next family and publish nothing whatsoever, leaving its parents on an
     * empty Rules screen until somebody happened to edit something.
     *
     * It is the exact shape of the bug the gate was added alongside fixing,
     * moved one step along, and it was found by reading rather than by anything
     * failing. This is that reading, written down so it cannot come back.
     */
    @Test
    fun `after leaving a family the rules are published to the next one`() {
        rules().dailyLimitMinutes = 90
        push()
        assertTrue("the gate should have closed", push().isEmpty())

        FamilyData.forget(context)

        val ops = push()
        assertTrue("nothing was published to the new family", ops.isNotEmpty())
        assertEquals("90", ReplicatedStateStore(context).value(dailyLimitPath))
    }

    /** The wipe really does empty the replica, so the test above is not vacuous. */
    @Test
    fun `leaving a family empties the replica first`() {
        rules().dailyLimitMinutes = 90
        push()
        assertFalse(ReplicatedStateStore(context).entries.isEmpty())

        FamilyData.forget(context)

        assertTrue(ReplicatedStateStore(context).entries.isEmpty())
    }

    // ---------- what a push is worth without an identity ----------

    /**
     * A phone that cannot say who it is publishes nothing — and does not record
     * that it has.
     *
     * The keystore can genuinely be unavailable: a key invalidated by a
     * biometric change, a restored backup, the moments before first unlock. The
     * write correctly produces nothing, because an op with no origin cannot be
     * attributed and the shared history is built on knowing which device wrote
     * what.
     *
     * The gate must not count that as done. It used to: the version was recorded
     * whether or not anything was published, so a phone whose keystore was
     * briefly unavailable would mark its rules as pushed, publish none of them,
     * and then refuse to try again until somebody happened to edit a rule. Same
     * shape as the bug that came with the gate, one step further along.
     */
    @Test
    fun `a phone with no identity publishes nothing and tries again later`() {
        rules().dailyLimitMinutes = 90
        LocalDeviceId.useInTests(null)

        assertTrue("published without an identity", push().isEmpty())

        LocalDeviceId.useInTests("THISPHONE")

        assertTrue("gave up after one failure", push().isNotEmpty())
        assertEquals("90", ReplicatedStateStore(context).value(dailyLimitPath))
    }

    // ---------- #42: web filter, goal, features ride this same channel ----------

    @Test
    fun `web filter, goal and feature settings round-trip through push and read`() {
        rules().webFilterProfile = DnsProfile.ADULT
        rules().webFilterCustomHost = "example.com"
        rules().browserPolicy = BrowserPolicy(
            forceSafeSearch = true,
            blockedUrls = listOf("bad.example.com"),
            allowedUrls = listOf("homework.example.com"),
        )
        rules().webFilterCountVisits = true
        rules().goal = DailyGoal(
            enabled = true,
            maxUsage = 90.minutes,
            prize = "Extra bonus time",
            effectiveFrom = LocalDate.now(),
        )
        rules().disabledFeatures = setOf(Feature.WEB_ACTIVITY, Feature.CHAT)

        push()
        val read = PolicyReplication(context).read(thisChildId)

        assertEquals("ADULT", read?.webFilterProfile)
        assertEquals("example.com", read?.webFilterCustomHost)
        assertEquals(true, read?.webFilterSafeSearch)
        assertEquals(setOf("bad.example.com"), read?.webFilterBlockedUrls)
        assertEquals(setOf("homework.example.com"), read?.webFilterAllowedUrls)
        assertEquals(true, read?.webFilterCountVisits)
        assertEquals(true, read?.goalEnabled)
        assertEquals(90, read?.goalMaxMinutes)
        assertEquals("Extra bonus time", read?.goalPrize)
        assertEquals(setOf("WEB_ACTIVITY", "CHAT"), read?.disabledFeatures)
    }

    // ---------- #104: the daily limit merges the same way every other rule does ----------

    /**
     * The daily limit used to have a second way to change it — the dedicated
     * `SET_DAILY_LIMIT` sync message — that raced this register rather than
     * going through it. Now that it is gone (#104), two parents changing the
     * limit while out of contact resolve exactly the way
     * `PolicyReplicationContextRuleTest` and `PolicyReplicationScheduleTest`
     * already prove for their own rule kinds: whichever write has the higher
     * Lamport stamp wins, whatever order the two arrive in.
     */
    @Test
    fun `two parents changing the daily limit concurrently converge on the higher lamport stamp, in every order`() {
        val fromA = StateOp(dailyLimitPath, "90", lamport = 5, origin = "AAAA")
        val fromB = StateOp(dailyLimitPath, "45", lamport = 9, origin = "BBBB")

        listOf(
            listOf(fromA, fromB),
            listOf(fromB, fromA),
        ).forEach { ops ->
            ReplicatedStateStore(context).forgetAll()
            ReplicatedStateStore(context).receive(ops)

            assertEquals("45", PolicyReplication(context).read(thisChildId)?.dailyLimitMinutes.toString())
        }
    }

    // ---------- recovering from defaultChildId()'s old null-on-the-managed-phone bug ----------

    /**
     * Regression for the same underlying bug [SyncReceiverTest] pins from the
     * sync-delivery side: before [PolicyReplication.defaultChildId] learned to
     * resolve its own id directly, a managed phone's replica could hold a
     * rule bit for bit correctly and still never materialise it locally,
     * because every `pull*IntoLocal` no-opped on the null childId that
     * lookup always returned there. [PolicyReplication.backfillIfNeeded]
     * exists to recover a family that was already stuck that way by the
     * time the phone updated, since a delta-triggered re-pull never revisits
     * state that has not changed since.
     */
    @Test
    fun `backfillIfNeeded pulls already-replicated state into local storage`() {
        RoleStore(context).choose(DeviceRole.CHILD)
        ReplicatedStateStore(context).writeAll(
            remoteRuleOps("rule-1", "No games after nine"),
            origin = "A-PARENT-DEVICE",
            role = SyncRole.PARENT,
        )

        PolicyReplication(context).backfillIfNeeded()

        val rules = ContextRuleStore(context).rules
        assertTrue(rules.any { it.id == "rule-1" && it.name == "No games after nine" })
    }

    @Test
    fun `backfillIfNeeded only ever runs once`() {
        RoleStore(context).choose(DeviceRole.CHILD)
        ReplicatedStateStore(context).writeAll(remoteRuleOps("rule-1", "First backfill"), origin = "A-PARENT-DEVICE", role = SyncRole.PARENT)
        PolicyReplication(context).backfillIfNeeded()
        assertTrue(ContextRuleStore(context).rules.any { it.id == "rule-1" })

        // A local edit made after the one-time backfill must survive a second
        // call to it — otherwise every later app start would clobber whatever
        // the family had changed since with whatever the replica held at the
        // moment of the very first backfill.
        ContextRuleStore(context).upsert(
            ContextRule(
                id = "local-only",
                name = "Added after backfill",
                enabled = true,
                condition = Condition.Always,
                effect = RuleEffect.Block(),
                target = RuleTarget.Everything,
            ),
        )
        // Something new arrives in the replica too, so a re-run (if it
        // happened) would have something fresh to pull rather than the
        // test passing by coincidence.
        ReplicatedStateStore(context).writeAll(
            remoteRuleOps("rule-2", "Set after the flag was already recorded"),
            origin = "A-PARENT-DEVICE",
            role = SyncRole.PARENT,
        )

        PolicyReplication(context).backfillIfNeeded()

        val rules = ContextRuleStore(context).rules
        assertTrue("the local edit must survive", rules.any { it.id == "local-only" })
        assertTrue("a second run must not have happened", rules.none { it.id == "rule-2" })
    }

    @Test
    fun `backfillIfNeeded does nothing on a parent's own phone`() {
        RoleStore(context).choose(DeviceRole.PARENT)
        ReplicatedStateStore(context).writeAll(
            mapOf("policy/THISPHONE/contextRules/rule-1/name" to "Should not land here"),
            origin = "A-PARENT-DEVICE",
            role = SyncRole.PARENT,
        )

        PolicyReplication(context).backfillIfNeeded()

        assertTrue(ContextRuleStore(context).rules.isEmpty())
    }
}
