package dev.schrammel.protego_parentis.policy

import dev.schrammel.protego_parentis.model.Condition
import dev.schrammel.protego_parentis.model.Defaults
import dev.schrammel.protego_parentis.model.RecurringWindow
import dev.schrammel.protego_parentis.model.RuleEffect
import dev.schrammel.protego_parentis.model.RuleTarget
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * A rule shape a family is likely to want, before it has a name or an id: what
 * it does, when, and — unless the template deliberately leaves it for the
 * parent to choose — to what.
 *
 * [target] is nullable so a template can answer the two easy questions and
 * still send the parent to "to what?", rather than guessing a target it has no
 * app data to name.
 */
data class RuleTemplateSpec(
    val effect: RuleEffect,
    val condition: Condition,
    val target: RuleTarget? = null,
)

/**
 * The windows almost every family ends up describing, as the one gallery the
 * rules list and the authoring wizard share (rules-UX phase D).
 *
 * Kept here rather than in either screen — the same split [ConditionDraft]
 * makes — so the two cannot offer different shapes for the "same" starter, and
 * so the whole gallery can be tested without a device. The times are chosen to
 * be plainly editable rather than plausibly correct: nobody's day is exactly
 * this, and the point is that the shape is there and only the numbers need
 * changing.
 */
object RuleTemplates {

    enum class Kind { SCHOOL, HOMEWORK, DINNER, BEDTIME }

    /** Monday to Friday. */
    private val WEEKDAYS = setOf(
        DayOfWeek.MONDAY,
        DayOfWeek.TUESDAY,
        DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY,
        DayOfWeek.FRIDAY,
    )

    private val EVERY_DAY = DayOfWeek.entries.toSet()

    fun all(): List<Kind> = Kind.entries

    /** The recurring window a starter's hours describe. */
    fun window(kind: Kind): RecurringWindow = when (kind) {
        Kind.SCHOOL -> RecurringWindow(WEEKDAYS, LocalTime.of(8, 30), LocalTime.of(15, 0))
        Kind.HOMEWORK -> RecurringWindow(WEEKDAYS, LocalTime.of(16, 0), LocalTime.of(17, 30))
        Kind.DINNER -> RecurringWindow(EVERY_DAY, LocalTime.of(18, 0), LocalTime.of(19, 0))
        Kind.BEDTIME -> RecurringWindow(EVERY_DAY, Defaults.BEDTIME_START, Defaults.BEDTIME_END)
    }

    /**
     * The rule a starter becomes: block everything with nothing named as an
     * exception, during the window — the same shape the dedicated schedules
     * screen always gave a fresh starter before #106 folded it in here.
     *
     * The window is a complete answer, so [RuleTemplateSpec.target] is set
     * rather than left null: a starter can be saved without a second question.
     */
    fun spec(kind: Kind): RuleTemplateSpec = RuleTemplateSpec(
        effect = RuleEffect.Block(),
        condition = Condition.DuringWindow(window(kind)),
        target = RuleTarget.EverythingExcept(emptySet()),
    )
}
