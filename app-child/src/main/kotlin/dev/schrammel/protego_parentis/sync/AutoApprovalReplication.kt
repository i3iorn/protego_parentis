package dev.schrammel.protego_parentis.sync

import android.content.Context
import dev.schrammel.protego_parentis.model.AutoApproval
import dev.schrammel.protego_parentis.model.AutoApprovalWindow
import dev.schrammel.protego_parentis.rules.AutoApprovalStore
import dev.schrammel.protego_parentis.sync.core.DeviceId
import dev.schrammel.protego_parentis.sync.core.ReplicationRules
import dev.schrammel.protego_parentis.sync.core.StateOp
import java.time.Instant
import kotlin.time.Duration.Companion.minutes

/**
 * Auto-approval rules — RQ-1's standing answers — as replicated state,
 * [PolicyReplication]'s delegate for [AutoApprovalStore.rules].
 */
internal class AutoApprovalReplication(context: Context) {

    private val appContext = context.applicationContext
    private val store = AutoApprovalStore(appContext)
    private val records = PolicyRecordReplication(appContext)
    private val state get() = records.state

    /**
     * Records [rules] as [childId]'s replicated auto-approval rules — the
     * schedules/contextual-rules shape's equivalent for RQ-1's standing
     * answers.
     *
     * [AutoApproval.target] does not round-trip: [AutoApprovalStore] itself
     * has never persisted it either — no editor screen sets it — so there is
     * nothing here to replicate yet. A rule read back always has `target =
     * null`, exactly as one read from local storage already does.
     */
    fun writeAutoApprovals(
        rules: List<AutoApproval>,
        childId: DeviceId?,
        now: Instant = Instant.now(),
    ): List<StateOp> = records.write(childId) { id -> flattenAutoApprovals(rules, id, now) }

    /**
     * [childId]'s replicated auto-approval rules, excluding any this replica
     * has been told is gone — see [FIELD_AUTO_DELETED] on
     * [flattenAutoApprovals], the same deleted-marker approach
     * [ScheduleReplication.readSchedules] uses.
     */
    fun readAutoApprovals(childId: DeviceId?): List<AutoApproval> {
        val id = childId ?: return emptyList()
        val root = "${ReplicationRules.POLICY}/${id.value}/$PATH_AUTO_APPROVAL"
        return state.idsUnder(root).mapNotNull { ruleId ->
            val fields = state.children("$root/$ruleId")
            if (FIELD_AUTO_DELETED in fields) return@mapNotNull null

            val name = fields[FIELD_AUTO_NAME] ?: return@mapNotNull null
            val minutes = fields[FIELD_AUTO_MAX_MINUTES]?.toIntOrNull() ?: return@mapNotNull null
            val count = fields[FIELD_AUTO_MAX_PER_WINDOW]?.toIntOrNull() ?: return@mapNotNull null
            runCatching {
                AutoApproval(
                    id = ruleId,
                    name = name,
                    enabled = fields[FIELD_AUTO_ENABLED]?.toBooleanStrictOrNull() ?: true,
                    maxAmount = minutes.minutes,
                    maxPerWindow = count,
                    window = AutoApprovalWindow.entries.firstOrNull { it.name == fields[FIELD_AUTO_WINDOW] }
                        ?: AutoApprovalWindow.WEEK,
                )
            }.getOrNull()
        }
    }

    /**
     * Records this phone's own [AutoApprovalStore] rules as replicated
     * writes — [ContextRuleReplication.pushContextRulesFromLocal]'s
     * equivalent.
     */
    fun pushAutoApprovalsFromLocal(childId: DeviceId?): List<StateOp> =
        writeAutoApprovals(store.rules, childId)

    /** [ContextRuleReplication.pullContextRulesIntoLocal]'s equivalent, for auto-approval rules. */
    fun pullAutoApprovalsIntoLocal(childId: DeviceId?) {
        store.rules = readAutoApprovals(childId)
    }

    /**
     * Every auto-approval rule as a path, plus a [FIELD_AUTO_DELETED] marker
     * — see [ScheduleReplication.flattenSchedules] for why a marker rather
     * than nulling every field.
     */
    private fun flattenAutoApprovals(
        rules: List<AutoApproval>,
        childId: DeviceId,
        now: Instant,
    ): Map<String, String?> = buildMap {
        val root = "${ReplicationRules.POLICY}/${childId.value}/$PATH_AUTO_APPROVAL"
        rules.forEach { rule ->
            val ruleRoot = "$root/${rule.id}"
            put("$ruleRoot/$FIELD_AUTO_NAME", rule.name)
            put("$ruleRoot/$FIELD_AUTO_ENABLED", rule.enabled.toString())
            put("$ruleRoot/$FIELD_AUTO_MAX_MINUTES", rule.maxAmount.inWholeMinutes.toString())
            put("$ruleRoot/$FIELD_AUTO_MAX_PER_WINDOW", rule.maxPerWindow.toString())
            put("$ruleRoot/$FIELD_AUTO_WINDOW", rule.window.name)
        }

        with(records) { tombstoneGone(root, rules.map { it.id }.toSet(), FIELD_AUTO_DELETED, now) }
    }

    companion object {
        const val PATH_AUTO_APPROVAL = "autoApproval"
        const val FIELD_AUTO_NAME = "name"
        const val FIELD_AUTO_ENABLED = "enabled"
        const val FIELD_AUTO_MAX_MINUTES = "maxMinutes"
        const val FIELD_AUTO_MAX_PER_WINDOW = "maxPerWindow"
        const val FIELD_AUTO_WINDOW = "window"

        /** As [ScheduleReplication.FIELD_SCHEDULE_DELETED], for an auto-approval rule. */
        const val FIELD_AUTO_DELETED = "deleted"
    }
}
