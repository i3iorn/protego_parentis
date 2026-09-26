package dev.schrammel.protego_parentis.policy

import dev.schrammel.protego_parentis.model.Condition
import dev.schrammel.protego_parentis.model.ContextRule
import dev.schrammel.protego_parentis.model.RuleEffect
import dev.schrammel.protego_parentis.model.RuleTarget

/**
 * Checks a [ContextRule] against the shapes a parent is likely to have built
 * by accident rather than intended, before it is saved.
 *
 * Kept separate from [ContextRules] (the runtime evaluator, internal to this
 * module): this is an authoring-time question — "does this rule, about to be
 * saved, look like a mistake" — not a decision that feeds enforcement, so it
 * has its own home and its own, public, visibility.
 */
object ContextRuleAuthoring {

    /**
     * The one contradiction the tutorial itself warns about, made hard to miss
     * at the moment it's easy to create: an unconditioned block of literally
     * everything, with nothing anywhere in this ruleset carved back out. Not a
     * general contradiction checker — AllOf/AnyOf combinations and overlapping
     * budgets are still on the parent to notice — just this one well-known
     * shape, which #87's combine/invert controls make easy to build by
     * accident.
     *
     * A plain [RuleEffect.Block] is only a true blackout while no [RuleEffect.Allow]
     * rule carves an exception back out; a [RuleEffect.AlwaysBlock] is not
     * saved by an [RuleEffect.Allow] at all — that is the whole point of the
     * override — so only a [RuleEffect.SafetyExempt] rule counts as its
     * exception. Getting this wrong in either direction would either warn a
     * parent who has already carved out an exception that works, or, worse,
     * silently let an always-block-everything rule through believing an
     * ordinary allow rule will save some app from it when it cannot.
     *
     * [otherRules] is the rest of the ruleset [rule] would be saved alongside —
     * [rule] itself need not be excluded from it, since it is matched out by
     * id below.
     */
    fun looksLikeTotalBlackout(rule: ContextRule, otherRules: List<ContextRule>): Boolean {
        if (!rule.enabled) return false
        if (rule.condition != Condition.Always || rule.exceptWhen != null) return false
        // The rule's own other actions count as carve-outs too: "block
        // everything, allow the phone app" in one rule is not a blackout.
        val carveOuts = rule.actions.map { it.effect } +
            otherRules.filter { it.enabled && it.id != rule.id }.flatMap { other -> other.actions.map { it.effect } }
        return rule.actions.any { action ->
            action.target == RuleTarget.Everything && when (action.effect) {
                // SafetyExempt frees a plain block as well as Allow does.
                is RuleEffect.Block -> carveOuts.none { it is RuleEffect.Allow || it is RuleEffect.SafetyExempt }
                is RuleEffect.AlwaysBlock -> carveOuts.none { it is RuleEffect.SafetyExempt }
                else -> false
            }
        }
    }
}
