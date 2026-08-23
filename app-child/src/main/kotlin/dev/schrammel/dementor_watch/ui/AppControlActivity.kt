package dev.schrammel.dementor_watch.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.schrammel.dementor_watch.R
import dev.schrammel.dementor_watch.audit.Audit
import dev.schrammel.dementor_watch.enforcement.EnforcementService
import dev.schrammel.dementor_watch.model.AuditAction
import dev.schrammel.dementor_watch.model.AuditActor
import dev.schrammel.dementor_watch.rules.AppReviewStore
import dev.schrammel.dementor_watch.rules.LocalPolicyStore
import dev.schrammel.dementor_watch.sync.SyncCoordinator
import dev.schrammel.dementor_watch.usage.AppInfo
import dev.schrammel.dementor_watch.usage.InstalledApps

private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

/**
 * Which apps are allowed on this phone, and what is new (AC-2, AC-3).
 *
 * One screen rather than two, because "review the app that just appeared" and
 * "block an app" are the same decision reached from different directions. A
 * separate review screen would leave a family able to block an app only during
 * the short window in which it counted as new.
 *
 * New apps sit at the top with an explicit Allow and Block, and stay there until
 * one of the two is pressed. Opening the screen is not an answer: a glance that
 * gets interrupted must not silently count as approval.
 */
class AppControlActivity : ParentGatedActivity() {

    private lateinit var store: LocalPolicyStore
    private lateinit var review: AppReviewStore
    private lateinit var sync: SyncCoordinator
    private lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = LocalPolicyStore(this)
        review = AppReviewStore(this)
        sync = SyncCoordinator(this)
        column = contentColumn()
        showColumn(column)
    }

    override fun onGatedContentVisible() = render()

    override fun onGatedContentHidden() {
        column.removeAllViews()
    }

    private fun render() {
        column.removeAllViews()
        column.addView(screenTitle(getString(R.string.apps_title)))
        column.addView(captionText(getString(R.string.apps_hint)))

        Background.load(this, {
            val apps = InstalledApps(this).launchable()
            val pending = review.pending
            apps.partition { it.pkg.value in pending }
        }) { (fresh, rest) ->
            if (fresh.isNotEmpty()) {
                column.addView(sectionHeader(getString(R.string.apps_new_header)))
                column.addView(captionText(getString(R.string.apps_new_hint)))
                column.addView(group(fresh.map(::newAppRow)))
            }

            column.addView(sectionHeader(getString(R.string.apps_all_header)))
            if (rest.isEmpty()) {
                column.addView(bodyText(getString(R.string.apps_none)))
            } else {
                column.addView(group(rest.map(::knownAppRow)))
            }
        }
    }

    /** A new arrival: name, icon, and the two answers. */
    private fun newAppRow(app: AppInfo): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        setPadding(0, dimen(R.dimen.space_sm), 0, dimen(R.dimen.space_sm))

        addView(identity(app))
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    topMargin = dimen(R.dimen.space_xs)
                }
                addView(
                    Button(context).apply {
                        setText(R.string.apps_allow)
                        layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                            marginEnd = dimen(R.dimen.space_xs)
                        }
                        setOnClickListener { decide(app, blocked = false, wasNew = true) }
                    },
                )
                addView(
                    Button(context).apply {
                        setText(R.string.apps_block)
                        layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                        setOnClickListener { decide(app, blocked = true, wasNew = true) }
                    },
                )
            },
        )
    }

    /**
     * An app the family has already seen, with a switch meaning "not on this phone".
     *
     * A checkbox rather than a third state for hiding: making an icon disappear is
     * the harsher and rarer option, and offering it on the same row invites a tap
     * from someone who has not read the difference.
     */
    private fun knownAppRow(app: AppInfo): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        setPadding(0, dimen(R.dimen.space_sm), 0, dimen(R.dimen.space_sm))

        addView(identity(app, weighted = true))
        addView(
            CheckBox(context).apply {
                setText(R.string.apps_blocked)
                isChecked = app.pkg.value in store.blockedPackages
                setOnCheckedChangeListener { _, blocked ->
                    decide(app, blocked, wasNew = false)
                }
            },
        )
    }

    private fun identity(app: AppInfo, weighted: Boolean = false): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = if (weighted) {
                LinearLayout.LayoutParams(0, WRAP, 1f)
            } else {
                LinearLayout.LayoutParams(MATCH, WRAP)
            }

            addView(
                ImageView(context).apply {
                    setImageDrawable(app.icon)
                    layoutParams = LinearLayout.LayoutParams(
                        dimen(R.dimen.row_icon),
                        dimen(R.dimen.row_icon),
                    ).apply { marginEnd = dimen(R.dimen.space_md) }
                },
            )
            addView(
                TextView(context).apply {
                    text = app.label
                    styleAs(R.style.TextAppearance_Dementor_ListItem)
                },
            )
        }

    private fun decide(app: AppInfo, blocked: Boolean, wasNew: Boolean) {
        store.setBlocked(app.pkg.value, blocked)
        Audit.record(
            context = this,
            actor = AuditActor.PARENT,
            action = AuditAction.APP_BLOCK_CHANGED,
            subject = app.label,
            after = Audit.flag(blocked),
        )

        if (wasNew) {
            review.markReviewed(app.pkg.value)
            Audit.record(
                context = this,
                actor = AuditActor.PARENT,
                action = AuditAction.APP_REVIEWED,
                subject = app.label,
            )
            // Tell the parent phones, so the same app does not sit in their list
            // waiting for an answer that has already been given here.
            sync.publishAppDecision(app.pkg.value, blocked)
            render()
        }

        EnforcementService.start(this)
    }

    companion object {
        fun intent(context: Context) = Intent(context, AppControlActivity::class.java)
    }
}
