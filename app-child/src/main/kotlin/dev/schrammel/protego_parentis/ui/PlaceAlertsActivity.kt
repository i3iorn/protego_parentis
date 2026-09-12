package dev.schrammel.protego_parentis.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.context.PlaceWatcher
import dev.schrammel.protego_parentis.rules.ContextRuleStore
import dev.schrammel.protego_parentis.sync.core.DeviceId
import dev.schrammel.protego_parentis.ui.rules.placeRuleStore
import dev.schrammel.protego_parentis.ui.rules.RuleScope
import dev.schrammel.protego_parentis.ui.rules.RuleTargets

/**
 * Telling the parents about arrivals (LOC-1).
 *
 * Built on the saved places the contextual rules already use, so a family names a
 * place once and can both make a rule about it and be told about arriving at it.
 *
 * The distinction this screen has to make clearly is the one the whole location
 * design rests on: an arrival sends a *name*, and never a position. Positions go
 * only when a parent asks — visibly, one at a time — or in an emergency.
 *
 * The announcement toggle is always local — [ContextRuleStore] directly,
 * never [target] — because it describes what *this* device tells a parent
 * about itself, which [RulesActivity] only ever offers on its own device
 * (#86). The saved places list, though, now reads and links onward through
 * [target] the same way [ContextRulesActivity]'s "Manage places" button
 * does (#71): the two doors into [PlacesActivity] used to diverge — one
 * always local by fiat, one target-aware — for no reason other than having
 * been written separately. Converging them here means this door resolves
 * to exactly the same place the other one already did, rather than merely
 * behaving the same by coincidence of always being reached on the managed
 * phone's own screen.
 */
class PlaceAlertsActivity : ParentGatedActivity() {

    private lateinit var target: RuleScope
    private lateinit var settings: ContextRuleStore
    private lateinit var column: LinearLayout

    override fun supportsReadOnly() = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val resolved = RuleTargets.resolve(this) ?: return finish()
        target = resolved
        settings = ContextRuleStore(this)
        column = contentColumn()
        showColumn(column)
    }

    override fun onGatedContentVisible() {
        if (!requireStillInFamily(target)) return
        render()
    }

    private fun render() {
        column.removeAllViews()
        column.addView(screenTitle(getString(R.string.rules_row_places_alerts)))
        column.addView(bodyText(getString(R.string.places_alerts_hint)))
        if (!canEdit) column.addView(readOnlyBanner { requestUnlock { onGatedContentVisible() } })

        column.addView(
            CheckBox(this).apply {
                setText(R.string.places_alerts_enable)
                isChecked = settings.announceArrivals
                isEnabled = canEdit
                setOnCheckedChangeListener { _, wanted ->
                    settings.announceArrivals = wanted
                    // Forget where the phone was, so switching this on does not
                    // immediately announce an arrival at wherever it already is.
                    PlaceWatcher(this@PlaceAlertsActivity).reset()
                }
            },
        )

        val places = placeRuleStore(this, target).read()
        column.addView(sectionHeader(getString(R.string.rules_row_context)))
        if (places.isEmpty()) {
            column.addView(bodyText(getString(R.string.places_none)))
        } else {
            column.addView(group(places.map { statRow(it.name, "${it.radiusMetres} m") }))
        }

        column.addView(
            actionButton().apply {
                setText(R.string.places_title)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dimen(R.dimen.space_md) }
                setOnClickListener { startActivity(PlacesActivity.intent(this@PlaceAlertsActivity, target.childId)) }
            },
        )
    }

    companion object {
        fun intent(context: Context, childId: DeviceId? = null) =
            RuleTargets.putExtra(Intent(context, PlaceAlertsActivity::class.java), childId)
    }
}
