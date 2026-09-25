package dev.schrammel.protego_parentis.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import dev.schrammel.protego_parentis.AppRegistry
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.model.GrantTarget
import dev.schrammel.protego_parentis.model.Request
import dev.schrammel.protego_parentis.model.RequestId
import dev.schrammel.protego_parentis.rules.LocalPolicyStore
import dev.schrammel.protego_parentis.rules.RequestCoordinator
import dev.schrammel.protego_parentis.rules.TargetLabels
import dev.schrammel.protego_parentis.sync.SyncGraph
import dev.schrammel.protego_parentis.usage.InstalledApps

/**
 * The parent's side of the negotiation.
 *
 * Reached from the rules screen, so it is already behind the PIN — a child must
 * not be able to answer their own request. Approving applies the time immediately;
 * a decision that takes effect "eventually" is one the child will not believe.
 */
class RequestInboxActivity : ParentGatedActivity() {

    private lateinit var coordinator: RequestCoordinator
    private lateinit var policyStore: LocalPolicyStore
    private lateinit var installedApps: InstalledApps
    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sync = SyncGraph.get(this)
        // So a second parent phone sees that this one has already answered.
        coordinator = RequestCoordinator(this, onDecided = sync::publishDecision)
        policyStore = AppRegistry.childStores.policy
        installedApps = InstalledApps(this)

        container = contentColumn()
        showColumn(container)
    }

    override fun onGatedContentVisible() {
        render()
    }

    private fun render() {
        container.removeAllViews()
        container.addView(
            TextView(this).apply {
                setText(R.string.inbox_title)
                styleAs(R.style.TextAppearance_Dementor_Title)
            },
        )

        Background.load(this, { coordinator.pending().sortedBy { it.expiresAt } }) { pending ->
            if (pending.isEmpty()) {
                container.addView(bodyText(getString(R.string.inbox_empty)))
            } else {
                addCollapsibleRows(container, pending.map(::requestCard))
            }
        }
    }

    private fun requestCard(request: Request): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dimen(R.dimen.space_md), 0, dimen(R.dimen.space_md))

        addView(
            bodyText(
                resources.getQuantityString(
                    R.plurals.inbox_asking_for,
                    request.amount.inWholeMinutes.toInt(),
                    request.amount.inWholeMinutes.toInt(),
                    describe(request.target),
                ),
            ).styleAs(R.style.TextAppearance_Dementor_ListItem),
        )
        addView(bodyText(getString(R.string.requests_row_reason, request.reason)))
        addView(
            bodyText(
                getString(
                    R.string.inbox_expires_at,
                    formatTime(request.expiresAt),
                ),
            ).styleAs(R.style.TextAppearance_Dementor_Caption),
        )

        addView(
            twoButtonRow(
                R.string.inbox_approve,
                { decide(request.id, approve = true) },
                R.string.inbox_deny,
                { decide(request.id, approve = false) },
            ),
        )
    }

    /**
     * Both answers offer a note. A denial with a reason is a conversation; a bare
     * "no" is the thing that makes a teenager stop asking and start working around.
     */
    private fun decide(id: RequestId, approve: Boolean) {
        decisionDialog(if (approve) R.string.inbox_approve else R.string.inbox_deny) { text ->
            if (approve) {
                coordinator.approve(id, text)
                // Apply at once so the extra time is usable immediately.
                AppRegistry.ruleMutationSink.rulesChanged()
            } else {
                coordinator.deny(id, text)
            }
            render()
        }
    }

    private fun describe(target: GrantTarget): String =
        TargetLabels.describe(this, target)

    companion object {

        fun intent(context: Context) = Intent(context, RequestInboxActivity::class.java)
    }
}
