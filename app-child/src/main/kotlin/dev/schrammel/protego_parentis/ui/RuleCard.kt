package dev.schrammel.protego_parentis.ui

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.ContextCompat
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.model.ContextRule

/**
 * One rule, drawn as a card that scans like a recipe (rules-UX phase C):
 * a title with its on/off chip, then the rule's own phrases as labelled rows —
 * "When", "Then", "Except when" — rather than one sentence to unpick.
 *
 * One component everywhere a rule is shown: the rules list (opens the editor,
 * with a switch so on/off never needs entering) and the editor's live preview
 * (inert, telling a parent what they are building before they save it). The
 * phrases come from [RuleDescriber.parts] and the read-aloud line from
 * [RuleDescriber.rule], so the card and every other place a rule is described
 * cannot drift apart.
 *
 * The screen reader reads the whole thing once — name, status, then the
 * sentence — while the eye's pieces (glyph, chip, labelled rows) sit out of the
 * accessibility tree, the same split [ContextRulesActivity]'s rows established
 * on this screen already.
 */
internal fun Activity.ruleCard(
    rule: ContextRule,
    describer: RuleDescriber,
    /** What happens when the card's body is tapped; null for an inert preview. */
    onOpen: (() -> Unit)?,
    /** When present, a trailing switch flips the rule without opening the editor. */
    onToggle: ((Boolean) -> Unit)?,
    /** Names the phones that cannot read this rule, when that is the case (rules-UX §G). */
    riskNote: String? = null,
): View {
    val parts = describer.parts(rule)
    val status = getString(if (rule.enabled) R.string.rules_status_on else R.string.rules_status_paused)
    val switchDescription = getString(
        if (rule.enabled) R.string.context_card_turn_off else R.string.context_card_turn_on,
        rule.name,
    )
    val touchTargetPx = dimen(R.dimen.touch_target)
    val gapXs = dimen(R.dimen.space_xs)
    val gapSm = dimen(R.dimen.space_sm)

    return card {
        contentDescription = "${rule.name}. $status. ${describer.rule(rule)}"
        if (onOpen != null) {
            withRipple()
            setOnClickListener { onOpen() }
        }

        // ---- title: glyph, name, status chip, and the switch ----
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    TextView(this@ruleCard).apply {
                        text = familyGlyph(parts.kind)
                        styleAs(R.style.TextAppearance_Dementor_Caption)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    },
                )
                addView(
                    TextView(this@ruleCard).apply {
                        text = rule.name
                        styleAs(R.style.TextAppearance_Dementor_ListItem)
                        layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                            marginStart = gapXs
                            marginEnd = gapSm
                        }
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    },
                )
                addView(
                    statusChip(rule.enabled).apply {
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    },
                )
                if (onToggle != null) {
                    addView(
                        Switch(this@ruleCard).apply {
                            isChecked = rule.enabled
                            // The bare switch reads as nothing; name the rule it acts on.
                            contentDescription = switchDescription
                            minimumHeight = touchTargetPx
                            setOnCheckedChangeListener { _, checked -> onToggle(checked) }
                            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
                                marginStart = gapXs
                            }
                        },
                    )
                }
            },
        )

        // ---- the rule itself, as labelled rows ----
        section(getString(R.string.context_when_header), parts.whenLines)
        val hidden = parts.whenLines.size - MAX_WHEN_LINES
        if (hidden > 0) {
            addView(
                phraseLine(
                    resources.getQuantityString(R.plurals.rules_card_more, hidden, hidden),
                ),
            )
        }
        if (parts.thenLines.isNotEmpty()) {
            section(getString(R.string.context_card_then), parts.thenLines)
        }
        parts.except?.let { section(getString(R.string.context_except_header), listOf(it)) }
        riskNote?.let { addView(riskLine(it)) }
    }
}

/**
 * When lines past this count sit behind a "+N more conditions" line. Two
 * visible conditions is the most a scan needs before the editor takes over —
 * the full set is one tap away, and the read-aloud sentence carries all of
 * them either way.
 */
private const val MAX_WHEN_LINES = 2

/** The eye's sort: one glyph per condition family, as text rather than assets. */
private fun familyGlyph(kind: RuleKind): String = when (kind) {
    RuleKind.ALWAYS -> "\u221E" // ∞
    RuleKind.WINDOW -> "\u23F0" // ⏰
    RuleKind.ALLOWANCE -> "\u23F3" // ⏳
    RuleKind.WIFI -> "\uD83D\uDCF6" // 📶
    RuleKind.PLACE -> "\uD83D\uDCCD" // 📍
    RuleKind.DEVICE -> "\uD83D\uDD17" // 🔗
    RuleKind.BATTERY -> "\uD83D\uDD0B" // 🔋
    RuleKind.GLUCOSE -> "\uD83E\uDE78" // 🩸
}

/**
 * A labelled block: its name at the left, the lines flowing beside it, so
 * several lines of one answer align at the same edge rather than as fragments.
 */
private fun LinearLayout.section(label: String, lines: List<String>) {
    if (lines.isEmpty()) return
    addView(
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                TextView(context).apply {
                    text = label
                    styleAs(R.style.TextAppearance_Dementor_Caption)
                    setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                },
            )
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                        marginStart = context.dimen(R.dimen.space_sm)
                    }
                    lines.forEach { line -> addView(context.phraseLine(line)) }
                },
            )
        },
    )
}

/** One phrase of a section, tall enough for a finger, tight enough to scan. */
private fun Context.phraseLine(text: String): TextView = TextView(this).apply {
    this.text = text
    styleAs(R.style.TextAppearance_Dementor_Body)
    val vertical = dimen(R.dimen.space_hairline)
    setPadding(0, vertical, 0, vertical)
}

/** The one warning a card can carry on its own: older phones will drop this rule. */
private fun Context.riskLine(text: String): TextView = TextView(this).apply {
    this.text = text
    styleAs(R.style.TextAppearance_Dementor_Caption)
    setTextColor(ContextCompat.getColor(context, R.color.state_critical))
    layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
        topMargin = dimen(R.dimen.space_xxs)
    }
}

/** A coloured word — never colour alone — for the rule's on/off state. */
private fun Context.statusChip(enabled: Boolean): TextView = TextView(this).apply {
    setText(if (enabled) R.string.rules_status_on else R.string.rules_status_paused)
    styleAs(R.style.TextAppearance_Dementor_Caption)
    setBackgroundResource(R.drawable.chip)
    backgroundTintList = android.content.res.ColorStateList.valueOf(
        ContextCompat.getColor(context, if (enabled) R.color.state_ok else R.color.card_stroke),
    )
    setTextColor(ContextCompat.getColor(context, if (enabled) R.color.on_brand else R.color.text_primary))
    val horizontal = dimen(R.dimen.space_sm)
    setPadding(horizontal, dimen(R.dimen.space_hairline), horizontal, dimen(R.dimen.space_hairline))
}
