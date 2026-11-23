package dev.schrammel.protego_parentis.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.sync.core.DeviceId
import dev.schrammel.protego_parentis.rules.Feature
import dev.schrammel.protego_parentis.rules.RuleBundle
import dev.schrammel.protego_parentis.sync.core.KnownDevice
import dev.schrammel.protego_parentis.sync.PolicyReplication
import dev.schrammel.protego_parentis.sync.RosterStore
import dev.schrammel.protego_parentis.sync.SyncCoordinator

/**
 * The map of every rule the family can set, in one place.
 *
 * The drawer carries the same destinations as plain rows, but a map answers the
 * question a row cannot: what is *on*, what is *off*, and what does each control
 * actually affect. One screen, every control, a state chip per row and the
 * worked examples one tap away — the tutorial is linked from here rather than
 * from whichever screens remembered it (#225).
 *
 * Routing goes through [RulesEntry.launchControl], exactly as the drawer does:
 * with one managed child — every family today — a row opens that control
 * directly, and with several the reader picks the child first.
 */
class RulesOverviewActivity : ParentRoleActivity() {

    private lateinit var column: LinearLayout
    private var pendingExportJson: String? = null
    private var pendingImportChild: DeviceId? = null

    private val createExportFile = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val json = pendingExportJson
        pendingExportJson = null
        if (uri == null || json == null) return@registerForActivityResult
        val written = runCatching {
            contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(json.toByteArray(Charsets.UTF_8))
            } ?: throw IllegalStateException("no stream")
        }.isSuccess
        toast(if (written) R.string.rules_share_done else R.string.rules_share_file_error)
    }

    private val pickRulesFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val childId = pendingImportChild
        pendingImportChild = null
        if (uri == null || childId == null) return@registerForActivityResult
        val body = runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        if (body.isNullOrBlank()) {
            toast(R.string.rules_share_bad_file)
            return@registerForActivityResult
        }
        importBundle(body, childId)
    }

    override fun onParentPhoneReady(savedInstanceState: Bundle?) {
        column = contentColumn()
        showColumn(column)
    }

    override fun onParentPhoneResumed() = render()

    private fun render() {
        column.removeAllViews()
        column.addView(screenTitle(getString(R.string.parent_row_rules)))
        column.addView(bodyText(getString(R.string.parent_row_rules_detail)))

        val flags = disabledFeaturesByChild()

        column.addView(sectionHeader(getString(R.string.home_group_child_time)))
        listOf(
            Control(HomeDestination.BONUS, R.string.rules_row_bonus, R.string.rules_row_bonus_detail, Feature.BONUS),
            Control(HomeDestination.GOAL, R.string.rules_row_goal, R.string.rules_row_goal_detail, Feature.GOALS),
            Control(HomeDestination.REQUEST_INBOX, R.string.rules_row_requests, R.string.rules_row_requests_detail, Feature.REQUESTS),
            Control(HomeDestination.AUTO_APPROVE, R.string.rules_row_auto, R.string.rules_row_auto_detail, Feature.AUTO_APPROVE),
        ).forEach { column.addView(controlRow(it, enabled = featureVisible(it.feature, flags))) }

        column.addView(sectionHeader(getString(R.string.home_group_child_limits)))
        listOf(
            Control(HomeDestination.CONTEXT_RULES, R.string.context_title, R.string.context_explainer, Feature.CONTEXT_RULES),
            Control(HomeDestination.CATEGORIES, R.string.rules_row_categories, R.string.rules_row_categories_detail, Feature.CATEGORIES),
            Control(HomeDestination.APP_LIMITS, R.string.rules_row_app_limits, R.string.rules_row_app_limits_detail, Feature.APP_LIMITS),
            Control(HomeDestination.APP_CONTROL, R.string.rules_row_apps, R.string.rules_row_apps_detail, Feature.APP_CONTROL),
            Control(HomeDestination.WEB_FILTER, R.string.rules_row_web, R.string.rules_row_web_detail, Feature.WEB_FILTER),
            Control(HomeDestination.PLACES, R.string.rules_row_places, R.string.rules_row_places_detail, Feature.PLACES),
        ).forEach { column.addView(controlRow(it, enabled = featureVisible(it.feature, flags))) }

        // The worked examples, offered here rather than only on the screens
        // that remembered to link them (#225).
        addSection(
            column,
            null,
            listOf(
                navigationRow(
                    getString(R.string.rules_tutorial_title),
                    getString(R.string.rules_tutorial_row_detail),
                ) { startActivity(RulesTutorialActivity.intent(this)) },
            ),
        )

        // Share the whole ruleset with another family: export packs the
        // rules and everything they depend on; import applies a bundle the
        // same way any other parent edit is applied.
        addSection(
            column,
            null,
            listOf(
                navigationRow(
                    getString(R.string.rules_share_export),
                    getString(R.string.rules_share_export_detail),
                ) {
                    withChild { child ->
                        pendingExportJson = bundleFor(child.id)
                        if (pendingExportJson == null) {
                            toast(R.string.rules_share_nothing)
                        } else {
                            createExportFile.launch(RuleBundle.FILE_NAME)
                        }
                    }
                },
                navigationRow(
                    getString(R.string.rules_share_import),
                    getString(R.string.rules_share_import_detail),
                                ) {
                    withChild { child ->
                        pendingImportChild = child.id
                        pickRulesFile.launch(arrayOf("application/json", "text/*", "application/octet-stream"))
                    }
                },
            ),
        )
    }

    /**
     * Runs [block] with the managed child this screen's rules belong to.
     * One child — every family today — resolves without asking; several ask
     * with a plain picker, since a ruleset is per child.
     */
    private fun withChild(block: (KnownDevice) -> Unit) {
        val children = RosterStore(this).children()
        when {
            children.isEmpty() -> toast(R.string.rules_share_no_child)
            children.size == 1 -> block(children.first())
            else -> AlertDialog.Builder(this)
                .setTitle(R.string.rules_share_pick_child)
                .setItems(children.map { it.name }.toTypedArray()) { _, which -> block(children[which]) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /** The child's complete ruleset, packed for another family; null if none have arrived. */
    private fun bundleFor(childId: DeviceId): String? {
        val replication = PolicyReplication(this)
        val snapshot = replication.read(childId) ?: return null
        return RuleBundle(
            snapshot = snapshot,
            contextRules = replication.readContextRules(childId),
            schedules = replication.readSchedules(childId),
            placeRefs = replication.readPlaceRefs(childId),
            autoApprovals = replication.readAutoApprovals(childId),
        ).toJson()
    }

    private fun importBundle(raw: String?, childId: DeviceId) {
        val bundle = RuleBundle.decode(raw)
        if (bundle == null) {
            toast(R.string.rules_share_bad_file)
            return
        }
        val counts = getString(
            R.string.rules_share_import_summary,
            bundle.contextRules.size,
            bundle.snapshot.categories.size,
            bundle.schedules.size,
            bundle.placeRefs.size,
            bundle.autoApprovals.size,
        )
        confirmDialog(
            getString(R.string.rules_share_import),
            counts + "\n\n" + getString(R.string.rules_share_import_note),
            R.string.rules_share_import,
        ) {
            // Applied through the ordinary parent-edit paths, so the rules
            // replicate to every phone in the receiving family like any
            // other change made on this one.
            val sync = SyncCoordinator(this)
            sync.applyPolicyEdit(bundle.snapshot, childId)
            sync.applyScheduleEdit(bundle.schedules, childId)
            sync.applyContextRuleEdit(bundle.contextRules, childId)
            sync.applyPlaceRefEdit(bundle.placeRefs, childId)
            sync.applyAutoApprovalEdit(bundle.autoApprovals, childId)
            toast(R.string.rules_share_done)
        }
    }

    private data class Control(
        val destination: HomeDestination,
        val title: Int,
        val detail: Int,
        val feature: Feature,
    )

    /**
     * A control row: title and what it affects, with a state chip at the end.
     * A switched-off control is still tappable — turning it on happens on the
     * very screen the row opens.
     */
    private fun controlRow(control: Control, enabled: Boolean): LinearLayout {
        val chip = statusChip(
            getString(if (enabled) R.string.rules_state_on else R.string.rules_state_off),
            ContextCompat.getColor(
                this,
                if (enabled) R.color.state_ok else R.color.state_warning,
            ),
        )
        return navigationRow(
            title = getString(control.title),
            subtitle = getString(control.detail),
            trailing = chip,
        ) {
            RulesEntry.launchControl(this, control.destination)
        }
    }

    /** The same read ParentHomeActivity does, once per render. */
    private fun disabledFeaturesByChild(): List<Set<String>?> {
        val replication = PolicyReplication(this)
        return RosterStore(this).children().map { child -> replication.read(child.id)?.disabledFeatures }
    }

    companion object {
        fun intent(context: Context) = Intent(context, RulesOverviewActivity::class.java)
    }
}
