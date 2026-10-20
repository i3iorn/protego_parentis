package dev.schrammel.protego_parentis.ui

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.schrammel.protego_parentis.AppRegistry
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.audit.Audit
import dev.schrammel.protego_parentis.enforcement.EnforcementCoordinator
import dev.schrammel.protego_parentis.model.AuditAction
import dev.schrammel.protego_parentis.model.AuditActor
import dev.schrammel.protego_parentis.model.Defaults
import dev.schrammel.protego_parentis.model.InstallPolicy
import dev.schrammel.protego_parentis.rules.GoalCoordinator
import dev.schrammel.protego_parentis.rules.LocalPolicyStore
import dev.schrammel.protego_parentis.ui.rules.RuleScope
import dev.schrammel.protego_parentis.ui.rules.RuleTargets
import dev.schrammel.protego_parentis.ui.rules.categoryBudgetMinutes
import dev.schrammel.protego_parentis.ui.rules.categories
import dev.schrammel.protego_parentis.ui.rules.contextRuleRuleStore
import dev.schrammel.protego_parentis.ui.rules.policyRuleStore
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** Only used to turn the install-window length into an offset. */
private const val SECONDS_PER_MINUTE = 60L

/** The list of categories, with their limits and how many apps each holds. */
class CategoriesActivity : ParentGatedActivity() {

    private lateinit var target: RuleScope
    private lateinit var column: LinearLayout

    override fun supportsReadOnly() = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        column = contentColumn()
        showColumn(column)
    }

    override fun onGatedContentVisible() {
        val resolved = RuleTargets.resolve(this) ?: return finish()
        target = resolved
        if (!requireStillInFamily(target)) return
        render()
    }

    private fun render() {
        column.removeAllViews()
        column.addView(screenTitle(getString(R.string.rules_categories_header)))
        column.addView(captionText(getString(R.string.rules_categories_hint)))
        if (!canEdit) column.addView(readOnlyBanner { requestUnlock { onGatedContentVisible() } })

        val categories = policyRuleStore(this, target).categories().sortedBy { it.name.lowercase() }
        // A category's limit is a rule, not a field: the editor writes it as a
        // ContextRule and leaves the stored field at zero (see
        // [CategoryEditActivity]). Reading the field here would show every
        // category as unlimited, and a limit set a moment ago would look as
        // though it had not stuck. The stored field stays the fallback for a
        // category no rule covers yet.
        val rules = contextRuleRuleStore(this, target).read()
        if (categories.isEmpty()) {
            column.addView(bodyText(getString(R.string.rules_no_categories)))
        } else {
            column.addView(
                group(
                    categories.map { category ->
                        navigationRow(
                            title = category.name,
                            subtitle = getString(
                                R.string.rules_category_row,
                                formatLimit(
                                    categoryBudgetMinutes(rules, category.id) ?: category.limitMinutes,
                                ),
                                resources.getQuantityString(
                                    R.plurals.rules_category_apps,
                                    category.packages.size,
                                    category.packages.size,
                                ),
                            ),
                        ) { startActivity(CategoryEditActivity.intent(this, category.id, target.childId)) }
                    },
                ),
            )
        }

        if (canEdit) {
            column.addView(
                actionButton().apply {
                    setText(R.string.rules_add_category)
                    setOnClickListener {
                        startActivity(CategoryEditActivity.intent(this@CategoriesActivity, childId = target.childId))
                    }
                },
            )
        }
    }
}

/** The daily goal and the prize for meeting it. */
class GoalActivity : ParentGatedActivity() {

    private lateinit var goals: GoalCoordinator
    private lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        goals = GoalCoordinator(this)
        column = contentColumn()
        showColumn(column)
    }

    override fun onGatedContentVisible() = render()

    private fun render() {
        column.removeAllViews()
        column.addView(screenTitle(getString(R.string.rules_goal_header)))
        column.addView(captionText(getString(R.string.rules_goal_hint)))

        val existing = goals.goal()
        val settings = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (existing.enabled) View.VISIBLE else View.GONE
        }

        val startingMinutes = existing.maxUsage.inWholeMinutes.toInt()
            .takeIf { it > 0 } ?: DEFAULT_GOAL_MINUTES
        var chosenMinutes = startingMinutes
        val slider = limitSliderBlock(
            initialMinutes = startingMinutes,
            onChanged = { minutes -> chosenMinutes = minutes },
        )
        val prize = EditText(this).apply {
            hint = getString(R.string.rules_goal_prize_hint)
            setText(existing.prize)
        }

        column.addView(
            CheckBox(this).apply {
                setText(R.string.rules_goal_enable)
                isChecked = existing.enabled
                setOnCheckedChangeListener { _, checked ->
                    settings.visibility = if (checked) View.VISIBLE else View.GONE
                    // Switching off applies at once. Switching on waits for a prize,
                    // so a goal is never live with nothing to win.
                    if (!checked) {
                        goals.saveGoal(false, chosenMinutes, prize.text.toString())
                        Audit.record(
                            context = this@GoalActivity,
                            actor = AuditActor.PARENT,
                            action = AuditAction.GOAL_CHANGED,
                            after = Audit.minutes(0),
                        )
                    }
                }
            },
        )
        settings.addView(slider)
        settings.addView(prize)
        settings.addView(
            Button(this).apply {
                setText(R.string.rules_goal_save)
                setOnClickListener {
                    val text = prize.text.toString().trim()
                    if (text.isEmpty()) {
                        this@GoalActivity.toast(R.string.rules_goal_needs_prize)
                        return@setOnClickListener
                    }
                    val minutes = chosenMinutes
                    val prizeChanged = text != existing.prize
                    goals.saveGoal(true, minutes, text)
                    Audit.record(
                        context = this@GoalActivity,
                        actor = AuditActor.PARENT,
                        action = AuditAction.GOAL_CHANGED,
                        before = Audit.minutes(startingMinutes),
                        after = Audit.minutes(minutes),
                    )
                    // The prize text itself is never recorded. It is meant to be a
                    // surprise, and a history that spoils it would not be opened twice.
                    if (prizeChanged) {
                        Audit.record(
                            context = this@GoalActivity,
                            actor = AuditActor.PARENT,
                            action = AuditAction.GOAL_PRIZE_CHANGED,
                        )
                    }
                    this@GoalActivity.toast(R.string.rules_goal_saved)
                }
            },
        )
        column.addView(settings)
    }

    private companion object {
        const val DEFAULT_GOAL_MINUTES = 60
    }
}

/** The PIN, the lockdown switch, and the way back out. */
class DeviceControlActivity : ParentGatedActivity() {

    private lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        column = contentColumn()
        showColumn(column)
    }

    override fun onGatedContentVisible() = render()

    /**
     * Five controls that used to float.
     *
     * Every other list in this app sits on a raised surface; this screen had bare
     * checkboxes and buttons straight on the window, which is the look the rest of
     * the design was written to get away from. Each setting now gets a card, with
     * the sentence explaining it inside the same card rather than orphaned above.
     *
     * Two headings also went. "Lock this phone down" was the section heading *and*
     * the checkbox label directly beneath it, and so was "Release this phone" — a
     * screen that says a thing twice in a row reads as a rendering fault.
     */
    private fun render() {
        val store = AppRegistry.childStores.policy
        val coordinator = EnforcementCoordinator(this)

        column.removeAllViews()
        column.addView(screenTitle(getString(R.string.rules_device_header)))

        column.addView(sectionHeader(getString(R.string.rules_security_header)))
        column.addView(
            card {
                addView(captionText(getString(R.string.parent_settings_pin_body)))
                addView(
                    actionButton().apply {
                        setText(R.string.rules_change_pin)
                        setOnClickListener {
                            // The old PIN is verified inside, and replaced only
                            // once the new one is confirmed.
                            startActivity(
                                PinActivity.intent(this@DeviceControlActivity, change = true),
                            )
                        }
                    },
                )
            },
        )
        column.addView(
            card {
                addView(
                    TextView(this@DeviceControlActivity).apply {
                        setText(R.string.biometric_header)
                        styleAs(R.style.TextAppearance_Dementor_Subtitle)
                    },
                )
                addView(biometricSetting { render() })
            },
        )

        column.addView(sectionHeader(getString(R.string.rules_protection_header)))
        column.addView(
            card {
                addView(
                    CheckBox(this@DeviceControlActivity).apply {
                        setText(R.string.rules_tamper_enable)
                        isChecked = store.tamperProtection
                        setOnCheckedChangeListener { _, checked ->
                            val before = store.tamperProtection
                            store.tamperProtection = checked
                            Audit.record(
                                context = this@DeviceControlActivity,
                                actor = AuditActor.PARENT,
                                action = AuditAction.TAMPER_PROTECTION_CHANGED,
                                before = Audit.flag(before),
                                after = Audit.flag(checked),
                            )
                            // Applied at once: turning it off is how someone
                            // recovers a device. The re-kick is the store's job
                            // now — every LocalPolicyStore write fires the
                            // ruleMutationSink itself.
                        }
                    },
                )
                addView(captionText(getString(R.string.rules_tamper_hint)))
            },
        )
        column.addView(
            card {
                addView(
                    CheckBox(this@DeviceControlActivity).apply {
                        setText(R.string.rules_installs_gate)
                        isChecked = store.installPolicy == InstallPolicy.GATED
                        setOnCheckedChangeListener { _, gated ->
                            val chosen = if (gated) InstallPolicy.GATED else InstallPolicy.NOTIFY
                            // One transaction, so one version bump and one
                            // enforcement kick. Switching the gate off leaves no
                            // window behind, so switching it back on is shut
                            // rather than mysteriously open.
                            store.atomically {
                                store.installPolicy = chosen
                                if (!gated) store.installWindowUntil = null
                            }
                            Audit.record(
                                context = this@DeviceControlActivity,
                                actor = AuditActor.PARENT,
                                action = AuditAction.INSTALL_POLICY_CHANGED,
                                after = chosen.name,
                            )
                            render()
                        }
                    },
                )
                addView(captionText(getString(R.string.rules_installs_hint)))
                if (store.installPolicy == InstallPolicy.GATED) addView(installWindow(store))
            },
        )

        column.addView(sectionHeader(getString(R.string.rules_release_header)))
        column.addView(
            card {
                addView(captionText(getString(R.string.rules_release_body)))
                addView(
                    actionButton().apply {
                        setText(R.string.rules_release)
                        setOnClickListener { confirmRelease(coordinator) }
                    },
                )
            },
        )
    }

    /**
     * Opening the gate for a while.
     *
     * Without this, gating installs locks the door from the outside: nobody can
     * add an app again, including the parent who turned it on. A window that
     * expires on its own means forgetting to close it is not the same as never
     * having gated installs at all.
     */
    private fun installWindow(store: LocalPolicyStore): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            val until = store.installWindowUntil
            val open = until != null && Instant.now().isBefore(until)
            addView(
                captionText(
                    if (open) {
                        getString(
                            R.string.rules_installs_window_open,
                            formatTime(
                                LocalTime.ofInstant(checkNotNull(until), ZoneId.systemDefault()),
                            ),
                        )
                    } else {
                        getString(R.string.rules_installs_window_shut)
                    },
                ),
            )
            addView(
                Button(context).apply {
                    text = resources.getQuantityString(
                        R.plurals.rules_installs_open,
                        Defaults.INSTALL_WINDOW_MINUTES.toInt(),
                        Defaults.INSTALL_WINDOW_MINUTES.toInt(),
                    )
                    setOnClickListener {
                        store.installWindowUntil = Instant.now()
                            .plusSeconds(Defaults.INSTALL_WINDOW_MINUTES * SECONDS_PER_MINUTE)
                        Audit.record(
                            context = this@DeviceControlActivity,
                            actor = AuditActor.PARENT,
                            action = AuditAction.INSTALL_WINDOW_OPENED,
                            after = Audit.minutes(Defaults.INSTALL_WINDOW_MINUTES.toInt()),
                        )
                        render()
                    }
                },
            )
        }

    private fun confirmRelease(coordinator: EnforcementCoordinator) {
        Background.load(this, { coordinator.isDeviceOwner() }) { owner ->
            if (!owner) {
                toast(R.string.rules_release_not_owner, Toast.LENGTH_LONG)
                return@load
            }
            confirmDialog(
                R.string.rules_release_confirm_title,
                R.string.rules_release_confirm_body,
                R.string.rules_release,
            ) {
                Audit.record(
                    context = this,
                    actor = AuditActor.PARENT,
                    action = AuditAction.DEVICE_RELEASED,
                )
                Background.load(this, { coordinator.releaseDevice() }) {
                    toast(R.string.rules_release_done, Toast.LENGTH_LONG)
                    finish()
                }
            }
        }
    }
}
