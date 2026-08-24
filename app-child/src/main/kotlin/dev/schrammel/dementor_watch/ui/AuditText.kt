package dev.schrammel.dementor_watch.ui

import android.content.Context
import dev.schrammel.dementor_watch.R
import dev.schrammel.dementor_watch.model.AuditAction
import dev.schrammel.dementor_watch.model.AuditActor
import dev.schrammel.dementor_watch.model.AuditEntry
import dev.schrammel.dementor_watch.model.InstallPolicy
import dev.schrammel.dementor_watch.model.ValueKind
import dev.schrammel.dementor_watch.rules.DnsProfile
import dev.schrammel.dementor_watch.security.DeviceRole
import dev.schrammel.dementor_watch.sync.SyncRole
import java.time.LocalTime

/**
 * Turns a stored [AuditEntry] into a sentence.
 *
 * Rendering happens here rather than at write time so the history follows the
 * app's language. An entry written while the phone was in English would otherwise
 * stay English after the family switched to Swedish — a small wrongness, but
 * exactly the kind that makes a shared record feel like it belongs to one side.
 *
 * The `when` is exhaustive on purpose: adding an action to the enum and not
 * deciding how it reads is a compile error rather than a blank line in the log.
 */
object AuditText {

    fun describe(context: Context, entry: AuditEntry): String {
        val subject = entry.subject.orEmpty()
        val before = value(context, entry, entry.before)
        val after = value(context, entry, entry.after)

        return when (entry.action) {
            AuditAction.DAILY_LIMIT_CHANGED ->
                context.getString(R.string.audit_daily_limit_changed, before, after)

            AuditAction.APP_LIMIT_CHANGED ->
                context.getString(R.string.audit_app_limit_changed, subject, before, after)

            AuditAction.APP_EXEMPTION_CHANGED -> context.getString(
                if (entry.after.toBoolean()) {
                    R.string.audit_app_exempted
                } else {
                    R.string.audit_app_unexempted
                },
                subject,
            )

            AuditAction.APP_BLOCK_CHANGED -> context.getString(
                if (entry.after.toBoolean()) {
                    R.string.audit_app_blocked
                } else {
                    R.string.audit_app_unblocked
                },
                subject,
            )

            AuditAction.APP_REVIEWED ->
                context.getString(R.string.audit_app_reviewed, subject)

            AuditAction.SCHEDULE_SAVED ->
                context.getString(R.string.audit_schedule_saved, subject, after)

            AuditAction.SCHEDULE_DELETED ->
                context.getString(R.string.audit_schedule_deleted, subject)

            AuditAction.INSTALL_POLICY_CHANGED -> context.getString(
                R.string.audit_install_policy_changed,
                installPolicy(context, entry.after),
            )

            AuditAction.INSTALL_WINDOW_OPENED ->
                context.getString(R.string.audit_install_window_opened, after)

            AuditAction.CATEGORY_SAVED ->
                context.getString(R.string.audit_category_saved, subject, after)

            AuditAction.CATEGORY_DELETED ->
                context.getString(R.string.audit_category_deleted, subject)

            AuditAction.BEDTIME_CHANGED ->
                context.getString(R.string.audit_bedtime_changed, before, after)

            AuditAction.BEDTIME_ENABLED -> context.getString(
                if (entry.after.toBoolean()) {
                    R.string.audit_bedtime_on
                } else {
                    R.string.audit_bedtime_off
                },
            )

            AuditAction.CONTEXT_RULE_SAVED ->
                context.getString(R.string.audit_context_rule_saved, subject)

            AuditAction.CONTEXT_RULE_DELETED ->
                context.getString(R.string.audit_context_rule_deleted, subject)

            AuditAction.GOAL_CHANGED ->
                context.getString(R.string.audit_goal_changed, after)

            AuditAction.GOAL_PRIZE_CHANGED ->
                context.getString(R.string.audit_goal_prize_changed)

            AuditAction.REQUEST_RAISED ->
                context.getString(R.string.audit_request_raised, after, subject)

            AuditAction.REQUEST_APPROVED ->
                context.getString(R.string.audit_request_approved, after, subject)

            AuditAction.REQUEST_DENIED ->
                context.getString(R.string.audit_request_denied, after, subject)

            AuditAction.REQUEST_WITHDRAWN ->
                context.getString(R.string.audit_request_withdrawn, after, subject)

            AuditAction.BONUS_GRANTED ->
                context.getString(R.string.audit_bonus_granted, after, subject)

            AuditAction.BONUS_CLEARED -> context.getString(R.string.audit_bonus_cleared)

            AuditAction.PIN_CHANGED -> context.getString(R.string.audit_pin_changed)

            AuditAction.BIOMETRIC_UNLOCK_CHANGED ->
                context.getString(R.string.audit_biometric_changed, after)

            AuditAction.WEB_FILTER_CHANGED ->
                context.getString(R.string.audit_web_filter_changed, webProfile(context, entry.after))

            AuditAction.BROWSER_POLICY_CHANGED ->
                context.getString(R.string.audit_browser_policy_changed, subject, after)

            AuditAction.DEVICE_APPROVED -> context.getString(
                R.string.audit_device_approved,
                subject,
                deviceRole(context, entry.after),
            )

            AuditAction.DEVICE_REVOKED ->
                context.getString(R.string.audit_device_revoked, subject)

            AuditAction.SYNC_CONFIGURED -> context.getString(
                if (entry.after.toBoolean()) {
                    R.string.audit_sync_on
                } else {
                    R.string.audit_sync_off
                },
            )

            AuditAction.TAMPER_PROTECTION_CHANGED -> context.getString(
                if (entry.after.toBoolean()) {
                    R.string.audit_tamper_on
                } else {
                    R.string.audit_tamper_off
                },
            )

            AuditAction.DEVICE_RELEASED -> context.getString(R.string.audit_device_released)

            AuditAction.DEVICE_LOCKED -> context.getString(R.string.audit_device_locked)

            AuditAction.REQUEST_AUTO_APPROVED ->
                context.getString(R.string.audit_request_auto_approved, after, subject)

            AuditAction.AUTO_RULE_SAVED ->
                context.getString(R.string.audit_auto_rule_saved, subject, after)

            AuditAction.AUTO_RULE_DELETED ->
                context.getString(R.string.audit_auto_rule_deleted, subject)

            AuditAction.APP_NETWORK_CHANGED -> context.getString(
                if (entry.after.toBoolean()) {
                    R.string.audit_app_network_blocked
                } else {
                    R.string.audit_app_network_allowed
                },
                subject,
            )

            AuditAction.WEB_LOGGING_CHANGED -> context.getString(
                if (entry.after.toBoolean()) {
                    R.string.audit_web_logging_on
                } else {
                    R.string.audit_web_logging_off
                },
            )

            AuditAction.LOCATE_REQUESTED ->
                context.getString(R.string.audit_locate_requested)

            AuditAction.LOCATION_SHARED ->
                context.getString(R.string.audit_location_shared)

            AuditAction.PLACE_ENTERED ->
                context.getString(R.string.audit_place_entered, subject)

            AuditAction.PLACE_LEFT ->
                context.getString(R.string.audit_place_left, subject)

            AuditAction.SOS_RAISED -> context.getString(R.string.audit_sos_raised)

            AuditAction.SOS_CLEARED -> context.getString(R.string.audit_sos_cleared)

            AuditAction.ROLE_CHANGED ->
                context.getString(R.string.audit_role_changed, role(context, entry.after))
        }
    }

    fun actor(context: Context, actor: AuditActor): String = context.getString(
        when (actor) {
            AuditActor.PARENT -> R.string.audit_actor_parent
            AuditActor.CHILD -> R.string.audit_actor_child
            AuditActor.SYSTEM -> R.string.audit_actor_system
        },
    )

    /** Stored values are locale-independent; this is where they become readable. */
    private fun value(context: Context, entry: AuditEntry, raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return when (entry.action.valueKind) {
            ValueKind.MINUTES ->
                raw.toIntOrNull()?.let(Format::limit) ?: raw

            ValueKind.TIME_RANGE -> range(raw)

            ValueKind.FLAG -> context.getString(
                if (raw.toBoolean()) R.string.audit_value_on else R.string.audit_value_off,
            )

            ValueKind.NONE, ValueKind.TOKEN -> raw
        }
    }

    /** `22:30-07:00` as stored, with an en dash for reading. */
    private fun range(raw: String): String {
        val parts = raw.split('-')
        if (parts.size != 2) return raw
        val start = runCatching { LocalTime.parse(parts[0]) }.getOrNull() ?: return raw
        val end = runCatching { LocalTime.parse(parts[1]) }.getOrNull() ?: return raw
        return "${Format.time(start)}–${Format.time(end)}"
    }

    private fun installPolicy(context: Context, raw: String?): String = context.getString(
        if (raw == InstallPolicy.GATED.name) {
            R.string.audit_install_gated
        } else {
            R.string.audit_install_notify
        },
    )

    private fun webProfile(context: Context, raw: String?): String = context.getString(
        when (DnsProfile.parse(raw)) {
            DnsProfile.OFF -> R.string.web_dns_off
            DnsProfile.MALWARE -> R.string.web_dns_malware
            DnsProfile.ADULT -> R.string.web_dns_adult
            DnsProfile.STRICT -> R.string.web_dns_strict
            DnsProfile.CUSTOM -> R.string.web_dns_custom
        },
    )

    private fun deviceRole(context: Context, raw: String?): String = context.getString(
        if (raw == SyncRole.PARENT.name) {
            R.string.devices_role_parent
        } else {
            R.string.devices_role_child
        },
    )

    private fun role(context: Context, raw: String?): String = context.getString(
        if (raw == DeviceRole.PARENT.name) {
            R.string.audit_role_parent
        } else {
            R.string.audit_role_child
        },
    )
}
