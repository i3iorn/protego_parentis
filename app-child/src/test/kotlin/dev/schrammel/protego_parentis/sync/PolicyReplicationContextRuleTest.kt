package dev.schrammel.protego_parentis.sync

import android.content.Context
import dev.schrammel.protego_parentis.sync.core.*
import dev.schrammel.protego_parentis.model.CategoryId
import dev.schrammel.protego_parentis.model.Condition
import dev.schrammel.protego_parentis.model.ContextRule
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.RuleEffect
import dev.schrammel.protego_parentis.model.RuleTarget
import dev.schrammel.protego_parentis.rules.ContextRuleStore
import dev.schrammel.protego_parentis.security.DeviceRole
import dev.schrammel.protego_parentis.security.RoleStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Contextual rules as the second worked example of #40's field-per-path
 * modelling, after schedules — see [PolicyReplicationScheduleTest]. The
 * `condition`/`effect`/`target` fields are each one opaque JSON register
 * rather than decomposed further, so the concurrency property under test is
 * inter-rule and inter-field, not inside a single condition tree.
 */
@RunWith(RobolectricTestRunner::class)
class PolicyReplicationContextRuleTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val childId = DeviceId("CHILD")

    private fun store() = ReplicatedStateStore(context)
    private fun replication() = PolicyReplication(context)

    @Before
    fun setUp() {
        LocalDeviceId.useInTests("PARENT")
        RoleStore(context).choose(DeviceRole.PARENT)
    }

    @After
    fun tearDown() {
        store().forgetAll()
        ContextRuleStore(context).rules = emptyList()
        LocalDeviceId.useInTests(null)
    }

    private fun path(ruleId: String, field: String) = "policy/${childId.value}/contextRules/$ruleId/$field"

    private fun op(ruleId: String, field: String, value: String?, lamport: Long, origin: String) =
        StateOp(path(ruleId, field), value, lamport, origin)

    // ---------- concurrent edits to different fields ----------

    /**
     * Two parents, each editing a different field of the same rule while out
     * of contact. Both edits have to survive, the same property
     * [PolicyReplicationScheduleTest] proves for a schedule.
     */
    @Test
    fun `two parents editing different fields of one rule both win, in every order`() {
        val fromA = listOf(
            op("R1", "name", "No games at dinner", lamport = 1, origin = "AAAA"),
            op("R1", "condition", """{"kind":"place","value":"kitchen"}""", lamport = 1, origin = "AAAA"),
            op("R1", "effect", """{"kind":"block"}""", lamport = 1, origin = "AAAA"),
            op("R1", "target", """{"kind":"everything"}""", lamport = 1, origin = "AAAA"),
        )
        val fromB = listOf(op("R1", "enabled", "false", lamport = 2, origin = "BBBB"))

        listOf(fromA + fromB, fromB + fromA, (fromA + fromB).shuffled(seededRandom())).forEach { ops ->
            store().forgetAll()
            store().receive(ops)

            val rule = replication().readContextRules(childId).singleOrNull()
            assertEquals("No games at dinner", rule?.name)
            assertEquals(false, rule?.enabled)
        }
    }

    // ---------- delete racing an edit ----------

    /**
     * As [PolicyReplicationScheduleTest]'s delete-races-a-rename test: whether
     * a rule reads as gone must not depend on arrival order or which write has
     * the higher Lamport stamp, because a delete and a concurrent edit write
     * different paths.
     */
    @Test
    fun `a delete racing an edit resolves identically whatever order they arrive, whichever stamp is higher`() {
        val deleteOp = op("R1", "deleted", "1700000100", lamport = 10, origin = "AAAA")
        val editOp = op("R1", "name", "Still here?", lamport = 20, origin = "BBBB")
        val created = listOf(
            op("R1", "name", "Original", lamport = 1, origin = "AAAA"),
            op("R1", "condition", """{"kind":"always"}""", lamport = 1, origin = "AAAA"),
            op("R1", "effect", """{"kind":"block"}""", lamport = 1, origin = "AAAA"),
            op("R1", "target", """{"kind":"everything"}""", lamport = 1, origin = "AAAA"),
        )

        listOf(
            created + listOf(deleteOp, editOp),
            created + listOf(editOp, deleteOp),
            (created + listOf(deleteOp, editOp)).shuffled(seededRandom()),
        ).forEach { ops ->
            store().forgetAll()
            store().receive(ops)

            assertTrue(
                "the rule should read as gone whatever order the ops arrived in",
                replication().readContextRules(childId).isEmpty(),
            )
        }
    }

    // ---------- malformed or partial records ----------

    @Test
    fun `a rule that has not fully arrived is excluded, not fatal to the read`() {
        val complete = listOf(
            op("R1", "name", "No games at dinner", lamport = 1, origin = "AAAA"),
            op("R1", "condition", """{"kind":"always"}""", lamport = 1, origin = "AAAA"),
            op("R1", "effect", """{"kind":"block"}""", lamport = 1, origin = "AAAA"),
            op("R1", "target", """{"kind":"everything"}""", lamport = 1, origin = "AAAA"),
        )
        // A second rule whose name has replicated but nothing else has yet.
        val partial = listOf(op("R2", "name", "Half-arrived", lamport = 1, origin = "AAAA"))

        store().receive(complete + partial)

        val rules = replication().readContextRules(childId)
        assertEquals(listOf("No games at dinner"), rules.map { it.name })
    }

    @Test
    fun `an unrecognised effect kind excludes the rule rather than failing the whole read`() {
        val ops = listOf(
            op("R1", "name", "No games at dinner", lamport = 1, origin = "AAAA"),
            op("R1", "condition", """{"kind":"always"}""", lamport = 1, origin = "AAAA"),
            // From a newer build with an effect kind this one has never heard of.
            op("R1", "effect", """{"kind":"fromTheFuture"}""", lamport = 1, origin = "AAAA"),
            op("R1", "target", """{"kind":"everything"}""", lamport = 1, origin = "AAAA"),
            op("R2", "name", "Still readable", lamport = 1, origin = "AAAA"),
            op("R2", "condition", """{"kind":"always"}""", lamport = 1, origin = "AAAA"),
            op("R2", "effect", """{"kind":"block"}""", lamport = 1, origin = "AAAA"),
            op("R2", "target", """{"kind":"everything"}""", lamport = 1, origin = "AAAA"),
        )

        store().receive(ops)

        val rules = replication().readContextRules(childId)
        assertEquals(listOf("Still readable"), rules.map { it.name })
    }

    @Test
    fun `a rule whose condition has not replicated yet falls back to Always rather than being excluded`() {
        val ops = listOf(
            op("R1", "name", "No games at dinner", lamport = 1, origin = "AAAA"),
            op("R1", "effect", """{"kind":"block"}""", lamport = 1, origin = "AAAA"),
            op("R1", "target", """{"kind":"everything"}""", lamport = 1, origin = "AAAA"),
        )

        store().receive(ops)

        val rule = replication().readContextRules(childId).singleOrNull()
        assertEquals(Condition.Always, rule?.condition)
    }

    // ---------- round trip ----------

    @Test
    fun `a rule written through writeContextRules reads back the same, and a removed one reads as gone`() {
        val rule = ContextRule(
            id = "R1",
            name = "No games at dinner",
            enabled = true,
            condition = Condition.AllOf(listOf(Condition.OnWifi("HomeNet"), Condition.BatteryBelow(20))),
            effect = RuleEffect.Block,
            target = RuleTarget.Categories(setOf(CategoryId("games"))),
        )

        replication().writeContextRules(listOf(rule), childId)
        assertEquals(listOf(rule), replication().readContextRules(childId))

        replication().writeContextRules(emptyList(), childId)
        assertTrue(replication().readContextRules(childId).isEmpty())
    }

    // ---------- the bridge to ContextRuleStore ----------

    @Test
    fun `pushContextRulesFromLocal replicates whatever ContextRuleStore holds`() {
        val rule = ContextRule(
            id = "R1",
            name = "No games at dinner",
            enabled = true,
            condition = Condition.Always,
            effect = RuleEffect.Block,
            target = RuleTarget.Everything,
        )
        ContextRuleStore(context).rules = listOf(rule)

        replication().pushContextRulesFromLocal(childId)

        assertEquals(listOf(rule), replication().readContextRules(childId))
    }

    @Test
    fun `pullContextRulesIntoLocal writes the replica's rules onto ContextRuleStore`() {
        val rule = ContextRule(
            id = "R1",
            name = "No games at dinner",
            enabled = true,
            condition = Condition.Always,
            effect = RuleEffect.Allow,
            target = RuleTarget.Apps(setOf(PackageName("com.example.app"))),
        )
        replication().writeContextRules(listOf(rule), childId)

        replication().pullContextRulesIntoLocal(childId)

        assertEquals(listOf(rule), ContextRuleStore(context).rules)
    }

    private fun seededRandom() = kotlin.random.Random(42)
}
