package dev.schrammel.dementor_watch.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import dev.schrammel.dementor_watch.R
import dev.schrammel.dementor_watch.enforcement.EnforcementService
import dev.schrammel.dementor_watch.model.GrantTarget
import dev.schrammel.dementor_watch.model.Request
import dev.schrammel.dementor_watch.model.RequestId
import dev.schrammel.dementor_watch.rules.LocalPolicyStore
import dev.schrammel.dementor_watch.rules.RequestCoordinator
import dev.schrammel.dementor_watch.usage.InstalledApps
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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

    private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern(TIME_PATTERN)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        coordinator = RequestCoordinator(this)
        policyStore = LocalPolicyStore(this)
        installedApps = InstalledApps(this)

        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dimen(R.dimen.screen_padding)
            setPadding(pad, pad, pad, pad)
        }
        setContentView(
            ScrollView(this).apply {
                layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
                addView(container)
            },
        )
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

        val pending = coordinator.pending()
        if (pending.isEmpty()) {
            container.addView(body(getString(R.string.inbox_empty)))
            return
        }
        pending.forEach { container.addView(requestCard(it)) }
    }

    private fun requestCard(request: Request): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dimen(R.dimen.space_md), 0, dimen(R.dimen.space_md))

        addView(
            body(
                resources.getQuantityString(
                    R.plurals.inbox_asking_for,
                    request.amount.inWholeMinutes.toInt(),
                    request.amount.inWholeMinutes.toInt(),
                    describe(request.target),
                ),
            ).styleAs(R.style.TextAppearance_Dementor_ListItem),
        )
        addView(body(getString(R.string.requests_row_reason, request.reason)))
        addView(
            body(
                getString(
                    R.string.inbox_expires_at,
                    timeFormat.format(request.expiresAt.atZone(ZoneId.systemDefault())),
                ),
            ).styleAs(R.style.TextAppearance_Dementor_Caption),
        )

        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    topMargin = dimen(R.dimen.space_sm)
                }
                addView(
                    Button(context).apply {
                        setText(R.string.inbox_approve)
                        layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                            marginEnd = dimen(R.dimen.space_xs)
                        }
                        setOnClickListener { decide(request.id, approve = true) }
                    },
                )
                addView(
                    Button(context).apply {
                        setText(R.string.inbox_deny)
                        layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                        setOnClickListener { decide(request.id, approve = false) }
                    },
                )
            },
        )
    }

    /**
     * Both answers offer a note. A denial with a reason is a conversation; a bare
     * "no" is the thing that makes a teenager stop asking and start working around.
     */
    private fun decide(id: RequestId, approve: Boolean) {
        val note = EditText(this).apply {
            hint = getString(R.string.inbox_note_hint)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dimen(R.dimen.screen_padding)
            setPadding(pad, pad, pad, 0)
            addView(note)
        }

        AlertDialog.Builder(this)
            .setTitle(if (approve) R.string.inbox_approve else R.string.inbox_deny)
            .setView(content)
            .setPositiveButton(if (approve) R.string.inbox_approve else R.string.inbox_deny) { _, _ ->
                val text = note.text.toString()
                if (approve) {
                    coordinator.approve(id, text)
                    // Apply at once so the extra time is usable immediately.
                    EnforcementService.start(this)
                } else {
                    coordinator.deny(id, text)
                }
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun describe(target: GrantTarget): String = when (target) {
        is GrantTarget.Device -> getString(R.string.requests_target_device)
        is GrantTarget.App -> installedApps.labelFor(target.pkg)
        is GrantTarget.Category ->
            policyStore.categories.firstOrNull { it.id == target.id.value }?.name
                ?: getString(R.string.requests_target_category)
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        styleAs(R.style.TextAppearance_Dementor_Body)
        setPadding(0, dimen(R.dimen.space_hairline), 0, dimen(R.dimen.space_hairline))
    }

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val TIME_PATTERN = "HH:mm"

        fun intent(context: Context) = Intent(context, RequestInboxActivity::class.java)
    }
}
