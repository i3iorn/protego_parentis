package dev.schrammel.dementor_watch.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import dev.schrammel.dementor_watch.R
import dev.schrammel.dementor_watch.enforcement.NotificationKind
import dev.schrammel.dementor_watch.enforcement.NotificationPreferences
import dev.schrammel.dementor_watch.security.DeviceRole
import dev.schrammel.dementor_watch.security.RoleStore

/**
 * What this phone tells you about.
 *
 * Not PIN-gated. These are this phone's own notifications, and a child deciding
 * they would rather not be pinged ten minutes before their time runs out is not a
 * rule change — it is the same category of choice as the ringtone. The four that
 * cannot be silenced are the ones a child turning things off might most want gone,
 * and those are exactly the ones that are not theirs to remove.
 *
 * Only the kinds this phone can actually send are listed. A parent's phone has no
 * use for a switch about time warnings it will never show, and a list padded with
 * settings that do nothing is a list nobody trusts.
 */
class NotificationSettingsActivity : DementorActivity() {

    private lateinit var preferences: NotificationPreferences
    private lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = NotificationPreferences(this)
        column = contentColumn()
        showColumn(column)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        column.removeAllViews()
        column.addView(screenTitle(getString(R.string.notify_title)))
        column.addView(bodyText(getString(R.string.notify_intro)))

        val onChild = RoleStore(this).roleOrChild == DeviceRole.CHILD
        val mine = NotificationKind.entries.filter { belongsHere(it, onChild) }

        val (optional, fixed) = mine.partition { it.optional }

        column.addView(sectionHeader(getString(R.string.notify_choose_header)))
        column.addView(group(optional.map(::switchRow)))

        column.addView(sectionHeader(getString(R.string.notify_always_header)))
        column.addView(captionText(getString(R.string.notify_always_hint)))
        column.addView(group(fixed.map(::fixedRow)))

        column.addView(sectionHeader(getString(R.string.notify_system_header)))
        column.addView(captionText(getString(R.string.notify_system_hint)))
        column.addView(
            actionButton().apply {
                setText(R.string.notify_system_open)
                setOnClickListener { openSystemSettings() }
            },
        )
    }

    /**
     * Which side of the family a kind is for.
     *
     * The permanent monitoring notice is deliberately absent from both lists. It
     * is not a notification anybody chose and not one anybody may remove — Android
     * requires it for a foreground service and the product requires it for a
     * monitored phone — so a row about it would be a row that does nothing.
     */
    private fun belongsHere(kind: NotificationKind, onChild: Boolean): Boolean = when (kind) {
        NotificationKind.TIME_WARNING,
        NotificationKind.REQUEST_ANSWERED,
        NotificationKind.GOAL_EARNED,
        NotificationKind.LOCATED,
        NotificationKind.LOCKED,
        -> onChild

        NotificationKind.REQUEST_RECEIVED,
        NotificationKind.NEW_APP,
        // A conversation has two ends, so this one is offered at both.
        NotificationKind.CHAT_MESSAGE,
        // So does approving a phone: a parent lets in the managed phone, and
        // whoever sets that phone up lets in the founding parent.
        NotificationKind.DEVICE_PENDING,
        // And so does hearing that one was added, or removed, or that the
        // whole family ended — all three including silently, in the
        // background, which is the case each of them exists for.
        NotificationKind.DEVICE_ADDED,
        NotificationKind.DEVICE_REMOVED,
        NotificationKind.FAMILY_DISBANDED,
        -> true

        NotificationKind.AUTO_APPROVED,
        NotificationKind.DEVICE_HEALTH,
        NotificationKind.PLACE_TRANSITION,
        NotificationKind.EMERGENCY,
        -> !onChild
    }

    private fun switchRow(kind: NotificationKind): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            setPadding(0, dimen(R.dimen.space_sm), 0, dimen(R.dimen.space_sm))

            addView(
                CheckBox(context).apply {
                    setText(titleOf(kind))
                    isChecked = preferences.isEnabled(kind)
                    setOnCheckedChangeListener { _, wanted ->
                        preferences.setEnabled(kind, wanted)
                    }
                },
            )
            addView(
                TextView(context).apply {
                    setText(detailOf(kind))
                    styleAs(R.style.TextAppearance_Dementor_Caption)
                },
            )
        }

    private fun fixedRow(kind: NotificationKind): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            setPadding(0, dimen(R.dimen.space_sm), 0, dimen(R.dimen.space_sm))

            addView(
                TextView(context).apply {
                    setText(titleOf(kind))
                    styleAs(R.style.TextAppearance_Dementor_ListItem)
                },
            )
            addView(
                TextView(context).apply {
                    setText(reasonOf(kind))
                    styleAs(R.style.TextAppearance_Dementor_Caption)
                },
            )
        }

    /**
     * Android's own controls, which can silence a whole channel.
     *
     * Linked to rather than duplicated. This app cannot override what the system
     * settings do, and a screen that implied otherwise would be lying about who is
     * in charge — better to point at the real thing.
     */
    private fun openSystemSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        runCatching { startActivity(intent) }
    }

    private fun titleOf(kind: NotificationKind): Int = when (kind) {
        NotificationKind.TIME_WARNING -> R.string.notify_time_warning
        NotificationKind.REQUEST_ANSWERED -> R.string.notify_request_answered
        NotificationKind.GOAL_EARNED -> R.string.notify_goal
        NotificationKind.LOCATED -> R.string.notify_located
        NotificationKind.LOCKED -> R.string.notify_locked
        NotificationKind.REQUEST_RECEIVED -> R.string.notify_request_received
        NotificationKind.AUTO_APPROVED -> R.string.notify_auto
        NotificationKind.NEW_APP -> R.string.notify_new_app
        NotificationKind.DEVICE_HEALTH -> R.string.notify_health
        NotificationKind.PLACE_TRANSITION -> R.string.notify_place
        NotificationKind.EMERGENCY -> R.string.notify_emergency
        NotificationKind.CHAT_MESSAGE -> R.string.chat_title
        NotificationKind.DEVICE_PENDING -> R.string.notify_device_pending
        NotificationKind.DEVICE_ADDED -> R.string.notify_device_added
        NotificationKind.DEVICE_REMOVED -> R.string.notify_device_removed
        NotificationKind.FAMILY_DISBANDED -> R.string.notify_family_disbanded
    }

    private fun detailOf(kind: NotificationKind): Int = when (kind) {
        NotificationKind.TIME_WARNING -> R.string.notify_time_warning_detail
        NotificationKind.REQUEST_ANSWERED -> R.string.notify_request_answered_detail
        NotificationKind.GOAL_EARNED -> R.string.notify_goal_detail
        NotificationKind.REQUEST_RECEIVED -> R.string.notify_request_received_detail
        NotificationKind.AUTO_APPROVED -> R.string.notify_auto_detail
        NotificationKind.NEW_APP -> R.string.notify_new_app_detail
        NotificationKind.DEVICE_HEALTH -> R.string.notify_health_detail
        NotificationKind.PLACE_TRANSITION -> R.string.notify_place_detail
        NotificationKind.CHAT_MESSAGE -> R.string.notify_chat_detail
        NotificationKind.DEVICE_PENDING -> R.string.notify_device_pending_detail
        // Not reachable: these seven are never shown with a switch.
        NotificationKind.LOCATED,
        NotificationKind.LOCKED,
        NotificationKind.EMERGENCY,
        NotificationKind.DEVICE_ADDED,
        NotificationKind.DEVICE_REMOVED,
        NotificationKind.FAMILY_DISBANDED,
        -> R.string.notify_always_hint
    }

    private fun reasonOf(kind: NotificationKind): Int = when (kind) {
        NotificationKind.LOCATED -> R.string.notify_located_reason
        NotificationKind.LOCKED -> R.string.notify_locked_reason
        NotificationKind.EMERGENCY -> R.string.notify_emergency_reason
        NotificationKind.DEVICE_ADDED -> R.string.notify_device_added_reason
        NotificationKind.DEVICE_REMOVED -> R.string.notify_device_removed_reason
        NotificationKind.FAMILY_DISBANDED -> R.string.notify_family_disbanded_reason
        else -> R.string.notify_always_hint
    }

    companion object {
        fun intent(context: Context) = Intent(context, NotificationSettingsActivity::class.java)
    }
}
