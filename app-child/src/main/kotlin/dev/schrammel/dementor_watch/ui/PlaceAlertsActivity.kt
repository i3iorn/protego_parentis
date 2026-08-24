package dev.schrammel.dementor_watch.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import dev.schrammel.dementor_watch.R
import dev.schrammel.dementor_watch.context.PlaceWatcher
import dev.schrammel.dementor_watch.rules.ContextRuleStore

/**
 * Telling the parents about arrivals (LOC-1).
 *
 * Built on the saved places the contextual rules already use, so a family names a
 * place once and can both make a rule about it and be told about arriving at it.
 *
 * The distinction this screen has to make clearly is the one the whole location
 * design rests on: an arrival sends a *name*, and never a position. Positions go
 * only when a parent asks — visibly, one at a time — or in an emergency.
 */
class PlaceAlertsActivity : ParentGatedActivity() {

    private lateinit var settings: ContextRuleStore
    private lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = ContextRuleStore(this)
        column = contentColumn()
        showColumn(column)
    }

    override fun onGatedContentVisible() = render()

    override fun onGatedContentHidden() {
        column.removeAllViews()
    }

    private fun render() {
        column.removeAllViews()
        column.addView(screenTitle(getString(R.string.rules_row_places_alerts)))
        column.addView(bodyText(getString(R.string.places_alerts_hint)))

        column.addView(
            CheckBox(this).apply {
                setText(R.string.places_alerts_enable)
                isChecked = settings.announceArrivals
                setOnCheckedChangeListener { _, wanted ->
                    settings.announceArrivals = wanted
                    // Forget where the phone was, so switching this on does not
                    // immediately announce an arrival at wherever it already is.
                    PlaceWatcher(this@PlaceAlertsActivity).reset()
                }
            },
        )

        val places = settings.places
        column.addView(sectionHeader(getString(R.string.rules_row_context)))
        if (places.isEmpty()) {
            column.addView(bodyText(getString(R.string.places_none)))
        } else {
            column.addView(group(places.map { statRow(it.name, "${it.radiusMetres} m") }))
        }

        column.addView(
            Button(this).apply {
                setText(R.string.places_title)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dimen(R.dimen.space_md) }
                setOnClickListener { startActivity(PlacesActivity.intent(context)) }
            },
        )
    }

    companion object {
        fun intent(context: Context) = Intent(context, PlaceAlertsActivity::class.java)
    }
}
