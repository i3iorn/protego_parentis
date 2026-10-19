package dev.schrammel.protego_parentis.policy

import dev.schrammel.protego_parentis.model.Condition
import dev.schrammel.protego_parentis.model.ContextRule
import dev.schrammel.protego_parentis.model.RuleAction
import dev.schrammel.protego_parentis.model.RuleEffect
import dev.schrammel.protego_parentis.model.RuleTarget

/**
 * The guided "build your own" authoring flow, as a pure state machine — the
 * same split [ConditionDraft] makes for the condition tree.
 *
 * Rules-UX phase D: the common case is three questions (what should happen,
 * when, to what) and a review, not a form exposing the whole engine. This type
 * holds only those answers and which question is being asked; every picker,
 * dialog and app list the screen needs stays in the Android layer, which turns
 * a chosen value into the matching model type and hands it here. Kept in this
 * module so the sequence, the skipping and the rule it finally builds can be
 * tested without a device.
 *
 * [effect]/[condition]/[target] are null until each is answered. [toRule] is
 * the only way out: it returns null until all three are set and a name is
 * given, so a half-built wizard can never be saved by accident.
 */
data class RuleWizard(
    val step: Step = Step.Start,
    val effect: RuleEffect? = null,
    val condition: Condition? = null,
    val target: RuleTarget? = null,
) {
    /**
     * Where the parent is. [Start] is the template gallery — not one of the
     * three numbered questions — and [Review] is the confirmation a wizard is
     * supposed to end on rather than saving straight from the last picker.
     */
    enum class Step { Start, Effect, Condition, Target, Review }

    fun chooseEffect(effect: RuleEffect): RuleWizard = copy(effect = effect)

    fun chooseCondition(condition: Condition): RuleWizard = copy(condition = condition)

    fun chooseTarget(target: RuleTarget): RuleWizard = copy(target = target)

    /** Leave the gallery and start the three-question flow from the top. */
    fun buildYourOwn(): RuleWizard = copy(step = Step.Effect)

    /**
     * Pre-fill from [template] and skip whichever questions it already
     * answers: one that names its own target lands on review, one that leaves
     * the target open still asks "to what?" first.
     */
    fun applyTemplate(template: RuleTemplateSpec): RuleWizard = copy(
        effect = template.effect,
        condition = template.condition,
        target = template.target,
        step = if (template.target == null) Step.Target else Step.Review,
    )

    /** Whether the current question has an answer, so [next] would do something. */
    val canAdvance: Boolean
        get() = when (step) {
            Step.Effect -> effect != null
            Step.Condition -> condition != null
            Step.Target -> target != null
            Step.Start, Step.Review -> false
        }

    val canGoBack: Boolean get() = step != Step.Start

    fun next(): RuleWizard = if (canAdvance) copy(step = step.after()) else this

    fun back(): RuleWizard = if (canGoBack) copy(step = step.before()) else this

    /** Every question answered, so [toRule] can produce a rule. */
    val isComplete: Boolean get() = effect != null && condition != null && target != null

    /** The rule these answers describe, or null until they are complete and named. */
    fun toRule(id: String, name: String, enabled: Boolean): ContextRule? {
        val effect = effect ?: return null
        val condition = condition ?: return null
        val target = target ?: return null
        if (name.isBlank()) return null
        return ContextRule(
            id = id,
            name = name,
            enabled = enabled,
            condition = condition,
            actions = listOf(RuleAction(effect, target)),
        )
    }

    private fun Step.after(): Step = when (this) {
        Step.Start -> Step.Effect
        Step.Effect -> Step.Condition
        Step.Condition -> Step.Target
        Step.Target, Step.Review -> Step.Review
    }

    private fun Step.before(): Step = when (this) {
        Step.Start, Step.Effect -> Step.Start
        Step.Condition -> Step.Effect
        Step.Target -> Step.Condition
        Step.Review -> Step.Target
    }
}
