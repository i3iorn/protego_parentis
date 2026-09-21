package dev.schrammel.protego_parentis.sync

import android.content.Context
import dev.schrammel.protego_parentis.model.Condition
import dev.schrammel.protego_parentis.model.ContextRule
import dev.schrammel.protego_parentis.rules.ContextRuleCodec
import dev.schrammel.protego_parentis.rules.ContextRuleStore
import dev.schrammel.protego_parentis.sync.core.DeviceId
import dev.schrammel.protego_parentis.sync.core.ReplicationRules
import dev.schrammel.protego_parentis.sync.core.StateOp
import java.time.Instant

/**
 * Contextual rules — the condition/effect/target shape — as replicated
 * state, [PolicyReplication]'s delegate for [ContextRuleStore.rules].
 *
 * Not yet reachable from a screen: this milestone is wiring only, per the
 * same "one PR per record type" split #40/#41 used for
 * [ScheduleReplication] — a condition can nest `AllOf`/`AnyOf`/`Not`
 * arbitrarily, and a parent-phone builder for that is its own, more careful
 * piece of work. This is the wire format and the merge behaviour, ready for
 * that to call into.
 */
internal class ContextRuleReplication(context: Context) {

    private val appContext = context.applicationContext
    private val store = ContextRuleStore(appContext)
    private val records = PolicyRecordReplication(appContext)
    private val state get() = records.state

    /**
     * Records [rules] as [childId]'s replicated contextual rules — the
     * condition/effect/target shape's equivalent of [ScheduleReplication.writeSchedules].
     */
    fun writeContextRules(
        rules: List<ContextRule>,
        childId: DeviceId?,
        now: Instant = Instant.now(),
    ): List<StateOp> = records.write(childId) { id -> flattenContextRules(rules, id, now) }

    /**
     * [childId]'s replicated contextual rules, excluding any this replica has
     * been told is gone — see [FIELD_RULE_DELETED] on [flattenContextRules],
     * the same deleted-marker approach [ScheduleReplication.readSchedules]
     * uses and for the same reason: a delete and a concurrent edit to the
     * same rule write different paths, so which one a replica saw last never
     * decides whether the rule is still there.
     *
     * A rule whose `condition`, `effect` or `target` has not replicated yet,
     * or does not parse — an unrecognised kind from a newer build, say — is
     * excluded the same way [ContextRuleStore.decodeRules] already drops one
     * it cannot make sense of; nothing here is new risk beyond what local
     * storage already tolerates.
     */
    fun readContextRules(childId: DeviceId?): List<ContextRule> {
        val id = childId ?: return emptyList()
        val root = "${ReplicationRules.POLICY}/${id.value}/$PATH_CONTEXT_RULES"
        return state.idsUnder(root).mapNotNull { ruleId ->
            val ruleRoot = "$root/$ruleId"
            val fields = state.children(ruleRoot)
            if (FIELD_RULE_DELETED in fields) return@mapNotNull null

            ContextRule(
                id = ruleId,
                name = fields[FIELD_RULE_NAME] ?: return@mapNotNull null,
                enabled = fields[FIELD_RULE_ENABLED]?.toBooleanStrictOrNull() ?: true,
                condition = ContextRuleCodec.decodeConditionFromString(fields[FIELD_RULE_CONDITION])
                    ?: Condition.Always,
                effect = ContextRuleCodec.decodeEffectFromString(fields[FIELD_RULE_EFFECT])
                    ?: return@mapNotNull null,
                target = ContextRuleCodec.decodeTargetFromString(fields[FIELD_RULE_TARGET])
                    ?: return@mapNotNull null,
            )
        }
    }

    /**
     * Records this phone's own [ContextRuleStore] rules as replicated writes
     * — [ScheduleReplication.pushSchedulesFromLocal]'s equivalent. Places are
     * pushed separately, by [PlaceRefReplication.pushPlaceRefsFromLocal] —
     * not folded in here because the store happens to hold both, and because
     * what a place replicates as ([dev.schrammel.protego_parentis.model.PlaceRef])
     * is deliberately not the same shape as what it is
     * ([dev.schrammel.protego_parentis.model.Place]).
     */
    fun pushContextRulesFromLocal(childId: DeviceId?): List<StateOp> =
        writeContextRules(store.rules, childId)

    /** [ScheduleReplication.pullSchedulesIntoLocal]'s equivalent, for contextual rules. */
    fun pullContextRulesIntoLocal(childId: DeviceId?) {
        store.rules = readContextRules(childId)
    }

    /**
     * Every contextual rule as a path, plus a [FIELD_RULE_DELETED] marker —
     * see [ScheduleReplication.flattenSchedules] for why a marker rather than
     * nulling every field.
     */
    private fun flattenContextRules(
        rules: List<ContextRule>,
        childId: DeviceId,
        now: Instant,
    ): Map<String, String?> = buildMap {
        val root = "${ReplicationRules.POLICY}/${childId.value}/$PATH_CONTEXT_RULES"
        with(records) {
            rules.forEach { rule ->
                val ruleRoot = "$root/${rule.id}"
                put("$ruleRoot/$FIELD_RULE_NAME", rule.name)
                put("$ruleRoot/$FIELD_RULE_ENABLED", rule.enabled.toString())
                put("$ruleRoot/$FIELD_RULE_CONDITION", ContextRuleCodec.encodeConditionToString(rule.condition))
                put("$ruleRoot/$FIELD_RULE_EFFECT", ContextRuleCodec.encodeEffectToString(rule.effect))
                put("$ruleRoot/$FIELD_RULE_TARGET", ContextRuleCodec.encodeTargetToString(rule.target))
            }

            tombstoneGone(root, rules.map { it.id }.toSet(), FIELD_RULE_DELETED, now)
        }
    }

    companion object {
        const val PATH_CONTEXT_RULES = "contextRules"
        const val FIELD_RULE_NAME = "name"
        const val FIELD_RULE_ENABLED = "enabled"
        const val FIELD_RULE_CONDITION = "condition"
        const val FIELD_RULE_EFFECT = "effect"
        const val FIELD_RULE_TARGET = "target"

        /** As [ScheduleReplication.FIELD_SCHEDULE_DELETED], for a contextual rule. */
        const val FIELD_RULE_DELETED = "deleted"
    }
}
