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
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.audit.Audit
import dev.schrammel.protego_parentis.model.AuditAction
import dev.schrammel.protego_parentis.model.AuditActor
import dev.schrammel.protego_parentis.model.CategoryId
import dev.schrammel.protego_parentis.model.Condition
import dev.schrammel.protego_parentis.model.DeviceTag
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.ContextRule
import dev.schrammel.protego_parentis.model.Place
import dev.schrammel.protego_parentis.model.PlaceId
import dev.schrammel.protego_parentis.model.PlaceRef
import dev.schrammel.protego_parentis.model.RuleEffect
import dev.schrammel.protego_parentis.model.RuleTarget
import dev.schrammel.protego_parentis.model.WeeklyBudget
import dev.schrammel.protego_parentis.rules.ContextRuleStore
import dev.schrammel.protego_parentis.sync.DeviceId
import dev.schrammel.protego_parentis.ui.rules.categories
import dev.schrammel.protego_parentis.ui.rules.contextRuleRuleStore
import dev.schrammel.protego_parentis.ui.rules.delete
import dev.schrammel.protego_parentis.ui.rules.find
import dev.schrammel.protego_parentis.ui.rules.placeRuleStore
import dev.schrammel.protego_parentis.ui.rules.policyRuleStore
import dev.schrammel.protego_parentis.ui.rules.upsert
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

/**
 * Builds one rule, read as a sentence: *when this, do that, to those* — on
 * whichever kind of phone edits [target]'s rules (#43).
 *
 * The three parts are chosen separately rather than offered as a list of preset
 * rules, because the useful combinations are family-specific. "Block games on the
 * school Wi-Fi" and "let homework run freely at Grandma's" are the same shape.
 */
class ContextRuleEditActivity : ParentGatedActivity() {

    private lateinit var target: dev.schrammel.protego_parentis.ui.RuleTarget
    private lateinit var container: LinearLayout

    private var ruleId: String = ""
    private var name: String = ""
    private var enabled: Boolean = true
    private var negate: Boolean = false
    private var condition: Condition = Condition.Always
    private var effect: RuleEffect = RuleEffect.Block
    private var ruleTarget: RuleTarget = RuleTarget.Everything

    private val pickApps = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val picked = AppPickerActivity.resultFrom(result.data)
        if (picked.isNotEmpty()) {
            ruleTarget = RuleTarget.Apps(picked.map(::PackageName).toSet())
        }
        render()
    }

    override fun supportsReadOnly() = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val resolved = dev.schrammel.protego_parentis.ui.RuleTargets.resolve(this) ?: return finish()
        target = resolved

        val existing = intent.getStringExtra(EXTRA_ID)
            ?.let { id -> contextRuleRuleStore(this, target).find(id) }
        ruleId = existing?.id ?: UUID.randomUUID().toString()
        name = existing?.name.orEmpty()
        enabled = existing?.enabled ?: true
        effect = existing?.effect ?: RuleEffect.Block
        ruleTarget = existing?.target ?: RuleTarget.Everything
        // A negated condition is stored wrapped; unwrap so the toggle reflects it.
        val stored = existing?.condition ?: Condition.Always
        negate = stored is Condition.Not
        condition = if (stored is Condition.Not) stored.condition else stored

        container = contentColumn()
        showColumn(container)
    }

    override fun onGatedContentVisible() = render()

    private fun render() {
        container.removeAllViews()
        container.addView(heading(getString(R.string.context_edit_title), R.style.TextAppearance_Dementor_Title))
        if (!canEdit) container.addView(readOnlyBanner { requestUnlock { onGatedContentVisible() } })

        container.addView(
            EditText(this).apply {
                hint = getString(R.string.context_name_hint)
                setText(name)
                setSingleLine()
                isEnabled = canEdit
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                addTextChangedListener(SimpleWatcher { name = it })
            },
        )
        container.addView(
            CheckBox(this).apply {
                setText(R.string.context_enabled)
                isChecked = enabled
                isEnabled = canEdit
                setOnCheckedChangeListener { _, checked -> enabled = checked }
            },
        )

        // ---- when ----
        container.addView(heading(getString(R.string.context_when_header), R.style.TextAppearance_Dementor_Section))
        container.addView(choice(describeCondition()) { chooseCondition() })
        container.addView(
            CheckBox(this).apply {
                setText(R.string.context_invert)
                isChecked = negate
                isEnabled = canEdit
                setOnCheckedChangeListener { _, checked ->
                    negate = checked
                    render()
                }
            },
        )

        // ---- do what ----
        container.addView(heading(getString(R.string.context_do_header), R.style.TextAppearance_Dementor_Section))
        container.addView(choice(describeEffect()) { chooseEffect() })

        // ---- to whom ----
        container.addView(heading(getString(R.string.context_to_header), R.style.TextAppearance_Dementor_Section))
        container.addView(choice(describeTarget()) { chooseTarget() })

        if (canEdit) {
            container.addView(
                Button(this).apply {
                    setText(R.string.context_save)
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                        .apply { topMargin = dimen(R.dimen.space_xl) }
                    setOnClickListener { save() }
                },
            )
            if (intent.hasExtra(EXTRA_ID)) {
                container.addView(
                    Button(this).apply {
                        setText(R.string.context_delete)
                        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                        setOnClickListener {
                            val deleted = name
                            contextRuleRuleStore(this@ContextRuleEditActivity, target).delete(ruleId)
                            Audit.record(
                                context = this@ContextRuleEditActivity,
                                actor = AuditActor.PARENT,
                                action = AuditAction.CONTEXT_RULE_DELETED,
                                subject = deleted,
                            )
                            finish()
                        }
                    },
                )
            }
        }
    }

    private fun places(): List<PlaceRef> = placeRuleStore(this, target).read()

    // ---------- choosing ----------

    private fun chooseCondition() {
        if (!canEdit) return
        val places = places()
        val options = buildList {
            add(getString(R.string.context_when_always) to { condition = Condition.Always; render() })
            add(getString(R.string.context_choose_wifi) to { askForWifi() })
            places.forEach { place ->
                add(
                    getString(R.string.context_when_place, place.name) to {
                        condition = Condition.AtPlace(place.id)
                        render()
                    },
                )
            }
            add(getString(R.string.context_choose_device) to { chooseDevice() })
            add(getString(R.string.context_choose_battery) to { askForBattery() })
        }
        menu(R.string.context_when_header, options)
    }

    /** A percentage rather than free text — there is no wrong value to type. */
    private fun askForBattery() {
        val existing = (condition as? Condition.BatteryBelow)?.percent ?: DEFAULT_BATTERY_PERCENT
        sliderDialog(
            title = R.string.context_choose_battery,
            max = MAX_BATTERY_PERCENT - 1,
            initialProgress = existing - 1,
            format = { getString(R.string.context_when_battery, it + 1) },
        ) { progress ->
            condition = Condition.BatteryBelow(progress + 1)
            render()
        }
    }

    /**
     * Offers the network the phone is on right now, since that is almost always
     * the one being written a rule about, but allows typing another.
     */
    @SuppressLint("MissingPermission")
    private fun askForWifi() {
        requestLocationIfNeeded()
        val current = dev.schrammel.protego_parentis.context.ContextProvider(this)
            .current(emptyList()).wifiSsid

        promptForText(
            R.string.context_choose_wifi,
            R.string.context_wifi_hint,
            prefill = current.orEmpty(),
        ) { ssid ->
            condition = Condition.OnWifi(ssid)
            render()
        }
    }

    private fun chooseDevice() {
        requestBluetoothIfNeeded()
        promptForText(
            R.string.context_choose_device,
            R.string.context_device_hint,
            message = getString(R.string.context_device_explainer),
        ) { tag ->
            condition = Condition.NearDevice(DeviceTag(tag))
            render()
        }
    }

    private fun chooseEffect() {
        if (!canEdit) return
        menu(
            R.string.context_do_header,
            listOf(
                getString(R.string.context_effect_block) to { effect = RuleEffect.Block; render() },
                getString(R.string.context_effect_allow) to { effect = RuleEffect.Allow; render() },
                getString(R.string.context_choose_budget) to { askForBudget() },
            ),
        )
    }

    private fun askForBudget() {
        sliderDialog(
            title = R.string.context_choose_budget,
            max = LimitSlider.MAX_STEPS,
            initialProgress = LimitSlider.progressFor(DEFAULT_BUDGET_MINUTES),
            format = { formatLimit(LimitSlider.minutesFor(it)) },
        ) { progress ->
            effect = RuleEffect.SetDeviceBudget(
                WeeklyBudget.flat(LimitSlider.minutesFor(progress).minutes),
            )
            render()
        }
    }

    private fun chooseTarget() {
        if (!canEdit) return
        menu(
            R.string.context_to_header,
            listOf(
                getString(R.string.context_target_everything) to {
                    ruleTarget = RuleTarget.Everything
                    render()
                },
                getString(R.string.context_choose_categories) to { chooseCategories() },
                getString(R.string.context_choose_apps) to {
                    pickApps.launch(
                        AppPickerActivity.intent(
                            this,
                            getString(R.string.context_choose_apps),
                            (ruleTarget as? RuleTarget.Apps)?.packages?.map { it.value }.orEmpty(),
                            target.childId,
                        ),
                    )
                },
            ),
        )
    }

    private fun chooseCategories() {
        val categories = policyRuleStore(this, target).categories()
        if (categories.isEmpty()) {
            toast(getString(R.string.rules_no_categories))
            return
        }
        val selected = (ruleTarget as? RuleTarget.Categories)?.categories?.map { it.value }.orEmpty()
        val checked = categories.map { it.id in selected }.toBooleanArray()
        val chosen = selected.toMutableSet()

        AlertDialog.Builder(this)
            .setTitle(R.string.context_choose_categories)
            .setMultiChoiceItems(
                categories.map { it.name }.toTypedArray(),
                checked,
            ) { _, index, isChecked ->
                val id = categories[index].id
                if (isChecked) chosen += id else chosen -= id
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                ruleTarget = RuleTarget.Categories(chosen.map(::CategoryId).toSet())
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun save() {
        if (name.isBlank()) {
            toast(getString(R.string.context_name_required))
            return
        }
        contextRuleRuleStore(this, target).upsert(
            ContextRule(
                id = ruleId,
                name = name.trim(),
                enabled = enabled,
                condition = if (negate) Condition.Not(condition) else condition,
                effect = effect,
                target = ruleTarget,
            ),
        )
        Audit.record(
            context = this,
            actor = AuditActor.PARENT,
            action = AuditAction.CONTEXT_RULE_SAVED,
            subject = name.trim(),
        )
        finish()
    }

    // ---------- permissions ----------

    /**
     * Asked for only when a rule actually needs it, rather than at first launch.
     * A permission prompt with no visible reason is one people refuse.
     */
    private fun requestLocationIfNeeded() {
        if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) return
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
            REQUEST_LOCATION,
        )
    }

    private fun requestBluetoothIfNeeded() {
        if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
            REQUEST_BLUETOOTH,
        )
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    // ---------- descriptions ----------

    private fun describeCondition(): String {
        val base = when (val current = condition) {
            is Condition.Always -> getString(R.string.context_when_always)
            is Condition.OnWifi -> getString(R.string.context_when_wifi, current.ssid)
            is Condition.NearDevice -> getString(R.string.context_when_near, current.tag.value)
            is Condition.AtPlace -> getString(
                R.string.context_when_place,
                places().firstOrNull { it.id == current.place }?.name
                    ?: getString(R.string.context_place_unknown),
            )

            is Condition.BatteryBelow -> getString(R.string.context_when_battery, current.percent)

            else -> getString(R.string.context_when_always)
        }
        return if (negate) getString(R.string.context_when_not, base) else base
    }

    private fun describeEffect(): String = when (val current = effect) {
        is RuleEffect.Block -> getString(R.string.context_effect_block)
        is RuleEffect.Allow -> getString(R.string.context_effect_allow)
        is RuleEffect.SetDeviceBudget ->
            getString(R.string.context_effect_budget, formatDuration(current.budget.default))
    }

    private fun describeTarget(): String = when (val current = ruleTarget) {
        is RuleTarget.Everything -> getString(R.string.context_target_everything)
        is RuleTarget.Apps -> resources.getQuantityString(
            R.plurals.rules_category_apps,
            current.packages.size,
            current.packages.size,
        )

        is RuleTarget.Categories -> current.categories
            .mapNotNull { id -> policyRuleStore(this, target).categories().firstOrNull { it.id == id.value }?.name }
            .joinToString(", ")
            .ifBlank { getString(R.string.context_target_categories) }
    }

    // ---------- view helpers ----------

    private fun menu(titleRes: Int, options: List<Pair<String, () -> Unit>>) {
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setItems(options.map { it.first }.toTypedArray()) { _, index -> options[index].second() }
            .show()
    }

    private fun choice(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isEnabled = canEdit
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        setOnClickListener { onClick() }
    }

    private fun heading(text: String, appearance: Int) = TextView(this).apply {
        this.text = text
        styleAs(appearance)
        setPadding(0, dimen(R.dimen.section_spacing), 0, dimen(R.dimen.space_xxs))
    }

    companion object {
        private const val EXTRA_ID = "ruleId"
        private const val REQUEST_LOCATION = 10
        private const val REQUEST_BLUETOOTH = 11
        private const val DEFAULT_BUDGET_MINUTES = 30
        private const val DEFAULT_BATTERY_PERCENT = 20
        private const val MAX_BATTERY_PERCENT = 100

        fun intent(context: Context, ruleId: String? = null, childId: DeviceId? = null): Intent =
            dev.schrammel.protego_parentis.ui.RuleTargets.putExtra(
                Intent(context, ContextRuleEditActivity::class.java)
                    .apply { ruleId?.let { putExtra(EXTRA_ID, it) } },
                childId,
            )
    }
}

/**
 * Names the places rules can refer to — on whichever kind of phone edits
 * [target]'s rules (#43), with one structural asymmetry: creating a *new*
 * place, or moving one, still only ever happens on the managed phone itself
 * ([PlaceRuleStore] deliberately has no such operation for a remote target —
 * see its own doc comment). A parent's phone can rename, resize or remove a
 * place a managed phone has already created, never add one.
 */
class PlacesActivity : ParentGatedActivity() {

    private lateinit var target: dev.schrammel.protego_parentis.ui.RuleTarget
    private lateinit var container: LinearLayout

    override fun supportsReadOnly() = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val resolved = dev.schrammel.protego_parentis.ui.RuleTargets.resolve(this) ?: return finish()
        target = resolved

        container = contentColumn()
        showColumn(container)
    }

    override fun onGatedContentVisible() = render()

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
        val store = ContextRuleStore(this)
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
        places.forEach { place ->
            container.addView(
                TextView(this).apply {
                    text = getString(R.string.places_row, place.name, place.radiusMetres)
                    styleAs(R.style.TextAppearance_Dementor_ListItem)
                    setPadding(0, dimen(R.dimen.space_sm), 0, dimen(R.dimen.space_sm))
                    if (canEdit) setOnClickListener { confirmDelete(store, place) }
                },
            )
            if (place.id in clampedIds) {
                container.addView(
                    TextView(this).apply {
                        setText(R.string.places_radius_increased)
                        styleAs(R.style.TextAppearance_Dementor_Caption)
                        setPadding(0, 0, 0, dimen(R.dimen.space_sm))
                    },
                )
            }
        }

        if (canEdit) {
            container.addView(
                Button(this).apply {
                    setText(R.string.places_add_here)
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                        .apply { topMargin = dimen(R.dimen.space_lg) }
                    setOnClickListener { addHere(store) }
                },
            )
            container.addView(
                Button(this).apply {
                    setText(R.string.places_add_by_coordinates)
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                        .apply { topMargin = dimen(R.dimen.space_sm) }
                    setOnClickListener { addByCoordinates(store) }
                },
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun addHere(store: ContextRuleStore) {
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
            store.upsertPlace(
                Place(
                    id = PlaceId(UUID.randomUUID().toString()),
                    name = name,
                    latitude = fix.latitude,
                    longitude = fix.longitude,
                    radiusMetres = DEFAULT_RADIUS_METRES,
                ),
            )
            render()
        }
    }

    /**
     * Names a place you are not standing in — "school", added from the kitchen
     * table (#31). Coordinates are typed rather than picked from a map: a map
     * means a tile-fetching third-party service, which the README's "no
     * third-party SDKs" promise rules out (see the issue's own discussion for
     * why). [checkOnMap] hands off to whatever map app is already installed
     * instead, so a typo is still catchable before saving.
     */
    private fun addByCoordinates(store: ContextRuleStore) {
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
                addByCoordinatesConfirmed(store, nameInput, latInput, lonInput, radiusInput, dialog)
            }
        }
        dialog.show()
    }

    private fun addByCoordinatesConfirmed(
        store: ContextRuleStore,
        nameInput: EditText,
        latInput: EditText,
        lonInput: EditText,
        radiusInput: EditText,
        dialog: AlertDialog,
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
        AlertDialog.Builder(this)
            .setTitle(place.name)
            // Rules referring to it go too, rather than being left permanently
            // unsatisfiable.
            .setMessage(R.string.places_delete_warning)
            .setPositiveButton(R.string.places_delete) { _, _ ->
                store.deletePlace(place.id)
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------- a parent editing another device's places: rename, resize, delete only ----------

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
        refs.forEach { ref ->
            container.addView(
                TextView(this).apply {
                    text = getString(R.string.places_row, ref.name, ref.radiusMetres)
                    styleAs(R.style.TextAppearance_Dementor_ListItem)
                    setPadding(0, dimen(R.dimen.space_sm), 0, dimen(R.dimen.space_sm))
                    if (canEdit) setOnClickListener { editRemotePlace(store, ref) }
                },
            )
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
                    2 -> AlertDialog.Builder(this)
                        .setTitle(ref.name)
                        .setMessage(R.string.places_delete_warning)
                        .setPositiveButton(R.string.places_delete) { _, _ ->
                            store.delete(ref.id)
                            render()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
            .show()
    }

    companion object {
        private const val REQUEST_LOCATION = 12
        private const val DEFAULT_RADIUS_METRES = 150
        private const val MAX_RADIUS_METRES = 5000

        fun intent(context: Context, childId: DeviceId? = null) =
            dev.schrammel.protego_parentis.ui.RuleTargets.putExtra(Intent(context, PlacesActivity::class.java), childId)
    }
}
