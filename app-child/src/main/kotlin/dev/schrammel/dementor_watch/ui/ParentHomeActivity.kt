package dev.schrammel.dementor_watch.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import dev.schrammel.dementor_watch.R
import dev.schrammel.dementor_watch.audit.Audit
import dev.schrammel.dementor_watch.model.AuditAction
import dev.schrammel.dementor_watch.model.AuditActor
import dev.schrammel.dementor_watch.model.BudgetLevel
import dev.schrammel.dementor_watch.rules.Feature
import dev.schrammel.dementor_watch.rules.FeatureFlags
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

        // A placeholder now, filled in when the answer arrives. Reading the
        // roster touches preference files, and the alternative — holding the
        // whole screen back until it returns — would make the common case, a
        // family that is fine, wait for news that there is nothing to report.
        val banner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(banner)
        Background.load(this, { FamilyStatus.read(this) }) { snapshot ->
            familyBanner(snapshot) { startActivity(FamilyDevicesActivity.intent(this)) }
                ?.let(banner::addView)
        }

        column.addView(sectionHeader(getString(R.string.parent_today_header)))
        column.addView(todayCard())

        val on = FeatureFlags(this)

        // Three groups rather than one list of eleven. The first is what a parent
        // opens the app to do, and it is deliberately unlabelled — the screen
        // title has already said whose phone this is. What follows is arranged by
        // how often anyone touches it, so the rarely-used settles at the bottom
        // instead of sitting between the two things anybody wanted.
        addSection(
            column,
            null,
            buildList {
                if (on.isEnabled(Feature.REQUESTS)) {
                    add(
                        row(R.string.parent_row_inbox, R.string.parent_row_inbox_detail) {
                            startActivity(ParentInboxActivity.intent(this@ParentHomeActivity))
                        },
                    )
                }
                if (on.isEnabled(Feature.BONUS)) {
                    add(
                        row(R.string.parent_row_bonus, R.string.parent_row_bonus_detail) {
                            startActivity(ParentBonusActivity.intent(this@ParentHomeActivity))
                        },
                    )
                }
                add(
                    row(R.string.parent_row_limit, R.string.parent_row_limit_detail) {
                        startActivity(ParentLimitActivity.intent(this@ParentHomeActivity))
                    },
                )
                add(
                    row(R.string.parent_row_rules, R.string.parent_row_rules_detail) {
                        startActivity(ParentRulesActivity.intent(this@ParentHomeActivity))
                    },
                )
                if (on.isEnabled(Feature.CHAT)) {
                    add(
                        row(R.string.chat_title, R.string.chat_row_detail) {
                            startActivity(ChatActivity.intent(this@ParentHomeActivity))
                        },
                    )
                }
                add(row(R.string.parent_row_lock, R.string.parent_row_lock_detail) { confirmLock() })
                // Gated: locating is answered by the other phone, so a row left
                // here after the feature was put away would ask a question that
                // now goes unanswered, which reads as the app being broken.
                if (on.isEnabled(Feature.LOCATION)) {
                    add(row(R.string.parent_row_locate, R.string.parent_row_locate_detail) { locate() })
                }
            },
        )

        addSection(
            column,
            getString(R.string.parent_group_family),
            listOf(
                row(R.string.parent_row_device, R.string.parent_row_device_detail) {
                    startActivity(ParentDeviceActivity.intent(this))
                },
                row(R.string.rules_row_devices, R.string.rules_row_devices_detail) {
                    startActivity(FamilyDevicesActivity.intent(this))
                },
                row(R.string.parent_row_audit, R.string.parent_row_audit_detail) {
                    startActivity(AuditActivity.intent(this))
                },
            ),
        )

        // Settings used to have its own row here too, next to Family. It now
        // lives one tap inside Family instead — reachable only by way of the
        // family it configures, rather than as a second front door.
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
                formatLimit(today.usedMinutes),
            ),
        )

        if (today.topApps.isNotEmpty()) {
            addView(captionText(getString(R.string.parent_today_apps)))
            today.topApps.forEach { app ->
                addView(statRow(app.label, formatLimit(app.minutes)))
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
        else -> getString(R.string.parent_today_remaining) + " " + formatLimit(today.remainingMinutes)
    }

    /**
     * The level travels as an ordinal so a parent phone needs no copy of the
     * rules to colour it. An unrecognised value reads as normal rather than as
     * an alarm — a newer child build must not make this screen shout. The colour
     * itself comes from the same [colourFor] the child's own dashboard uses.
     */
    private fun colourFor(today: DailySummary): Int =
        colourFor(BudgetLevel.entries.getOrNull(today.level) ?: BudgetLevel.NORMAL)

    private fun percentUsed(today: DailySummary): Int =
        percentUsed(today.usedMinutes, today.allowedMinutes + today.bonusMinutes)

    /**
     * Confirmed, because it is the one button here that reaches across and changes
     * what the other phone is doing right now.
     */
    private fun confirmLock() {
        if (!reachable()) return
        confirmDialog(
            R.string.parent_lock_confirm_title,
            R.string.parent_lock_confirm_body,
            R.string.parent_row_lock,
        ) {
            sync.publishLockNow()
            Audit.record(this, AuditActor.PARENT, AuditAction.DEVICE_LOCKED)
            toast(R.string.parent_lock_sent)
        }
    }

    /**
     * Asks where the phone is.
     *
     * No confirmation: asking is not the irreversible part, and the child is told
     * every time regardless — which is what makes it reasonable to ask at all.
     */
    private fun locate() {
        if (!reachable()) return
        sync.publishLocateRequest()
        Audit.record(this, AuditActor.PARENT, AuditAction.LOCATE_REQUESTED)
        toast(R.string.parent_locate_sent)
        // Straight to where the answer lands. Asking from here and displaying
        // the result on another screen meant a position could arrive correctly
        // and never be seen — which reads exactly like the request having failed.
        startActivity(ParentDeviceActivity.intent(this))
    }

    /**
     * Whether there is anywhere for a command to go.
     *
     * Both of these publish and forget — the other phone picks the message up when
     * it next checks in, so there is no synchronous answer to report. That is fine
     * as long as there is a family to publish to. Without one, publish() quietly
     * does nothing while the toast said "Sent", which is a lie about the one kind
     * of action a parent most needs to be able to trust.
     *
     * It cannot promise delivery, and does not try to. It only refuses to claim
     * something was sent when there was nobody to send it to.
     */
    private fun reachable(): Boolean {
        if (sync.settings().isUsable) return true
        Toast.makeText(this, R.string.parent_not_connected_action, Toast.LENGTH_LONG).show()
        return false
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
