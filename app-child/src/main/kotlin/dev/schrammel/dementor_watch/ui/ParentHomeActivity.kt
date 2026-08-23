package dev.schrammel.dementor_watch.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import dev.schrammel.dementor_watch.R
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
class ParentHomeActivity : DementorActivity() {

    private lateinit var sync: SyncCoordinator
    private lateinit var inbox: InboxStore
    private lateinit var column: LinearLayout

    /** Null until this phone has successfully reached the topic in this session. */
    private var lastCheckedAt: Instant? = null

    private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern(TIME_PATTERN)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sync = SyncCoordinator(this)
        inbox = InboxStore(this)
        // Idempotent, and re-asserted here so a phone that was granted the family
        // connection after choosing its role still starts listening.
        ParentPollWorker.schedule(this)

        column = contentColumn()
        showColumn(column)
    }

    override fun onResume() {
        super.onResume()
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
                        getString(R.string.parent_row_device),
                        getString(R.string.parent_row_device_detail),
                    ) { startActivity(ParentDeviceActivity.intent(this)) },
                    navigationRow(
                        getString(R.string.parent_row_audit),
                        getString(R.string.parent_row_audit_detail),
                    ) { startActivity(AuditActivity.intent(this)) },
                    navigationRow(
                        getString(R.string.parent_row_sync),
                        getString(R.string.parent_row_sync_detail),
                    ) { startActivity(SyncSettingsActivity.intent(this)) },
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
