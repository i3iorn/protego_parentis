package dev.schrammel.dementor_watch.ui

import android.app.Activity
import android.app.TimePickerDialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import dev.schrammel.dementor_watch.R
import dev.schrammel.dementor_watch.enforcement.EnforcementCoordinator
import dev.schrammel.dementor_watch.enforcement.EnforcementService
import dev.schrammel.dementor_watch.model.PackageName
import dev.schrammel.dementor_watch.rules.LocalPolicyStore
import dev.schrammel.dementor_watch.security.PinLock
import dev.schrammel.dementor_watch.usage.InstalledApps
import java.time.LocalTime

/**
 * Local rules editor, behind the parent's PIN.
 *
 * Stands in for the parent app until the server exists — the same policy fields,
 * edited on the device instead of pushed to it. Changes save as you make them and
 * bump the policy version, which is what makes the service re-evaluate.
 */
class RulesActivity : AppCompatActivity() {

    private lateinit var store: LocalPolicyStore
    private lateinit var installedApps: InstalledApps
    private var unlocked = false

    private val unlock = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            unlocked = true
            showContent()
        } else {
            // Cancelled at the PIN screen: nothing to edit, so back out entirely.
            finish()
        }
    }

    private val pickExempt = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        store.exemptPackages = AppPickerActivity.resultFrom(result.data).toSet()
        renderExempt()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rules)
        store = LocalPolicyStore(this)
        installedApps = InstalledApps(this)

        setUpDailyLimit()
        setUpBedtime()
        setUpCategories()
        findViewById<Button>(R.id.open_app_limits).setOnClickListener {
            startActivity(AppLimitsActivity.intent(this))
        }
        findViewById<Button>(R.id.open_bonus).setOnClickListener {
            startActivity(BonusTimeActivity.intent(this))
        }
        setUpExempt()
        setUpPin()
        setUpDeviceControl()

        findViewById<Button>(R.id.done).setOnClickListener {
            // Push the new rules through immediately rather than waiting for the
            // next tick, so the effect of a change is visible at once.
            EnforcementService.start(this)
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        if (unlocked) {
            // Coming back from the category editor; the list may have changed.
            renderCategories()
            renderExempt()
        } else {
            findViewById<View>(R.id.rules_content).visibility = View.GONE
            unlock.launch(PinActivity.intent(this))
        }
    }

    private fun showContent() {
        findViewById<View>(R.id.rules_content).visibility = View.VISIBLE
        renderCategories()
        renderExempt()
    }

    // ---------- daily limit ----------

    private fun setUpDailyLimit() {
        val value = findViewById<TextView>(R.id.limit_value)
        val bar = findViewById<SeekBar>(R.id.limit_bar)

        value.text = Format.limit(store.dailyLimitMinutes)
        bar.progress = LimitSlider.progressFor(store.dailyLimitMinutes)

        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val minutes = LimitSlider.minutesFor(progress)
                value.text = Format.limit(minutes)
                if (fromUser) store.dailyLimitMinutes = minutes
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
    }

    // ---------- bedtime ----------

    private fun setUpBedtime() {
        val enabled = findViewById<CheckBox>(R.id.bedtime_enabled)
        val times = findViewById<View>(R.id.bedtime_times)
        val start = findViewById<Button>(R.id.bedtime_start)
        val end = findViewById<Button>(R.id.bedtime_end)

        fun refresh() {
            val setting = store.bedtime
            times.visibility = if (setting.enabled) View.VISIBLE else View.GONE
            enabled.isChecked = setting.enabled
            start.text = getString(R.string.rules_bedtime_start, Format.time(setting.start))
            end.text = getString(R.string.rules_bedtime_end, Format.time(setting.end))
        }

        refresh()

        enabled.setOnCheckedChangeListener { _, checked ->
            store.bedtime = store.bedtime.copy(enabled = checked)
            refresh()
        }
        start.setOnClickListener {
            pickTime(store.bedtime.start) { picked ->
                store.bedtime = store.bedtime.copy(start = picked)
                refresh()
            }
        }
        end.setOnClickListener {
            pickTime(store.bedtime.end) { picked ->
                store.bedtime = store.bedtime.copy(end = picked)
                refresh()
            }
        }
    }

    private fun pickTime(current: LocalTime, onPicked: (LocalTime) -> Unit) {
        TimePickerDialog(
            this,
            { _, hour, minute -> onPicked(LocalTime.of(hour, minute)) },
            current.hour,
            current.minute,
            true,
        ).show()
    }

    // ---------- categories ----------

    private fun setUpCategories() {
        findViewById<Button>(R.id.add_category).setOnClickListener {
            startActivity(CategoryEditActivity.intent(this))
        }
    }

    private fun renderCategories() {
        val container = findViewById<LinearLayout>(R.id.category_list)
        container.removeAllViews()

        val categories = store.categories
        if (categories.isEmpty()) {
            container.addView(bodyText(getString(R.string.rules_no_categories)))
            return
        }

        categories.sortedBy { it.name.lowercase() }.forEach { category ->
            container.addView(
                bodyText(
                    getString(
                        R.string.rules_category_row,
                        category.name,
                        Format.limit(category.limitMinutes),
                        resources.getQuantityString(
                            R.plurals.rules_category_apps,
                            category.packages.size,
                            category.packages.size,
                        ),
                    ),
                ).apply {
                    setOnClickListener {
                        startActivity(CategoryEditActivity.intent(this@RulesActivity, category.id))
                    }
                },
            )
        }
    }

    // ---------- apps with no limit ----------

    private fun setUpExempt() {
        findViewById<Button>(R.id.choose_exempt).setOnClickListener {
            pickExempt.launch(
                AppPickerActivity.intent(
                    this,
                    getString(R.string.rules_exempt_header),
                    store.exemptPackages,
                ),
            )
        }
    }

    private fun renderExempt() {
        val summary = findViewById<TextView>(R.id.exempt_summary)
        val exempt = store.exemptPackages
        summary.text = if (exempt.isEmpty()) {
            getString(R.string.rules_no_exempt)
        } else {
            exempt.map { installedApps.labelFor(PackageName(it)) }.sorted().joinToString(", ")
        }
    }

    // ---------- pin ----------

    private fun setUpPin() {
        findViewById<Button>(R.id.change_pin).setOnClickListener {
            PinLock(this).clear()
            startActivity(PinActivity.intent(this))
        }
    }

    // ---------- device control ----------

    private fun setUpDeviceControl() {
        val coordinator = EnforcementCoordinator(this)
        val owner = coordinator.isDeviceOwner()

        findViewById<TextView>(R.id.device_status).setText(
            if (owner) R.string.rules_device_status_owner else R.string.rules_device_status_monitor,
        )

        findViewById<CheckBox>(R.id.tamper_protection).apply {
            isChecked = store.tamperProtection
            setOnCheckedChangeListener { _, checked ->
                store.tamperProtection = checked
                // Apply at once: turning it off is how someone recovers a device.
                EnforcementService.start(this@RulesActivity)
            }
        }

        findViewById<Button>(R.id.release_device).setOnClickListener {
            if (!owner) {
                toast(getString(R.string.rules_release_not_owner))
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setTitle(R.string.rules_release_confirm_title)
                .setMessage(R.string.rules_release_confirm_body)
                .setPositiveButton(R.string.rules_release) { _, _ ->
                    coordinator.releaseDevice()
                    toast(getString(R.string.rules_release_done))
                    finish()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun toast(message: String) =
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()

    // ---------- helpers ----------

    private fun bodyText(text: String) = TextView(this).apply {
        this.text = text
        styleAs(R.style.TextAppearance_Dementor_ListItem)
        setPadding(0, dimen(R.dimen.space_sm), 0, dimen(R.dimen.space_sm))
    }

    private companion object {
    }
}
