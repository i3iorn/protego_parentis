package dev.schrammel.protego_parentis.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.schrammel.protego_parentis.AppRegistry
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.audit.Audit
import dev.schrammel.protego_parentis.model.AuditAction
import dev.schrammel.protego_parentis.model.AuditActor
import dev.schrammel.protego_parentis.model.CategoryId
import dev.schrammel.protego_parentis.model.GrantTarget
import dev.schrammel.protego_parentis.rules.GrantStore
import dev.schrammel.protego_parentis.rules.LocalPolicyStore
import dev.schrammel.protego_parentis.rules.TargetLabels
import java.time.Instant

/**
 * Grants extra time for today.
 *
 * The spec calls this the highest-frequency parent action in the whole product,
 * so it is deliberately two taps and nothing more. Bonus time expires at the day
 * boundary and never changes the underlying limit.
 */
class BonusTimeActivity : ParentGatedActivity() {

    private lateinit var store: LocalPolicyStore
    private lateinit var grants: GrantStore

    /**
     * Nothing here disables the tapped button — the row can hold several
     * amounts, and disabling only one still leaves the others tappable. A
     * short debounce catches the actual failure mode instead: none of these
     * buttons round-trips through a server that could reject a duplicate, so
     * a double-tap from a slow screen or a fast thumb becomes two real
     * grants, two audit entries, and double the minutes — silently, since
     * [GrantStore.add] has no reason to refuse a second grant that looks
     * exactly like a deliberate one.
     *
     * A null means "no grant yet", not "granted at boot": both 0 and
     * [Long.MIN_VALUE] as the start value swallow the very first tap, because
     * `elapsedRealtime` starts near 0 (and `0 - Long.MIN_VALUE` even wraps
     * around) — so the first tap must bypass the comparison entirely.
     */
    private var lastGrantAt: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AppRegistry.childStores.policy
        grants = AppRegistry.childStores.grants
        showColumn(buildLayout())
    }

    private fun buildLayout(): LinearLayout {
        val content = contentColumn()

        content.addView(screenTitle(getString(R.string.bonus_title)))
        content.addView(
            TextView(this).apply {
                setText(R.string.bonus_hint)
                styleAs(R.style.TextAppearance_Dementor_Caption)
                setPadding(0, dimen(R.dimen.space_xxs), 0, dimen(R.dimen.space_sm))
            },
        )

        content.addView(heading(getString(R.string.bonus_device_header), 20f))
        content.addView(amountRow { minutes -> grant(GrantTarget.Device, minutes) })

        val categories = store.categories
        content.addView(heading(getString(R.string.bonus_category_header), 20f))
        if (categories.isEmpty()) {
            content.addView(captionText(getString(R.string.bonus_no_categories)))
        } else {
            categories.sortedBy { it.name.lowercase() }.forEach { category ->
                content.addView(
                    TextView(this).apply {
                        text = category.name
                        styleAs(R.style.TextAppearance_Dementor_ListItem)
                        setPadding(0, dimen(R.dimen.space_md), 0, dimen(R.dimen.space_hairline))
                    },
                )
                content.addView(
                    amountRow { minutes ->
                        grant(GrantTarget.Category(CategoryId(category.id)), minutes)
                    },
                )
            }
        }

        content.addView(
            Button(this).apply {
                setText(R.string.bonus_clear)
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                    .apply { topMargin = dimen(R.dimen.space_lg) }
                setOnClickListener {
                    grants.clearAll()
                    AppRegistry.ruleMutationSink.rulesChanged()
                    toast(getString(R.string.bonus_cleared))
                }
            },
        )

        return content
    }

    private fun amountRow(onPick: (Int) -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            bonusOptions().forEach { minutes ->
                addView(
                    Button(context).apply {
                        text = getString(R.string.bonus_add_minutes, minutes)
                        layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                            .apply { marginEnd = dimen(R.dimen.space_xs) }
                        setOnClickListener { onPick(minutes) }
                    },
                )
            }
        }

    private fun grant(target: GrantTarget, minutes: Int) {
        val now = SystemClock.elapsedRealtime()
        val last = lastGrantAt
        if (last != null && now - last < GRANT_DEBOUNCE_MS) return
        lastGrantAt = now

        grants.add(
            target = target,
            minutes = minutes,
            now = Instant.now(),
            zone = store.timeZone,
            dayBoundaryHour = store.dayBoundaryHour,
        )
        Audit.record(
            context = this,
            actor = AuditActor.PARENT,
            action = AuditAction.BONUS_GRANTED,
            subject = TargetLabels.describe(this, target),
            after = Audit.minutes(minutes),
        )
        // Apply immediately: the point of bonus time is that it unblocks now.
        AppRegistry.ruleMutationSink.rulesChanged()
        toast(resources.getQuantityString(R.plurals.bonus_granted, minutes, minutes))
    }

    /** See `res/values/config.xml`. */
    private fun bonusOptions(): List<Int> =
        resources.getIntArray(R.array.bonus_minute_options).toList()

    private fun heading(text: String, size: Float) = TextView(this).apply {
        this.text = text
        textSize = size
        setPadding(0, dimen(R.dimen.space_xl), 0, dimen(R.dimen.space_xs))
    }

    companion object {
        fun intent(context: Context) = Intent(context, BonusTimeActivity::class.java)

        /** Comfortably longer than any accidental double-tap, short enough no real second tap ever waits on it. */
        private const val GRANT_DEBOUNCE_MS = 750L
    }
}
