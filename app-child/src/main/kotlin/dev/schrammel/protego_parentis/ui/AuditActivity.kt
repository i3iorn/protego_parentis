package dev.schrammel.protego_parentis.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.audit.Audit
import dev.schrammel.protego_parentis.audit.AuditLogFormat
import dev.schrammel.protego_parentis.model.AuditEntry
import dev.schrammel.protego_parentis.model.ChainCheck
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The shared history (TR-3).
 *
 * Not PIN-gated, and that is the whole point. Every rule change and every answer,
 * with who made it and when, readable by the child on their own device without
 * asking anyone. A parental control app whose history only the parent can read is
 * surveillance with a nicer icon.
 *
 * Grouped by day, newest first, because the question people actually bring to a
 * log is "what changed recently", not "what has ever happened".
 */
class AuditActivity : DementorActivity() {

    private val dateFormat: DateTimeFormatter =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern(TIME_PATTERN)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val column = contentColumn()
        column.addView(screenTitle(getString(R.string.audit_title)))
        column.addView(bodyText(getString(R.string.audit_intro)))
        showColumn(column)

        // The chain check walks the whole log (up to AuditLogFormat.MAX_ENTRIES)
        // to verify every hash, and reading+grouping the entries is real disk and
        // CPU work — both belong off the main thread, the same as every other
        // screen that loads a store.
        Background.load(this, {
            val entries = Audit.entries(this)
            // This phone's own chain first: if that is already broken, saying
            // so is more useful than a family-wide check that would fail for
            // the same underlying reason.
            val check = Audit.check(this).takeIf { !it.isIntact } ?: Audit.checkFamily(this)
            entries to check
        }) { (entries, check) ->
            renderEntries(column, entries, check)
        }
    }

    private fun renderEntries(column: LinearLayout, entries: List<AuditEntry>, check: ChainCheck) {
        column.addView(integrityCard(check))

        if (entries.isEmpty()) {
            column.addView(bodyText(getString(R.string.audit_empty)))
            return
        }

        val zone = ZoneId.systemDefault()
        entries
            .groupBy { it.at.atZone(zone).toLocalDate() }
            .forEach { (day, ofDay) ->
                column.addView(sectionHeader(dayLabel(day)))
                column.addView(group(ofDay.map(::entryRow)))
            }

        column.addView(
            captionText(
                resources.getQuantityString(
                    R.plurals.audit_retention,
                    AuditLogFormat.MAX_ENTRIES,
                    AuditLogFormat.MAX_ENTRIES,
                ),
            ),
        )
    }

    /**
     * Whether the stored history still adds up against itself.
     *
     * Shown rather than kept internal, because the point of chaining the entries
     * is that both sides can see the answer. A record whose integrity is only
     * known to the app is one nobody has a reason to believe.
     *
     * The wording is careful about what this proves. The chain is verified
     * against an anchor held in the same `SharedPreferences` as the entries, so
     * it establishes self-consistency and nothing more — it catches damage and
     * accidental loss, not somebody on this phone who rewrites the entries and
     * recomputes the anchor to match. Saying "nothing has been changed" would
     * lend it authority it has not got.
     */
    private fun integrityCard(check: ChainCheck): LinearLayout = card {
        addView(
            TextView(this@AuditActivity).apply {
                setText(
                    if (check.isIntact) R.string.audit_intact else R.string.audit_broken,
                )
                styleAs(R.style.TextAppearance_Dementor_Subtitle)
            },
        )
        addView(
            captionText(
                if (check is ChainCheck.Broken) {
                    getString(R.string.audit_broken_hint)
                } else {
                    getString(R.string.audit_intact_hint)
                },
            ),
        )
    }

    private fun dayLabel(day: LocalDate): String = when (day) {
        LocalDate.now() -> getString(R.string.audit_today)
        LocalDate.now().minusDays(1) -> getString(R.string.audit_yesterday)
        else -> dateFormat.format(day)
    }

    private fun entryRow(entry: AuditEntry): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        setPadding(0, dimen(R.dimen.space_sm), 0, dimen(R.dimen.space_sm))

        addView(
            TextView(context).apply {
                text = AuditText.describe(context, entry)
                styleAs(R.style.TextAppearance_Dementor_ListItem)
            },
        )
        addView(
            TextView(context).apply {
                text = getString(
                    R.string.audit_by_at,
                    AuditText.actor(context, entry.actor),
                    timeFormat.format(entry.at.atZone(ZoneId.systemDefault())),
                )
                styleAs(R.style.TextAppearance_Dementor_Caption)
            },
        )
    }

    companion object {
        private const val TIME_PATTERN = "HH:mm"

        fun intent(context: Context) = Intent(context, AuditActivity::class.java)
    }
}
