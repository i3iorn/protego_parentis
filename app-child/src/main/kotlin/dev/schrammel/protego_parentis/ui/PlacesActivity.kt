package dev.schrammel.protego_parentis.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dev.schrammel.protego_parentis.AppRegistry
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.model.Place
import dev.schrammel.protego_parentis.model.PlaceId
import dev.schrammel.protego_parentis.model.PlaceRef
import dev.schrammel.protego_parentis.rules.ContextRuleStore
import dev.schrammel.protego_parentis.sync.SyncGraph
import dev.schrammel.protego_parentis.sync.core.DeviceId
import dev.schrammel.protego_parentis.ui.rules.RuleScope
import dev.schrammel.protego_parentis.ui.rules.RuleTargets
import dev.schrammel.protego_parentis.ui.rules.placeRuleStore
import java.util.UUID

/**
 * Names the places rules can refer to — on whichever kind of phone edits
 * [target]'s rules (#43). Renaming, resizing or deleting an existing place
 * flows the same way on both: [PlaceRuleStore] edits it in place, with no
 * coordinate ever involved. Creating one is the one asymmetry, and it is
 * about who *holds* the new coordinate rather than who has one worth saving:
 * [renderLocal] writes straight into [ContextRuleStore], while [renderRemote]
 * — wherever the phone is standing right now, or typed by hand for somewhere
 * neither phone is — always goes out as a
 * [dev.schrammel.protego_parentis.sync.core.SyncMessageType.PLACE_CREATE_REQUESTED]
 * for the managed phone to actually create.
 */
class PlacesActivity : ParentGatedActivity() {

    private lateinit var target: RuleScope
    private lateinit var container: LinearLayout

    override fun supportsReadOnly() = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val resolved = RuleTargets.resolve(this) ?: return finish()
        target = resolved

        container = contentColumn()
        showColumn(container)
    }

    override fun onGatedContentVisible() {
        if (!requireStillInFamily(target)) return
        render()
    }

    private fun render() {
        container.removeAllViews()
        container.addView(
            TextView(this).apply {
                setText(R.string.places_title)
                styleAs(R.style.TextAppearance_Dementor_Title)
            },
        )
        container.addView(
            TextView(this).apply {
                setText(R.string.places_explainer)
                styleAs(R.style.TextAppearance_Dementor_Caption)
            },
        )
        if (!canEdit) container.addView(readOnlyBanner { requestUnlock { onGatedContentVisible() } })

        if (target.isOwnDevice) renderLocal() else renderRemote()
    }

    // ---------- the managed phone editing its own places: unchanged, coordinates and all ----------

    private fun renderLocal() {
        val store = AppRegistry.childStores.contextRules
        val places = store.places
        val clampedIds = store.clampedPlaceIds
        if (places.isEmpty()) {
            container.addView(
                TextView(this).apply {
                    setText(R.string.places_none)
                    styleAs(R.style.TextAppearance_Dementor_Body)
                    setPadding(0, dimen(R.dimen.space_md), 0, 0)
                },
            )
        }
        addCollapsibleRows(
            container,
            places.map { place ->
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(
                        TextView(context).apply {
                            text = getString(R.string.places_row, place.name, place.radiusMetres)
                            styleAs(R.style.TextAppearance_Dementor_ListItem)
                            setPadding(0, dimen(R.dimen.space_sm), 0, dimen(R.dimen.space_sm))
                            if (canEdit) setOnClickListener { confirmDelete(store, place) }
                        },
                    )
                    if (place.id in clampedIds) {
                        addView(
                            TextView(context).apply {
                                setText(R.string.places_radius_increased)
                                styleAs(R.style.TextAppearance_Dementor_Caption)
                                setPadding(0, 0, 0, dimen(R.dimen.space_sm))
                            },
                        )
                    }
                }
            },
        )

        if (canEdit) {
            container.addView(
                Button(this).apply {
                    setText(R.string.places_add_here)
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                        .apply { topMargin = dimen(R.dimen.space_lg) }
                    setOnClickListener {
                        addMyPosition { name, latitude, longitude ->
                            store.upsertPlace(
                                Place(
                                    id = PlaceId(UUID.randomUUID().toString()),
                                    name = name,
                                    latitude = latitude,
                                    longitude = longitude,
                                    radiusMetres = DEFAULT_RADIUS_METRES,
                                ),
                            )
                            render()
                        }
                    }
                },
            )
            container.addView(
                Button(this).apply {
                    setText(R.string.places_add_by_coordinates)
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                        .apply { topMargin = dimen(R.dimen.space_sm) }
                    setOnClickListener {
                        addByCoordinates { name, latitude, longitude, radiusMetres ->
                            store.upsertPlace(
                                Place(
                                    id = PlaceId(UUID.randomUUID().toString()),
                                    name = name,
                                    latitude = latitude,
                                    longitude = longitude,
                                    radiusMetres = radiusMetres,
                                ),
                            )
                            render()
                        }
                    }
                },
            )
        }
    }

    /**
     * Names *this* phone's current position — the managed phone's own, in
     * [renderLocal], or a parent's, in [addPositionRemotely]. Either way it is
     * a fix taken on whichever phone is running this code right now, never
     * one requested from, or sent to, another device: [onConfirmed] is what
     * decides whether that becomes a local place or a
     * [dev.schrammel.protego_parentis.sync.core.SyncMessageType.PLACE_CREATE_REQUESTED]
     * asking the managed phone to create one from it.
     */
    @SuppressLint("MissingPermission")
    private fun addMyPosition(onConfirmed: (name: String, latitude: Double, longitude: Double) -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                REQUEST_LOCATION,
            )
            return
        }

        val manager = getSystemService(LocationManager::class.java)
        val fix = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .mapNotNull { runCatching { manager?.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }

        if (fix == null) {
            Toast.makeText(this, R.string.places_no_fix, Toast.LENGTH_LONG).show()
            return
        }

        promptForText(
            R.string.places_add_here,
            R.string.places_name_hint,
            positiveText = R.string.places_add_here,
        ) { name ->
            onConfirmed(name, fix.latitude, fix.longitude)
        }
    }

    /**
     * Names a place you are not standing in — "school", added from the kitchen
     * table (#31). Coordinates are typed rather than picked from a map: a map
     * means a tile-fetching third-party service, which the README's "no
     * third-party SDKs" promise rules out (see the issue's own discussion for
     * why). [checkOnMap] hands off to whatever map app is already installed
     * instead, so a typo is still catchable before saving.
     *
     * [onConfirmed] runs once every field has passed validation: on the
     * managed phone itself that means writing the place straight into
     * [ContextRuleStore], and from a parent's phone it means asking the
     * managed phone to create it instead — see [addPlaceRemotely].
     */
    private fun addByCoordinates(onConfirmed: (name: String, latitude: Double, longitude: Double, radiusMetres: Int) -> Unit) {
        val nameInput = EditText(this).apply {
            hint = getString(R.string.places_name_hint)
            setSingleLine()
        }
        val latInput = EditText(this).apply {
            hint = getString(R.string.places_lat_hint)
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_DECIMAL or
                InputType.TYPE_NUMBER_FLAG_SIGNED
            setSingleLine()
        }
        val lonInput = EditText(this).apply {
            hint = getString(R.string.places_lon_hint)
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_DECIMAL or
                InputType.TYPE_NUMBER_FLAG_SIGNED
            setSingleLine()
        }
        val radiusInput = EditText(this).apply {
            hint = getString(R.string.places_radius_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(DEFAULT_RADIUS_METRES.toString())
            setSingleLine()
        }
        val checkOnMapButton = Button(this).apply {
            setText(R.string.places_check_map)
            setOnClickListener { checkOnMap(latInput, lonInput) }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dimen(R.dimen.screen_padding)
            setPadding(pad, pad, pad, 0)
            addView(nameInput)
            addView(latInput)
            addView(lonInput)
            addView(radiusInput)
            addView(checkOnMapButton)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.places_add_by_coordinates)
            .setView(content)
            .setPositiveButton(R.string.places_add_by_coordinates, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        // A plain setPositiveButton closure dismisses on any input, valid or
        // not. Wiring the click after the dialog is showing is what lets an
        // invalid field Toast and leave the dialog open instead.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                addByCoordinatesConfirmed(nameInput, latInput, lonInput, radiusInput, dialog, onConfirmed)
            }
        }
        dialog.show()
    }

    private fun addByCoordinatesConfirmed(
        nameInput: EditText,
        latInput: EditText,
        lonInput: EditText,
        radiusInput: EditText,
        dialog: AlertDialog,
        onConfirmed: (name: String, latitude: Double, longitude: Double, radiusMetres: Int) -> Unit,
    ) {
        val name = nameInput.text.toString().trim()
        val latitude = latInput.text.toString().toDoubleOrNull()
        val longitude = lonInput.text.toString().toDoubleOrNull()
        val radiusMetres = radiusInput.text.toString().toIntOrNull()

        if (name.isEmpty()) {
            Toast.makeText(this, R.string.places_name_required, Toast.LENGTH_LONG).show()
            return
        }
        if (latitude == null || latitude !in -90.0..90.0 || longitude == null || longitude !in -180.0..180.0) {
            Toast.makeText(this, R.string.places_bad_coordinates, Toast.LENGTH_LONG).show()
            return
        }
        if (radiusMetres == null || radiusMetres < Place.MINIMUM_RADIUS_METRES) {
            Toast.makeText(
                this,
                getString(R.string.places_bad_radius, Place.MINIMUM_RADIUS_METRES),
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        onConfirmed(name, latitude, longitude, radiusMetres)
        dialog.dismiss()
    }

    /** Hands off to whatever map app is installed, so a typo is catchable before saving. */
    private fun checkOnMap(latInput: EditText, lonInput: EditText) {
        val latitude = latInput.text.toString().toDoubleOrNull()
        val longitude = lonInput.text.toString().toDoubleOrNull()
        if (latitude == null || latitude !in -90.0..90.0 || longitude == null || longitude !in -180.0..180.0) {
            Toast.makeText(this, R.string.places_bad_coordinates, Toast.LENGTH_LONG).show()
            return
        }

        val uri = Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.places_no_map_app, Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmDelete(store: ContextRuleStore, place: Place) {
        // Rules referring to it go too, rather than being left permanently
        // unsatisfiable.
        confirmDialog(place.name, getString(R.string.places_delete_warning), R.string.places_delete) {
            store.deletePlace(place.id)
            render()
        }
    }

    // ---------- a parent editing another device's places: rename/resize/delete, plus creating one remotely ----------

    private fun renderRemote() {
        val store = placeRuleStore(this, target)
        val refs = store.read()
        if (refs.isEmpty()) {
            container.addView(
                TextView(this).apply {
                    setText(R.string.places_none)
                    styleAs(R.style.TextAppearance_Dementor_Body)
                    setPadding(0, dimen(R.dimen.space_md), 0, 0)
                },
            )
        }
        addCollapsibleRows(
            container,
            refs.map { ref ->
                TextView(this).apply {
                    text = getString(R.string.places_row, ref.name, ref.radiusMetres)
                    styleAs(R.style.TextAppearance_Dementor_ListItem)
                    setPadding(0, dimen(R.dimen.space_sm), 0, dimen(R.dimen.space_sm))
                    if (canEdit) setOnClickListener { editRemotePlace(store, ref) }
                }
            },
        )

        if (canEdit) {
            container.addView(
                Button(this).apply {
                    setText(R.string.places_add_my_position)
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                        .apply { topMargin = dimen(R.dimen.space_lg) }
                    setOnClickListener { addPositionRemotely() }
                },
            )
            container.addView(
                Button(this).apply {
                    setText(R.string.places_add_by_coordinates)
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                        .apply { topMargin = dimen(R.dimen.space_sm) }
                    setOnClickListener { addPlaceRemotely() }
                },
            )
        }
    }

    /**
     * Asks the managed phone to create a place at *this* — the parent's own —
     * current position, for the ordinary case of naming somewhere the parent
     * is standing and the child is not, like a grandparent's house on a
     * visit. [addMyPosition] takes the fix on whichever phone calls it, so
     * this reaches the parent's own [LocationManager], never the managed
     * phone's — that's [dev.schrammel.protego_parentis.enforcement.EnforcementService]'s
     * territory, and unrelated to this button entirely.
     */
    private fun addPositionRemotely() {
        addMyPosition { name, latitude, longitude ->
            SyncGraph.get(this)
                .publishPlaceCreateRequest(name, latitude, longitude, DEFAULT_RADIUS_METRES)
            Toast.makeText(this, R.string.places_create_requested, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Asks the managed phone to create a place at coordinates typed here,
     * for a place neither phone is currently standing in — [PlaceRuleStore]
     * has nothing between "read" and "rename/resize/delete" for exactly that
     * reason, and [addPositionRemotely] only ever offers wherever the parent
     * already is. The new place will not appear in [renderRemote]'s list
     * until the managed phone confirms it and that reaches this phone
     * through the ordinary place-ref replication, so this only tells the
     * parent the request was sent, not that it landed.
     */
    private fun addPlaceRemotely() {
        addByCoordinates { name, latitude, longitude, radiusMetres ->
            SyncGraph.get(this).publishPlaceCreateRequest(name, latitude, longitude, radiusMetres)
            Toast.makeText(this, R.string.places_create_requested, Toast.LENGTH_LONG).show()
        }
    }

    private fun editRemotePlace(store: dev.schrammel.protego_parentis.ui.rules.PlaceRuleStore, ref: PlaceRef) {
        AlertDialog.Builder(this)
            .setTitle(ref.name)
            .setItems(
                arrayOf(
                    getString(R.string.places_rename),
                    getString(R.string.places_resize),
                    getString(R.string.places_delete),
                ),
            ) { _, index ->
                when (index) {
                    0 -> promptForText(R.string.places_rename, R.string.places_name_hint, prefill = ref.name) { name ->
                        store.rename(ref.id, name)
                        render()
                    }
                    1 -> sliderDialog(
                        title = R.string.places_resize,
                        max = MAX_RADIUS_METRES,
                        initialProgress = ref.radiusMetres,
                        format = { getString(R.string.places_radius_value, it) },
                    ) { radius ->
                        store.resize(ref.id, radius.coerceAtLeast(Place.MINIMUM_RADIUS_METRES))
                        render()
                    }
                    2 -> confirmDialog(ref.name, getString(R.string.places_delete_warning), R.string.places_delete) {
                        store.delete(ref.id)
                        render()
                    }
                }
            }
            .show()
    }

    companion object {
        private const val REQUEST_LOCATION = 12
        private const val DEFAULT_RADIUS_METRES = 150
        private const val MAX_RADIUS_METRES = 5000

        fun intent(context: Context, childId: DeviceId? = null) =
            RuleTargets.putExtra(Intent(context, PlacesActivity::class.java), childId)
    }
}
