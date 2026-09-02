package dev.schrammel.protego_parentis.ui

import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.audit.Audit
import dev.schrammel.protego_parentis.model.AuditAction
import dev.schrammel.protego_parentis.model.AuditActor
import dev.schrammel.protego_parentis.model.InstallPolicy
import dev.schrammel.protego_parentis.rules.PolicyMirrorStore
import dev.schrammel.protego_parentis.rules.PolicySnapshot
import dev.schrammel.protego_parentis.rules.StoredCategory
import dev.schrammel.protego_parentis.rules.toLocalTime
import dev.schrammel.protego_parentis.rules.toMinuteOfDay
import dev.schrammel.protego_parentis.sync.AppInventoryEntry
import dev.schrammel.protego_parentis.sync.AppInventoryStore
import dev.schrammel.protego_parentis.sync.SyncCoordinator
import java.time.DayOfWeek
import java.time.LocalTime
import java.util.UUID

/**
 * Every rule this family can set, edited from a parent's own phone.
 *
 * This phone holds no rules of its own — everything shown here is this device's
 * replica of the family's shared state, read through [PolicyMirrorStore].
 * Editing changes only the local buffer; nothing leaves until "send" is pressed,
 * and what then travels is the registers that actually differ rather than the
 * whole policy.
 *
 * A concurrent edit elsewhere is no longer a reason to refuse anything. Two
 * parents changing different rules both land; two changing the same one are
 * ordered by their logical clocks, and every device ends up agreeing on the
 * result — see [dev.schrammel.protego_parentis.sync.StateMerge].
 *
 * Tamper protection is not here, on purpose. It is the one rule this app treats
 * as too dangerous to change from a distance — see [PolicySnapshot].
 *
 * Not PIN-gated, like every other screen built for a parent's own phone: this
 * device is not the one being managed, so there is nothing here for a second
 * lock to protect that the phone's own lock screen does not already.
 */
class ParentRulesActivity : ParentRoleActivity() {

    private lateinit var sync: SyncCoordinator
    private lateinit var mirror: PolicyMirrorStore
    private lateinit var inventory: AppInventoryStore
    private lateinit var column: LinearLayout

    /** The edit in progress. Null until a snapshot has ever been received. */
    private var edited: PolicySnapshot? = null

    /**
     * Survives [render], which rebuilds the whole column after any policy edit.
     * Losing what a parent typed because they ticked a box is not acceptable.
     */
    private var appQuery = ""

    override fun onParentPhoneReady(savedInstanceState: Bundle?) {
        sync = SyncCoordinator(this)
        mirror = PolicyMirrorStore(this)
        inventory = AppInventoryStore(this)
        edited = mirror.snapshot

        column = contentColumn()
        showColumn(column)
        render()
        refresh()
    }

    override fun onParentPhoneResumed() {
        if (edited == null) edited = mirror.snapshot
        render()
    }

    /**
     * Picks up whatever has replicated since this screen was last drawn.
     *
     * Nothing is *asked for* any more: every device holds the same state and
     * receives changes as they are made, so this only polls and re-reads. The
     * button that runs it is a "check now", not a request.
     */
    private fun refresh() {
        if (!sync.settings().isUsable) return
        sync.publishAppInventoryRequest()
        sync.pollNow {
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                // Only replaces the buffer if nothing has been typed yet this visit
                // — a slow poll landing mid-edit must not blow away unsent changes.
                if (edited == null) edited = mirror.snapshot
                render()
            }
        }
    }

    private fun render() {
        column.removeAllViews()
        column.addView(screenTitle(getString(R.string.parent_rules_title)))

        if (!sync.settings().isUsable) {
            column.addView(bodyText(getString(R.string.parent_not_connected_hint)))
            return
        }

        val snapshot = edited
        if (snapshot == null) {
            column.addView(bodyText(getString(R.string.parent_rules_loading)))
            column.addView(
                actionButton().apply {
                    setText(R.string.parent_rules_refresh)
                    setOnClickListener { refresh() }
                },
            )
            return
        }

        column.addView(captionText(getString(R.string.parent_rules_intro)))

        column.addView(sectionHeader(getString(R.string.parent_rules_daily_limit)))
        column.addView(
            limitSliderBlock(
                initialMinutes = snapshot.dailyLimitMinutes,
                onChanged = { minutes -> update { it.copy(dailyLimitMinutes = minutes) } },
            ),
        )

        column.addView(sectionHeader(getString(R.string.rules_bedtime_name)))
        column.addView(bedtimeCard(snapshot))

        column.addView(sectionHeader(getString(R.string.parent_rules_install_header)))
        column.addView(installPolicyCard(snapshot))

        column.addView(sectionHeader(getString(R.string.rules_categories_header)))
        column.addView(captionText(getString(R.string.rules_categories_hint)))
        if (snapshot.categories.isNotEmpty()) {
            column.addView(group(snapshot.categories.map(::categoryRow)))
        }
        column.addView(
            actionButton().apply {
                setText(R.string.parent_rules_categories_add)
                setOnClickListener { addCategory() }
            },
        )

        column.addView(sectionHeader(getString(R.string.parent_rules_apps_header)))
        val apps = inventory.apps
        if (apps.isEmpty()) {
            // Nothing heard from the managed phone yet. Distinct from a search
            // that matched nothing: the fix for each is a different action.
            column.addView(captionText(getString(R.string.parent_rules_apps_none)))
        } else {
            val appRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            column.addView(
                searchField(appQuery) { typed ->
                    appQuery = typed
                    renderAppRows(appRows, apps, snapshot)
                },
            )
            column.addView(appRows)
            renderAppRows(appRows, apps, snapshot)
        }

        column.addView(
            Button(this).apply {
                setText(R.string.parent_rules_send)
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                    .apply { topMargin = dimen(R.dimen.space_lg) }
                setOnClickListener { send() }
            },
        )
        column.addView(
            actionButton().apply {
                setText(R.string.parent_rules_refresh)
                setOnClickListener { refresh() }
            },
        )
    }

    // ---------- bedtime ----------

    private fun bedtimeCard(snapshot: PolicySnapshot): LinearLayout = card {
        val start = Button(this@ParentRulesActivity).apply {
            text = formatTime(snapshot.bedtimeStartMinute.toLocalTime())
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            setOnClickListener {
                pickTime(snapshot.bedtimeStartMinute.toLocalTime()) { picked ->
                    update { it.copy(bedtimeStartMinute = picked.toMinuteOfDay()) }
                }
            }
        }
        val end = Button(this@ParentRulesActivity).apply {
            text = formatTime(snapshot.bedtimeEndMinute.toLocalTime())
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            setOnClickListener {
                pickTime(snapshot.bedtimeEndMinute.toLocalTime()) { picked ->
                    update { it.copy(bedtimeEndMinute = picked.toMinuteOfDay()) }
                }
            }
        }

        addView(
            CheckBox(this@ParentRulesActivity).apply {
                setText(R.string.rules_bedtime_enable)
                isChecked = snapshot.bedtimeEnabled
                setOnCheckedChangeListener { _, checked ->
                    update { it.copy(bedtimeEnabled = checked) }
                }
            },
        )
        if (snapshot.bedtimeEnabled) {
            addView(
                LinearLayout(this@ParentRulesActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(start)
                    addView(end)
                },
            )
            addView(captionText(getString(R.string.rules_bedtime_nights)))
            val days = snapshot.bedtimeDays.mapNotNull { value -> DayOfWeek.entries.firstOrNull { it.value == value } }
                .toSet()
            addView(
                DayPicker.build(this@ParentRulesActivity, days) { chosen ->
                    update { it.copy(bedtimeDays = chosen.map(DayOfWeek::getValue).toSet()) }
                },
            )
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

    // ---------- install policy ----------

    private fun installPolicyCard(snapshot: PolicySnapshot): LinearLayout = card {
        val current = runCatching { InstallPolicy.valueOf(snapshot.installPolicy) }
            .getOrDefault(InstallPolicy.NOTIFY)

        addView(
            RadioGroup(this@ParentRulesActivity).apply {
                orientation = RadioGroup.VERTICAL
                listOf(
                    InstallPolicy.NOTIFY to R.string.parent_rules_install_notify,
                    InstallPolicy.GATED to R.string.parent_rules_install_gated,
                ).forEachIndexed { index, (policy, label) ->
                    addView(
                        RadioButton(context).apply {
                            id = index + 1
                            setText(label)
                            isChecked = policy == current
                            setOnCheckedChangeListener { _, checked ->
                                if (checked) update { it.copy(installPolicy = policy.name) }
                            }
                        },
                    )
                }
            },
        )
    }

    // ---------- categories ----------

    private fun categoryRow(category: StoredCategory): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        setPadding(0, dimen(R.dimen.space_sm), 0, dimen(R.dimen.space_sm))

        addView(
            TextView(this@ParentRulesActivity).apply {
                text = category.name
                styleAs(R.style.TextAppearance_Dementor_ListItem)
            },
        )
        addView(
            captionText(
                getString(
                    R.string.parent_rules_category_summary,
                    formatLimit(category.limitMinutes),
                    category.packages.size,
                ),
            ),
        )
        addView(
            LinearLayout(this@ParentRulesActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    topMargin = dimen(R.dimen.space_xs)
                }
                addView(
                    Button(context).apply {
                        setText(R.string.category_choose_apps)
                        layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                            .apply { marginEnd = dimen(R.dimen.space_xs) }
                        setOnClickListener {
                            pickApps(category.packages) { chosen ->
                                updateCategory(category.copy(packages = chosen))
                            }
                        }
                    },
                )
                addView(
                    Button(context).apply {
                        text = formatLimit(category.limitMinutes)
                        layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                            .apply { marginEnd = dimen(R.dimen.space_xs) }
                        setOnClickListener {
                            sliderDialog(
                                title = category.name,
                                max = LimitSlider.MAX_STEPS,
                                initialProgress = LimitSlider.progressFor(category.limitMinutes),
                                format = { formatLimit(LimitSlider.minutesFor(it)) },
                            ) { progress ->
                                updateCategory(category.copy(limitMinutes = LimitSlider.minutesFor(progress)))
                            }
                        }
                    },
                )
                addView(
                    Button(context).apply {
                        setText(R.string.category_delete)
                        layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                        setOnClickListener {
                            update { snapshot ->
                                snapshot.copy(categories = snapshot.categories.filterNot { it.id == category.id })
                            }
                        }
                    },
                )
            },
        )
    }

    private fun addCategory() {
        promptForText(
            title = R.string.category_title,
            hint = R.string.category_name_hint,
        ) { name ->
            val category = StoredCategory(
                id = UUID.randomUUID().toString(),
                name = name,
                limitMinutes = 0,
                packages = emptySet(),
            )
            update { snapshot -> snapshot.copy(categories = snapshot.categories + category) }
        }
    }

    private fun updateCategory(changed: StoredCategory) = update { snapshot ->
        snapshot.copy(categories = snapshot.categories.map { if (it.id == changed.id) changed else it })
    }

    // ---------- apps ----------

    private fun renderAppRows(
        into: LinearLayout,
        apps: List<AppInventoryEntry>,
        snapshot: PolicySnapshot,
    ) {
        into.removeAllViews()
        val visible = AppSearch.filter(apps, appQuery, { it.label }, { it.packageName })
        if (visible.isEmpty()) {
            into.addView(noSearchResults())
        } else {
            into.addView(group(visible.map { app -> appRow(app, snapshot) }))
        }
    }

    private fun appRow(app: AppInventoryEntry, snapshot: PolicySnapshot): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            setPadding(0, dimen(R.dimen.space_sm), 0, dimen(R.dimen.space_sm))

            val limitMinutes = snapshot.appLimits[app.packageName] ?: 0
            addView(
                TextView(this@ParentRulesActivity).apply {
                    text = app.label
                    styleAs(R.style.TextAppearance_Dementor_ListItem)
                },
            )
            addView(
                Button(context).apply {
                    text = if (limitMinutes > 0) {
                        getString(R.string.parent_rules_app_limit, formatLimit(limitMinutes))
                    } else {
                        getString(R.string.parent_rules_app_no_limit)
                    }
                    setOnClickListener {
                        sliderDialog(
                            title = app.label,
                            max = LimitSlider.MAX_STEPS,
                            initialProgress = LimitSlider.progressFor(limitMinutes),
                            format = { formatLimit(LimitSlider.minutesFor(it)) },
                        ) { progress ->
                            val minutes = LimitSlider.minutesFor(progress)
                            update { current ->
                                current.copy(
                                    appLimits = if (minutes > 0) {
                                        current.appLimits + (app.packageName to minutes)
                                    } else {
                                        current.appLimits - app.packageName
                                    },
                                )
                            }
                        }
                    }
                },
            )
            addView(
                CheckBox(context).apply {
                    setText(R.string.parent_rules_app_exempt)
                    isChecked = app.packageName in snapshot.exemptPackages
                    setOnCheckedChangeListener { _, checked ->
                        update { current ->
                            current.copy(
                                exemptPackages = if (checked) {
                                    current.exemptPackages + app.packageName
                                } else {
                                    current.exemptPackages - app.packageName
                                },
                            )
                        }
                    }
                },
            )
            addView(
                CheckBox(context).apply {
                    setText(R.string.parent_rules_app_blocked)
                    isChecked = app.packageName in snapshot.blockedPackages
                    setOnCheckedChangeListener { _, checked ->
                        update { current ->
                            current.copy(
                                blockedPackages = if (checked) {
                                    current.blockedPackages + app.packageName
                                } else {
                                    current.blockedPackages - app.packageName
                                },
                            )
                        }
                    }
                },
            )
            addView(
                CheckBox(context).apply {
                    setText(R.string.parent_rules_app_network_blocked)
                    isChecked = app.packageName in snapshot.networkBlockedPackages
                    setOnCheckedChangeListener { _, checked ->
                        update { current ->
                            current.copy(
                                networkBlockedPackages = if (checked) {
                                    current.networkBlockedPackages + app.packageName
                                } else {
                                    current.networkBlockedPackages - app.packageName
                                },
                            )
                        }
                    }
                },
            )
        }

    /**
     * A checklist of every app this phone knows about, for choosing a
     * category's membership.
     */
    private fun pickApps(current: Set<String>, onChosen: (Set<String>) -> Unit) {
        val apps = inventory.apps
        if (apps.isEmpty()) {
            toast(R.string.parent_rules_apps_none)
            return
        }

        val chosen = current.toMutableSet()
        val boxes = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Rebuilds the checkboxes from `chosen`, never the other way round: an app
        // ticked and then filtered out of view stays chosen, which is the obvious
        // way to get this wrong and it silently discards a parent's choices.
        fun renderBoxes(query: String) {
            boxes.removeAllViews()
            val visible = AppSearch.filter(apps, query, { it.label }, { it.packageName })
            if (visible.isEmpty()) {
                boxes.addView(noSearchResults())
                return
            }
            visible.forEach { app ->
                boxes.addView(
                    CheckBox(this).apply {
                        text = app.label
                        isChecked = app.packageName in chosen
                        setOnCheckedChangeListener { _, checked ->
                            if (checked) chosen += app.packageName else chosen -= app.packageName
                        }
                    },
                )
            }
        }
        renderBoxes("")

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dimen(R.dimen.screen_padding)
            setPadding(pad, pad, pad, 0)
            addView(searchField { renderBoxes(it) })
            addView(boxes)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.category_apps_header)
            .setView(ScrollView(this).apply { addView(list) })
            .setPositiveButton(android.R.string.ok) { _, _ -> onChosen(chosen.toSet()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------- plumbing ----------

    private fun update(transform: (PolicySnapshot) -> PolicySnapshot) {
        edited = edited?.let(transform)
        render()
    }

    /**
     * Pushes the edit buffer to the child phone.
     *
     * "Sent", not "saved" — like every other remote action here, this is a
     * message the other phones apply at their next poll, not a synchronous
     * change. Unlike the push this replaced, it cannot clobber a concurrent
     * edit: only the registers that actually differ travel, and they merge.
     */
    private fun send() {
        val snapshot = edited ?: return
        if (!sync.settings().isUsable) {
            toast(R.string.parent_not_connected_action)
            return
        }
        sync.applyPolicyEdit(snapshot)
        Audit.record(context = this, actor = AuditActor.PARENT, action = AuditAction.RULES_REPLACED)
        toast(R.string.parent_rules_sent)
    }

    companion object {
        fun intent(context: Context) = Intent(context, ParentRulesActivity::class.java)
    }
}
