package dev.schrammel.dementor_watch.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import dev.schrammel.dementor_watch.R
import dev.schrammel.dementor_watch.audit.Audit
import dev.schrammel.dementor_watch.model.AuditAction
import dev.schrammel.dementor_watch.model.AuditActor
import dev.schrammel.dementor_watch.model.BudgetLevel
import dev.schrammel.dementor_watch.sync.DailySummary
import dev.schrammel.dementor_watch.sync.DailySummaryStore
import dev.schrammel.dementor_watch.sync.InboxStore
import dev.schrammel.dementor_watch.sync.ParentPollWorker
import dev.schrammel.dementor_watch.sync.SyncCoordinator
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The dashboard on a parent's own phone.
 *
 * Deliberately thin. This phone holds no usage data, no rules and no enforcement
 * state — everything it knows arrived encrypted over the family topic. So the
 * screen answers one question first, which is the only one a parent opens the app
 * for: is anybody waiting on me?
 *
 * Not PIN-gated, unlike the rules screens on the child's device. This is the
 * parent's own phone, already behind its own lock screen, and a second PIN in
 * front of "approve 15 minutes" is friction with nothing on the other side of it.
 * Switching the phone back to child mode *is* gated — see [ParentSettingsActivity].
 */
class ParentHomeActivity : ParentRoleActivity() {

    private lateinit var sync: SyncCoordinator
    private lateinit var inbox: InboxStore
    private lateinit var summaries: DailySummaryStore
    private lateinit var column: LinearLayout

    /** Null until this phone has successfully reached the topic in this session. */
    private var lastCheckedAt: Instant? = null

    private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern(TIME_PATTERN)

    override fun onParentPhoneReady(savedInstanceState: Bundle?) {
        sync = SyncCoordinator(this)
        inbox = InboxStore(this)
        summaries = DailySummaryStore(this)
        // Idempotent, and re-asserted here so a phone that was granted the family
        // connection after choosing its role still starts listening.
        ParentPollWorker.schedule(this)

        column = contentColumn()
        showColumn(column)
    }

    override fun onParentPhoneResumed() {
        render()
        // Opening the app is the moment a parent expects to be up to date, so a
        // poll happens here rather than only on a timer.
        pollQuietly()
    }

    private fun render() {
        column.removeAllViews()
        column.addView(screenTitle(getString(R.string.parent_title)))

        val configured = sync.settings().isUsable
        val pending = inbox.pending().size

        column.addView(
            card {
                addView(
                    TextView(this@ParentHomeActivity).apply {
                        text = when {
                            !configured -> getString(R.string.parent_not_connected)
                            pending == 0 -> getString(R.string.parent_nothing_waiting)
                            else -> resources.getQuantityString(
                                R.plurals.parent_waiting,
                                pending,
                                pending,
                            )
                        }
                        styleAs(R.style.TextAppearance_Dementor_Subtitle)
                    },
                )
                addView(
                    TextView(this@ParentHomeActivity).apply {
                        text = statusLine(configured)
                        styleAs(R.style.TextAppearance_Dementor_Caption)
                    },
                )
            },
        )

        column.addView(sectionHeader(getString(R.string.parent_today_header)))
        column.addView(todayCard())

        column.addView(
            group(
                listOf(
                    navigationRow(
                        getString(R.string.parent_row_inbox),
                        getString(R.string.parent_row_inbox_detail),
                    ) { startActivity(ParentInboxActivity.intent(this)) },
                    navigationRow(
                        getString(R.string.parent_row_bonus),
                        getString(R.string.parent_row_bonus_detail),
                    ) { startActivity(ParentBonusActivity.intent(this)) },
                    navigationRow(
                        getString(R.string.parent_row_lock),
                        getString(R.string.parent_row_lock_detail),
                    ) { confirmLock() },
                    navigationRow(
                        getString(R.string.parent_row_locate),
                        getString(R.string.parent_row_locate_detail),
                    ) { locate() },
                    navigationRow(
                        getString(R.string.parent_row_device),
                        getString(R.string.parent_row_device_detail),
                    ) { startActivity(ParentDeviceActivity.intent(this)) },
                    navigationRow(
                        getString(R.string.parent_row_audit),
                        getString(R.string.parent_row_audit_detail),
                    ) { startActivity(AuditActivity.intent(this)) },
                    navigationRow(
                        getString(R.string.rules_row_devices),
                        getString(R.string.rules_row_devices_detail),
                    ) { startActivity(FamilyDevicesActivity.intent(this)) },
                    navigationRow(
                        getString(R.string.parent_row_sync),
                        getString(R.string.parent_row_sync_detail),
                    ) { startActivity(JoinFamilyActivity.intent(this)) },
                    navigationRow(
                        getString(R.string.backend_title),
                        getString(R.string.backend_row_detail),
                    ) { startActivity(SyncBackendActivity.intent(this)) },
                    navigationRow(
                        getString(R.string.home_language, LanguagePicker.currentLabel(this)),
                        getString(R.string.home_language_detail),
                    ) { LanguagePicker.show(this) },
                    navigationRow(
                        getString(R.string.parent_row_settings),
                        getString(R.string.parent_row_settings_detail),
                    ) { startActivity(ParentSettingsActivity.intent(this)) },
                ),
            ),
        )
    }

    /**
     * Today on the child's phone (RPT-1).
     *
     * The figures a parent opens the app for, in the order they are wanted: how
     * much is left, then how much has gone, then where. Identical to what the
     * child sees on their own dashboard — that symmetry is the product's central
     * promise, so this screen shows no more and no less.
     */
    private fun todayCard(): LinearLayout = card {
        val today = summaries.latest
        if (today == null) {
            addView(
                TextView(this@ParentHomeActivity).apply {
                    setText(R.string.parent_today_none)
                    styleAs(R.style.TextAppearance_Dementor_Subtitle)
                },
            )
            addView(captionText(getString(R.string.parent_today_none_hint)))
            return@card
        }

        addView(
            TextView(this@ParentHomeActivity).apply {
                text = headlineFor(today)
                styleAs(R.style.TextAppearance_Dementor_Display)
                setTextColor(ContextCompat.getColor(context, colourFor(today)))
            },
        )

        if (today.hasLimit) {
            addView(
                ProgressBar(
                    this@ParentHomeActivity,
                    null,
                    android.R.attr.progressBarStyleHorizontal,
                ).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dimen(R.dimen.budget_bar_height),
                    ).apply { topMargin = dimen(R.dimen.space_md) }
                    max = 100
                    progress = percentUsed(today)
                    progressTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(context, colourFor(today)),
                    )
                },
            )
        }

        addView(
            statRow(
                getString(R.string.parent_today_used),
                Format.limit(today.usedMinutes),
            ),
        )

        if (today.topApps.isNotEmpty()) {
            addView(captionText(getString(R.string.parent_today_apps)))
            today.topApps.forEach { app ->
                addView(statRow(app.label, Format.limit(app.minutes)))
            }
        }

        addView(
            captionText(
                getString(
                    R.string.parent_today_as_of,
                    timeFormat.format(today.receivedAt.atZone(ZoneId.systemDefault())),
                ),
            ),
        )
    }

    private fun headlineFor(today: DailySummary): String = when {
        !today.hasLimit -> getString(R.string.parent_today_no_limit)
        today.remainingMinutes <= 0 -> getString(R.string.home_used_up)
        else -> getString(R.string.parent_today_remaining) + " " + Format.limit(today.remainingMinutes)
    }

    /**
     * The level travels as an ordinal so a parent phone needs no copy of the
     * rules to colour it. An unrecognised value reads as normal rather than as
     * an alarm — a newer child build must not make this screen shout.
     */
    private fun colourFor(today: DailySummary): Int =
        when (BudgetLevel.entries.getOrNull(today.level)) {
            BudgetLevel.EXHAUSTED -> R.color.state_critical
            BudgetLevel.GRACE, BudgetLevel.NOTICE -> R.color.state_warning
            else -> R.color.state_ok
        }

    private fun percentUsed(today: DailySummary): Int {
        val total = today.allowedMinutes + today.bonusMinutes
        if (total <= 0) return 0
        return ((today.usedMinutes * 100L) / total).coerceIn(0L, 100L).toInt()
    }

    /**
     * Confirmed, because it is the one button here that reaches across and changes
     * what the other phone is doing right now.
     */
    private fun confirmLock() {
        AlertDialog.Builder(this)
            .setTitle(R.string.parent_lock_confirm_title)
            .setMessage(R.string.parent_lock_confirm_body)
            .setPositiveButton(R.string.parent_row_lock) { _, _ ->
                sync.publishLockNow()
                Audit.record(this, AuditActor.PARENT, AuditAction.DEVICE_LOCKED)
                Toast.makeText(this, R.string.parent_lock_sent, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Asks where the phone is.
     *
     * No confirmation: asking is not the irreversible part, and the child is told
     * every time regardless — which is what makes it reasonable to ask at all.
     */
    private fun locate() {
        sync.publishLocateRequest()
        Audit.record(this, AuditActor.PARENT, AuditAction.LOCATE_REQUESTED)
        Toast.makeText(this, R.string.parent_locate_sent, Toast.LENGTH_SHORT).show()
    }

    private fun statusLine(configured: Boolean): String = when {
        !configured -> getString(R.string.parent_not_connected_hint)
        lastCheckedAt == null -> getString(R.string.parent_checking)
        else -> getString(
            R.string.parent_checked_at,
            timeFormat.format(checkNotNull(lastCheckedAt).atZone(ZoneId.systemDefault())),
        )
    }

    /**
     * Refreshes in the background and re-renders with whatever arrived.
     *
     * The callback lands on the sync executor, so it hops back to the main thread
     * before touching a view.
     */
    private fun pollQuietly() {
        if (!sync.settings().isUsable) return
        sync.pollNow {
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                lastCheckedAt = Instant.now()
                render()
            }
        }
    }

    companion object {
        private const val TIME_PATTERN = "HH:mm"

        fun intent(context: Context) = Intent(context, ParentHomeActivity::class.java)
    }
}
