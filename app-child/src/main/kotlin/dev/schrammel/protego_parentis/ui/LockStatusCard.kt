package dev.schrammel.protego_parentis.ui

import android.app.Activity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.enforcement.EnforcementSnapshot
import dev.schrammel.protego_parentis.format.formatDuration
import dev.schrammel.protego_parentis.model.Reason
import java.time.Duration
import java.time.Instant
import kotlin.time.Duration.Companion.minutes

/**
 * The dashboard's first answer: *why is my phone locked, and when does it
 * open again?*
 *
 * One card above the summary, standing over everything else, because a phone
 * that will not open an app is the fact a reader needs first — before "how much
 * time is left", before the navigation, before anything that assumes the phone
 * is usable. The budget summary already answers the second question well; this
 * card answers the first, which until now sat in small text in the blocked
 * list below the fold.
 *
 * Priority order of what it says (first match wins) — the same order the
 * enforcement engine itself resolves, so the card can never disagree with the
 * behaviour the phone is showing:
 *
 * 1. **Emergency active** — a parent's answer beats every rule, and the
 *    countdown the card would otherwise show would be a lie about who decides.
 * 2. **Device budget exhausted** — "time's up for today", with the day-boundary
 *    reset (`Enforcement.nextChangeAt`) as the countdown.
 * 3. **A contextual rule is applying** — the rule's name + when it lifts
 *    (`Reason.ContextRuleActive.liftsAt`); when several rules apply at once,
 *    the one lifting soonest is the headline, because that is the answer the
 *    reader is standing inside — and a rule whose condition cannot say when it
 *    ends (Wi-Fi, place) keeps the countdown out rather than showing a wrong
 *    one, losing the headline only to a rule that can actually name its end.
 * 4. **Bedtime window active** — same shape as any other rule; the window's
 *    own end is its countdown.
 * 5. **Nothing restricted** — the card does not render at all. A status card
 *    standing over "everything is fine" is noise; the budget summary already
 *    carries the good news.
 *
 * The countdown re-renders with the dashboard on `onResume` — a ticking timer
 * would need a loop the screen's other content does not otherwise run, and a
 * minute-stale countdown that corrects on the next open beats a foreground
 * service for a label.
 */
internal fun Activity.lockStatusCard(snapshot: EnforcementSnapshot): View? {
    val enforcement = snapshot.enforcement

    val headline: String
    val subline: String
    when {
        enforcement.emergency -> {
            headline = getString(R.string.lock_status_emergency)
            subline = getString(R.string.lock_status_emergency_body)
        }

        enforcement.deviceBudget.isExhausted -> {
            headline = getString(R.string.lock_status_budget)
            subline = enforcement.nextChangeAt?.let { countdown(it) }
                ?: getString(R.string.lock_status_rule_until_parent)
        }

        else -> {
            val rule = enforcement.suspend.values
                .filterIsInstance<Reason.ContextRuleActive>()
                .filter { it.liftsAt != null }
                .sortedBy { it.liftsAt }
                .firstOrNull()
                ?: return null
            headline = getString(R.string.lock_status_rule, rule.ruleName)
            subline = rule.liftsAt?.let { countdown(it) }
                ?: getString(R.string.lock_status_rule_until_parent)
        }
    }

    return card {
        addView(
            TextView(context).apply {
                text = headline
                styleAs(R.style.TextAppearance_Dementor_Display)
                setTextColor(ContextCompat.getColor(context, R.color.state_critical))
            },
        )
        addView(
            TextView(context).apply {
                text = subline
                styleAs(R.style.TextAppearance_Dementor_Body)
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    topMargin = dimen(R.dimen.space_xxs)
                }
            },
        )
    }
}

/**
 * "Opens again in 7h 40m — at 06:00". Both halves matter: the duration is the
 * plan, the clock time is the confirmation a child checks against the wall
 * clock, and a countdown alone makes the reader do the arithmetic to learn
 * whether it ends before or after dinner.
 */
internal fun Activity.countdown(at: Instant): String {
    val minutes = Duration.between(Instant.now(), at).toMinutes()
    val duration = if (minutes >= 0) {
        // "Right now" is a future moment of zero minutes, so it must stay in
        // the duration form ("opens in 0m") — `formatDuration` would call it
        // "under a minute", which reads as if the restriction had already
        // lifted rather than about to.
        getString(
            R.string.lock_status_opens_in,
            if (minutes == 0L) getString(dev.schrammel.protego_parentis.format.R.string.format_minutes, 0)
            else formatDuration(minutes.minutes),
        )
    } else {
        getString(R.string.lock_status_opens_at, formatTime(at))
    }
    return getString(R.string.lock_status_countdown, duration, formatTime(at))
}
