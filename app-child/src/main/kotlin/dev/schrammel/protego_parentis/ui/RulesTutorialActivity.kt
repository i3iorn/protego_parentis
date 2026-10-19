package dev.schrammel.protego_parentis.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import dev.schrammel.protego_parentis.R

/**
 * Worked examples of what a [dev.schrammel.protego_parentis.model.ContextRule]
 * can express, read together as one story rather than three separate pickers.
 *
 * Not PIN-gated, unlike the screens it explains: nothing here is
 * configuration, so there is nothing for a parent to be kept from reading
 * before deciding they trust it — the same reasoning [PrivacyActivity]
 * already applies to the app's other read-only explanations.
 *
 * Static rather than an interactive builder on purpose: a guided flow that
 * actually constructed a rule live would duplicate [ContextRuleEditActivity]'s
 * own state machine behind a second, different flow, with the two free to
 * silently drift apart on what a combination means. A worked example that
 * stays in sync with the model doc comments it is drawn from is worth more
 * than a shortcut that can disagree with the screen it is meant to teach.
 */
class RulesTutorialActivity : DementorActivity() {
    override val showsBackButton = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = contentColumn()
        column.addView(screenTitle(getString(R.string.rules_tutorial_title)))
        column.addView(bodyText(getString(R.string.rules_tutorial_intro)))

        EXAMPLES.forEach { example -> column.addView(exampleCard(example)) }

        showColumn(column)
    }

    private fun exampleCard(example: Example): LinearLayout = card {
        addView(
            TextView(this@RulesTutorialActivity).apply {
                setText(example.title)
                styleAs(R.style.TextAppearance_Dementor_Subtitle)
            },
        )
        example.steps.forEachIndexed { index, step ->
            if (example.steps.size > 1) {
                addView(
                    TextView(this@RulesTutorialActivity).apply {
                        text = getString(R.string.rules_tutorial_step_label, index + 1)
                        styleAs(R.style.TextAppearance_Dementor_Caption)
                        setPadding(0, dimen(R.dimen.space_xs), 0, 0)
                    },
                )
            }
            addView(recipeLine(R.string.rules_tutorial_when_label, step.condition))
            addView(recipeLine(R.string.rules_tutorial_do_label, step.effect))
            addView(recipeLine(R.string.rules_tutorial_to_label, step.target))
        }
        addView(
            TextView(this@RulesTutorialActivity).apply {
                setText(example.note)
                styleAs(R.style.TextAppearance_Dementor_Caption)
                setPadding(0, dimen(R.dimen.space_sm), 0, 0)
            },
        )
    }

    private fun recipeLine(@StringRes labelRes: Int, @StringRes valueRes: Int): TextView =
        TextView(this).apply {
            text = getString(R.string.rules_tutorial_line, getString(labelRes), getString(valueRes))
            styleAs(R.style.TextAppearance_Dementor_Body)
        }

    private data class Step(
        @param:StringRes val condition: Int,
        @param:StringRes val effect: Int,
        @param:StringRes val target: Int,
    )

    private data class Example(
        @param:StringRes val title: Int,
        @param:StringRes val note: Int,
        val steps: List<Step>,
    )

    companion object {
        fun intent(context: Context) = Intent(context, RulesTutorialActivity::class.java)

        private val EXAMPLES = listOf(
            // A plain Allow beats every other rule that would otherwise catch
            // the same app — see RuleEffect.Allow's own doc comment.
            Example(
                title = R.string.rules_tutorial_never_block_title,
                note = R.string.rules_tutorial_never_block_note,
                steps = listOf(
                    Step(
                        R.string.rules_tutorial_cond_always,
                        R.string.rules_tutorial_effect_allow,
                        R.string.rules_tutorial_target_one_app,
                    ),
                ),
            ),
            // Two rules that only work as a pair: the category block would
            // catch the excepted app too if the allow rule weren't also there.
            Example(
                title = R.string.rules_tutorial_block_except_title,
                note = R.string.rules_tutorial_block_except_note,
                steps = listOf(
                    Step(
                        R.string.rules_tutorial_cond_always,
                        R.string.rules_tutorial_effect_block,
                        R.string.rules_tutorial_target_category,
                    ),
                    Step(
                        R.string.rules_tutorial_cond_always,
                        R.string.rules_tutorial_effect_allow,
                        R.string.rules_tutorial_target_one_app,
                    ),
                ),
            ),
            // RuleEffect.AlwaysBlock: the mirror image of the pair above — an
            // allow this broad would otherwise free the one app that has to
            // stay off regardless.
            Example(
                title = R.string.rules_tutorial_always_block_title,
                note = R.string.rules_tutorial_always_block_note,
                steps = listOf(
                    Step(
                        R.string.rules_tutorial_cond_always,
                        R.string.rules_tutorial_effect_allow,
                        R.string.rules_tutorial_target_category,
                    ),
                    Step(
                        R.string.rules_tutorial_cond_always,
                        R.string.rules_tutorial_effect_always_block,
                        R.string.rules_tutorial_target_one_app,
                    ),
                ),
            ),
            // RuleTarget.EverythingExcept: a strict schedule that names the
            // short list of what should keep working, rather than what to block.
            Example(
                title = R.string.rules_tutorial_lockdown_title,
                note = R.string.rules_tutorial_lockdown_note,
                steps = listOf(
                    Step(
                        R.string.rules_tutorial_cond_window,
                        R.string.rules_tutorial_effect_block,
                        R.string.rules_tutorial_target_everything_except,
                    ),
                ),
            ),
            // WeeklyBudget.schoolWeek: one rule, two numbers — no separate
            // weekday/weekend rules needed.
            Example(
                title = R.string.rules_tutorial_weekend_budget_title,
                note = R.string.rules_tutorial_weekend_budget_note,
                steps = listOf(
                    Step(
                        R.string.rules_tutorial_cond_always,
                        R.string.rules_tutorial_effect_budget_split,
                        R.string.rules_tutorial_target_category,
                    ),
                ),
            ),
            // Condition.AllOf: every part has to hold at once.
            Example(
                title = R.string.rules_tutorial_allof_title,
                note = R.string.rules_tutorial_allof_note,
                steps = listOf(
                    Step(
                        R.string.rules_tutorial_cond_allof,
                        R.string.rules_tutorial_effect_block,
                        R.string.rules_tutorial_target_category,
                    ),
                ),
            ),
            // Condition.AnyOf: any one part holding is enough.
            Example(
                title = R.string.rules_tutorial_anyof_title,
                note = R.string.rules_tutorial_anyof_note,
                steps = listOf(
                    Step(
                        R.string.rules_tutorial_cond_anyof,
                        R.string.rules_tutorial_effect_allow,
                        R.string.rules_tutorial_target_everything,
                    ),
                ),
            ),
            // RuleEffect.SafetyExempt: the strongest tier, stronger even than
            // AlwaysBlock above — it also reaches network/DNS blocking, not
            // just other context rules.
            Example(
                title = R.string.rules_tutorial_safety_title,
                note = R.string.rules_tutorial_safety_note,
                steps = listOf(
                    Step(
                        R.string.rules_tutorial_cond_always,
                        R.string.rules_tutorial_effect_safety_exempt,
                        R.string.rules_tutorial_target_one_app,
                    ),
                ),
            ),
        )
    }
}
