package dev.schrammel.protego_parentis.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.sync.core.DeviceId
import dev.schrammel.protego_parentis.sync.SyncCoordinator
import dev.schrammel.protego_parentis.ui.rules.appCatalog
import dev.schrammel.protego_parentis.usage.AppInfo

/**
 * Multi-select list of installed apps.
 *
 * Shared by "apps with no limit", category membership and rule targets, because
 * all three are the same question — pick some apps.
 *
 * Parent-gated even though it is only ever launched from screens that are already
 * gated. Relying on the caller was not enough: left on top of the task it could be
 * resumed straight from recents, and the result is delivered to the caller before
 * the caller re-checks the PIN, so a selection changed here would already have
 * been applied by the time anything asked who was holding the phone.
 */
class AppPickerActivity : ParentGatedActivity() {

    private val selected = linkedSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        selected += intent.getStringArrayListExtra(EXTRA_SELECTED).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()

        content = contentColumn()
        content.addView(
            TextView(this).apply {
                text = title
                styleAs(R.style.TextAppearance_Dementor_Headline)
                setPadding(0, 0, 0, dimen(R.dimen.space_md))
            },
        )
        content.addView(searchField { query = it; renderRows() })
        content.addView(rows)
        showColumn(content)
    }

    override fun onGatedContentVisible() {
        authenticated = true
        if (loaded) return
        loaded = true
        target = RuleTargets.resolve(this) ?: return finish()
        // Loading every launchable app's label and icon is the slowest thing this
        // screen does, so the list arrives after the frame rather than blocking it.
        Background.load(this, { appCatalog(this, target).launchable() }) { loadedApps ->
            apps = loadedApps
            renderRows()
        }
    }

    /**
     * Rebuilds only the rows, never [selected].
     *
     * That distinction is the whole point. An app ticked and then filtered out of
     * view is still chosen; rebuilding the selection from whatever happens to be
     * on screen would quietly discard a parent's choices.
     */
    private fun renderRows() {
        rows.removeAllViews()
        if (apps.isEmpty() && !target.isOwnDevice) {
            rows.addView(requestAppListHint())
            return
        }
        val visible = AppSearch.filter(apps, query, { it.label }, { it.pkg.value })
        if (visible.isEmpty() && apps.isNotEmpty()) {
            rows.addView(noSearchResults())
            return
        }
        visible.forEach { app -> rows.addView(row(app.pkg.value, app.label, app.icon)) }
    }

    /**
     * The managed phone's own list arrives only when asked for — nothing on this
     * screen requests it automatically, since a parent opening a picker that
     * already has a cached list should not re-trigger a sync every time. Before
     * #85, nothing anywhere ever asked either, so a family that enrolled after
     * this screen last ran a request saw a permanently empty list with no way
     * to explain or fix it.
     */
    private fun requestAppListHint(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(bodyText(getString(R.string.app_picker_empty_remote_hint)))
        addView(
            actionButton().apply {
                setText(R.string.app_picker_request_list)
                setOnClickListener {
                    SyncCoordinator(this@AppPickerActivity).publishAppInventoryRequest()
                    toast(getString(R.string.app_picker_request_list_sent))
                }
            },
        )
    }

    private lateinit var content: LinearLayout
    private lateinit var target: RuleTarget
    private val rows: LinearLayout by lazy {
        LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    }
    private var apps: List<AppInfo> = emptyList()
    private var query = ""
    private var loaded = false
    private var authenticated = false

    private fun row(
        pkg: String,
        label: String,
        icon: android.graphics.drawable.Drawable?,
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dimen(R.dimen.space_xs), 0, dimen(R.dimen.space_xs))

        addView(
            ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dimen(R.dimen.row_icon), dimen(R.dimen.row_icon))
                    .apply { marginEnd = dimen(R.dimen.space_md) }
                setImageDrawable(icon)
            },
        )
        addView(
            TextView(context).apply {
                text = label
                styleAs(R.style.TextAppearance_Dementor_ListItem)
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            },
        )
        addView(
            CheckBox(context).apply {
                isChecked = pkg in selected
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selected += pkg else selected -= pkg
                }
            },
        )

        setOnClickListener {
            // Tapping the row toggles too — the checkbox alone is a small target.
            (getChildAt(2) as CheckBox).toggle()
        }
    }

    override fun finish() {
        // No result unless a parent actually got in. Backing out of the PIN must
        // not hand the caller a selection to apply.
        if (authenticated) {
            setResult(
                Activity.RESULT_OK,
                Intent().putStringArrayListExtra(EXTRA_SELECTED, ArrayList(selected)),
            )
        }
        super.finish()
    }

    companion object {
        const val EXTRA_SELECTED = "selected"
        private const val EXTRA_TITLE = "title"

        fun intent(context: Context, title: String, selected: Collection<String>, childId: DeviceId? = null): Intent =
            RuleTargets.putExtra(
                Intent(context, AppPickerActivity::class.java)
                    .putExtra(EXTRA_TITLE, title)
                    .putStringArrayListExtra(EXTRA_SELECTED, ArrayList(selected)),
                childId,
            )

        fun resultFrom(data: Intent?): List<String> =
            data?.getStringArrayListExtra(EXTRA_SELECTED).orEmpty()
    }
}
