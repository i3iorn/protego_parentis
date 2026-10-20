package dev.schrammel.protego_parentis.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.schrammel.protego_parentis.enforcement.EnforcementStatus
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.glucose.NightscoutStore
import dev.schrammel.protego_parentis.context.ContextCapabilities
import dev.schrammel.protego_parentis.context.ContextProvider
import dev.schrammel.protego_parentis.model.Condition
import dev.schrammel.protego_parentis.model.ContextRule
import dev.schrammel.protego_parentis.model.Defaults
import dev.schrammel.protego_parentis.model.PlaceRef
import dev.schrammel.protego_parentis.model.RecurringWindow
import dev.schrammel.protego_parentis.model.RuleEffect
import dev.schrammel.protego_parentis.model.RuleTarget
import dev.schrammel.protego_parentis.model.WeeklyBudget
import dev.schrammel.protego_parentis.policy.ContextRuleFormat
import dev.schrammel.protego_parentis.rules.LocalPolicyStore
import dev.schrammel.protego_parentis.sync.RosterStore
import dev.schrammel.protego_parentis.sync.core.DeviceId
import dev.schrammel.protego_parentis.ui.rules.bedtimeRuleId
import dev.schrammel.protego_parentis.ui.rules.categories
import dev.schrammel.protego_parentis.ui.rules.contextRuleRuleStore
import dev.schrammel.protego_parentis.ui.rules.placeRuleStore
import dev.schrammel.protego_parentis.ui.rules.policyRuleStore
import dev.schrammel.protego_parentis.ui.rules.ruleGroups
import dev.schrammel.protego_parentis.ui.rules.upsert
import dev.schrammel.protego_parentis.ui.rules.RuleScope
import dev.schrammel.protego_parentis.ui.rules.RuleSection
import dev.schrammel.protego_parentis.ui.rules.RuleTargets
import java.time.DayOfWeek
import java.time.LocalTime
import java.util.UUID

/**
 * Every rule a family can write, on one screen, on whichever kind of phone
 * edits [target]'s rules (#43, #106).
 *
 * The daily limit, schedules, and rules that depend on where or when the
 * phone is are all the same thing now — a [ContextRule] — so they are all
 * edited here rather than across the three screens (the old dedicated
 * schedules screen, `LimitsActivity`/`ParentLimitActivity`, and this screen's
 * own previous, narrower self) that #106 replaces. The daily limit is grouped
 * with the rest now rather than pinned in a slot of its own: it is still the
 * first thing the screen shows — it is the reason the app exists — but it is
 * the first card in the same list, not a different pattern (rules-UX phase E).
 *
 * The list is grouped by what a parent recognises — schedules, somewhere the
 * phone is or is near, health — and each rule carries its on/off state, so the
 * whole setup is legible at a glance (rules-UX phase B). See [ruleGroups].
 *
 * The screen also shows which context sources actually work on this phone — only
 * on the managed phone itself, though: [ContextProvider.capabilities] describes
 * whichever device is running the code, which on a parent's phone would answer
 * the wrong question entirely. Editing a remote child's rules skips that section
 * rather than showing a capability reading for the wrong device.
 */
class ContextRulesActivity : ParentGatedActivity() {

    private lateinit var target: RuleScope
    private lateinit var container: LinearLayout
    private lateinit var listHolder: LinearLayout

    /** The text typed into the rules search field, empty when nothing is typed. */
    private var searchQuery: String = ""

    /** How the rules are ordered: by the list's own grouping, or A→Z by name. */
    private var sortByName: Boolean = false

    override fun supportsReadOnly() = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = contentColumn()
        showColumn(container)
    }

    override fun onGatedContentVisible() {
        val resolved = RuleTargets.resolve(this) ?: return finish()
        target = resolved
        if (!requireStillInFamily(target)) return
        reload()
    }

    /**
     * Re-reads the rules from the store and draws the screen. The switch on a
     * rule card goes through here too: pausing or resuming moves the rule
     * between sections, so the whole screen redraws from the store rather than
     * one line mutating in place (rules-UX phase C).
     */
    private fun reload() {
        Background.load(this, {
            val rules = contextRuleRuleStore(this, target).read()
            val places = placeRuleStore(this, target).read()
            val capabilities = if (target.isOwnDevice) ContextProvider(this).capabilities() else null
            Loaded(rules, places, capabilities, RuleVersionWarning.read(this))
        }) { loaded ->
            render(loaded.rules, loaded.places, loaded.capabilities, loaded.versions)
        }
    }

    private class Loaded(
        val rules: List<ContextRule>,
        val places: List<PlaceRef>,
        val capabilities: ContextCapabilities?,
        val versions: ContextRuleFormat.MixedVersions?,
    )

    private fun render(
        rules: List<ContextRule>,
        places: List<PlaceRef>,
        capabilities: ContextCapabilities?,
        versions: ContextRuleFormat.MixedVersions?,
    ) {
        container.removeAllViews()
        container.addView(screenTitle(getString(R.string.context_title)))
        ruleVersionBanner(versions) { startActivity(FamilyDevicesActivity.intent(this)) }?.let(container::addView)
        // The "is this phone actually enforced?" answer, first: a parent who
        // cannot find it assumes the rules are doing something they are not.
        if (target.isOwnDevice) container.addView(deviceStatusCard())
        container.addView(caption(getString(R.string.context_explainer)))
        if (!canEdit) container.addView(readOnlyBanner { requestUnlock { onGatedContentVisible() } })
        // One line for "how much is set up", where the old screen had a flat
        // "Your rules" heading; the "About rules" explainer now rides on the
        // count (rules-UX phase B).
        container.addView(summaryRow(rules))

        // The daily limit is a rule like any other now, so it is drawn by the
        // same grouped list as everything else — its own group first, where a
        // parent looks, rather than a pinned slot of its own (rules-UX phase E).
        // The one thing the grouping cannot draw for us is the door to create it
        // when it does not exist yet: there is no card to group, so the screen
        // offers one.
        val dailyLimit = rules.firstOrNull { it.id == LocalPolicyStore.MIGRATED_DAILY_LIMIT_ID }
        if (dailyLimit == null && canEdit) {
            container.addView(
                navigationRow(
                    title = getString(R.string.context_daily_limit_add),
                    subtitle = getString(R.string.context_daily_limit_add_detail),
                ) {
                    contextRuleRuleStore(this, target).upsert(
                        ContextRule(
                            id = LocalPolicyStore.MIGRATED_DAILY_LIMIT_ID,
                            name = getString(R.string.rules_row_limits),
                            enabled = true,
                            condition = Condition.Always,
                            effect = RuleEffect.SetBudget(WeeklyBudget.flat(Defaults.DAILY_LIMIT)),
                            target = RuleTarget.Everything,
                        ),
                    )
                    startActivity(
                        ContextRuleEditActivity.intent(
                            this,
                            LocalPolicyStore.MIGRATED_DAILY_LIMIT_ID,
                            target.childId,
                        ),
                    )
                },
            )
        }

        // The search field and the sort switch are the screen's controls, not
        // part of the list they drive: a keystroke or a toggle rebuilds the
        // list below them and never the controls themselves, so typing never
        // steals the cursor — the same split AppLimitsActivity's rows use.
        container.addView(searchField(hint = getString(R.string.rules_search_hint)) { searchQuery = it; renderList(rules, places) })
        container.addView(sortToggle(rules, places))
        listHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(listHolder)
        renderList(rules, places)

        if (canEdit) {
            container.addView(
                Button(this).apply {
                    setText(R.string.context_add_rule)
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                        .apply { topMargin = dimen(R.dimen.space_md) }
                    setOnClickListener {
                        startActivity(ContextRuleEditActivity.intent(this@ContextRulesActivity, childId = target.childId))
                    }
                },
            )
        }

        // Below the rules rather than above them: the rules are what this
        // screen is opened for, and places, the tutorial and sensor status are
        // the setup around them.
        container.addView(
            Button(this).apply {
                setText(R.string.context_manage_places)
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                    .apply { topMargin = dimen(R.dimen.space_md) }
                setOnClickListener {
                    startActivity(PlacesActivity.intent(this@ContextRulesActivity, target.childId))
                }
            },
        )

        // The "how rules combine" explainer now lives only beside the condition
        // tree it describes — the advanced editor — rather than as a standalone
        // essay reachable from every screen (rules-UX phase F).

        // The other things this app enforces — app limits, categories, the web
        // filter, places — live on their own hub screen. "Rules" leads here, to
        // the rules, so this is the one door across to the rest (rules-UX phase A).
        container.addView(
            navigationRow(
                getString(R.string.rules_more_controls),
                getString(R.string.rules_more_controls_detail),
            ) {
                startActivity(RulesActivity.intent(this@ContextRulesActivity, target.childId))
            },
        )

        // What the sensors can read is only meaningful for this device's own.
        if (capabilities != null) {
            container.addView(title(getString(R.string.context_sources_header), R.style.TextAppearance_Dementor_Section))
            container.addView(
                caption(
                    getString(
                        if (capabilities.canReadLocation) R.string.context_wifi_ready else R.string.context_wifi_blocked,
                    ),
                ),
            )
            container.addView(
                caption(
                    getString(
                        if (capabilities.canReadLocation) R.string.context_place_ready else R.string.context_place_blocked,
                    ),
                ),
            )
            container.addView(
                caption(
                    getString(
                        if (capabilities.canReadNearbyDevices) {
                            R.string.context_near_ready
                        } else {
                            R.string.context_near_blocked
                        },
                    ),
                ),
            )
        }
    }

    /**
     * A suggestion, which becomes a real rule the moment it is tapped.
     *
     * Saved switched off and opened for editing straight away, the same way
     * the dedicated schedules screen this replaced always did: the times
     * here are a guess at somebody else's week, and the one thing worse than
     * an empty screen is a rule that started applying before anyone read it.
     */
    private fun starterRow(starter: Starter) = navigationRow(
        title = getString(starter.name),
        subtitle = getString(
            R.string.schedules_starter_detail,
            formatTime(starter.start),
            formatTime(starter.end),
        ),
    ) {
        val created = ContextRule(
            id = starter.fixedId ?: "starter-${UUID.randomUUID()}",
            name = getString(starter.name),
            enabled = false,
            condition = Condition.DuringWindow(RecurringWindow(starter.days, starter.start, starter.end)),
            effect = RuleEffect.Block(),
            // Blocks everything with nothing named as an exception — the same
            // shape the old schedules screen always gave a fresh starter.
            target = RuleTarget.EverythingExcept(emptySet()),
        )
        contextRuleRuleStore(this, target).upsert(created)
        startActivity(ContextRuleEditActivity.intent(this, created.id, target.childId))
    }

    /**
     * A named window a family is likely to want, with the hours already in it.
     *
     * [fixedId] is null for School/Homework/Dinner — any number of those can
     * exist, so each gets a fresh id — but set for bedtime, so a family that
     * creates it here lands on the exact id #105's migration already gives a
     * pre-existing one, and [HomeActivity]'s summary can find either the same
     * way. Naming a single well-known instance of a rule by a fixed id, the
     * same way [LocalPolicyStore.MIGRATED_DAILY_LIMIT_ID] does for the daily
     * limit above.
     */
    private data class Starter(
        val name: Int,
        val days: Set<DayOfWeek>,
        val start: LocalTime,
        val end: LocalTime,
        val fixedId: String? = null,
    )

    /**
     * One rule, as a card (rules-UX phase C): its name with an on/off chip,
     * then the rule as labelled When/Then/Except rows instead of one sentence
     * to unpick. The body opens the editor; the switch flips the rule without
     * entering it — read-only (before the PIN) a parent sees the cards with the
     * switches disabled, so the setup stays legible while the actions are not
     * offered (rules-UX §G).
     */
    /**
     * The rules themselves, rebuilt on every keystroke or sort toggle: the
     * controls above them are not, so typing never steals the cursor (the
     * same split AppLimitsActivity's rows use).
     *
     * While a search is active the groups give way to one flat list of the
     * rules that match — a parent looking for one rule does not want to guess
     * which section it lives in — and the A→Z order only applies inside the
     * list's own groups when no search is filtering them.
     */
    private fun renderList(rules: List<ContextRule>, places: List<PlaceRef>) {
        listHolder.removeAllViews()
        val visible =
            if (searchQuery.isBlank()) {
                rules
            } else {
                rules.filter { it.name.contains(searchQuery, ignoreCase = true) }
            }
        if (searchQuery.isBlank()) {
            ruleGroups(visible).forEach { group ->
                val ordered = if (sortByName) group.rules.sortedBy { it.name.lowercase() } else group.rules
                listHolder.addView(sectionHeading(group.section))
                addCollapsibleRows(listHolder, ordered.map { ruleCardRow(it, places) })
            }
        } else {
            val ordered = if (sortByName) visible.sortedBy { it.name.lowercase() } else visible
            if (ordered.isEmpty()) {
                listHolder.addView(caption(getString(R.string.context_search_empty)))
            } else {
                addCollapsibleRows(listHolder, ordered.map { ruleCardRow(it, places) })
            }
        }

        // Nothing but the daily limit — or nothing at all. An invitation rather
        // than a dead end, below the list so the daily limit still leads it.
        val otherRules = rules.filterNot { it.id == LocalPolicyStore.MIGRATED_DAILY_LIMIT_ID }
        if (otherRules.isEmpty()) {
            listHolder.addView(caption(getString(R.string.context_no_rules)))
            if (canEdit) {
                // Offered rather than created. A family that opens this screen and
                // finds three windows already switched on has had rules made for
                // them by an app, which is the opposite of what this product is —
                // so these arrive off, named, and with the times a parent would
                // have typed anyway, leaving the decision where it belongs.
                listHolder.addView(
                    title(getString(R.string.schedules_starters_header), R.style.TextAppearance_Dementor_Section),
                )
                listHolder.addView(caption(getString(R.string.schedules_starters_hint)))
                listHolder.addView(group(STARTERS.map(::starterRow)))
            }
        }
    }

    /**
     * The one control for how the rules are ordered: by the list's own grouping
     * (the default) or A→Z by name. A word, never colour alone.
     */
    private fun sortToggle(rules: List<ContextRule>, places: List<PlaceRef>): View = Button(this).apply {
        setText(if (sortByName) R.string.rules_sort_grouped else R.string.rules_sort_name)
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            .apply { topMargin = dimen(R.dimen.space_xs) }
        setOnClickListener {
            sortByName = !sortByName
            renderList(rules, places)
        }
    }

    private fun ruleCardRow(rule: ContextRule, places: List<PlaceRef>): View = ruleCard(
        rule = rule,
        describer = RuleDescriber(
            activity = this,
            placeName = { id -> places.firstOrNull { it.id == id }?.name },
            categoryName = { id -> policyRuleStore(this, target).categories().firstOrNull { it.id == id.value }?.name },
            glucoseUnit = NightscoutStore(this).units,
        ),
        onOpen = {
            startActivity(
                ContextRuleEditActivity.intent(this@ContextRulesActivity, rule.id, target.childId),
            )
        },
        onToggle = if (canEdit) {
            { checked -> toggleEnabled(rule, checked) }
        } else {
            null
        },
        riskNote = riskNote(rule),
        activeNow = EnforcementStatus(this).activeRuleIds.contains(rule.id),
    )

    /**
     * Flips a rule on the spot: the write is the enforcement edit — the same
     * store [ContextRuleEditActivity] saves to — and the screen re-reads so the
     * rule lands in its new section's place straight away.
     */
    private fun toggleEnabled(rule: ContextRule, checked: Boolean) {
        contextRuleRuleStore(this, target).upsert(rule.copy(enabled = checked))
        reload()
    }

    /**
     * The per-rule half of the mixed-version warning (rules-UX §G, seen to in
     * phase C): a rule one or more phones cannot read until they update, named
     * on the card itself rather than only in the banner above the list. The
     * same phones-and-per-rule shape [ContextRuleEditActivity]'s save-time
     * confirm uses — a phone that hasn't reported a version counts as one that
     * can't, because a silent phone deletes what it can't read.
     */
    private fun riskNote(rule: ContextRule): String? {
        val required = ContextRuleFormat.requiredVersion(rule) ?: return null
        val older = RosterStore(this).devices
            .filterNot { ContextRuleFormat.supports(it.appVersion, required) }
            .map { it.name }
        if (older.isEmpty()) return null
        return getString(R.string.context_card_version_risk, older.joinToString(", "))
    }

    /** The count of what is set up, with the "About rules" explainer beside it. */
    private fun summaryRow(rules: List<ContextRule>): LinearLayout {
        val on = rules.count { it.enabled }
        val paused = rules.count { !it.enabled }
        val onText = resources.getQuantityString(R.plurals.rules_summary_on, on, on)
        val text = if (paused == 0) {
            onText
        } else {
            onText + " · " + resources.getQuantityString(R.plurals.rules_summary_paused, paused, paused)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(caption(text).apply { layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f) })
            addView(infoButton(R.string.context_rules_info_title, R.string.context_rules_info_body))
        }
    }

    /**
     * The heading a [RuleSection] is shown under. The daily limit's carries its
     * own explainer, the same pairing the full editor gives its "when" and "to"
     * rows; every other section is just its name.
     */
    private fun sectionHeading(section: RuleSection): View {
        val label = title(getString(sectionLabel(section)), R.style.TextAppearance_Dementor_Section)
        return if (section == RuleSection.DAILY_LIMIT) {
            headingWithInfo(
                label,
                R.string.context_daily_limit_info_title,
                R.string.context_daily_limit_info_body,
            )
        } else {
            label
        }
    }

    /** The text a [RuleSection]'s heading reads. */
    private fun sectionLabel(section: RuleSection): Int = when (section) {
        RuleSection.DAILY_LIMIT -> R.string.context_daily_limit_header
        RuleSection.SCHEDULES -> R.string.rules_section_schedules
        RuleSection.WHERE -> R.string.rules_section_where
        RuleSection.HEALTH -> R.string.rules_section_health
        RuleSection.OTHER -> R.string.rules_section_other
        RuleSection.PAUSED -> R.string.rules_section_paused
    }

    /**
     * A title or section heading on this screen. Marked as an accessibility
     * heading so TalkBack's heading navigation can jump between the parts of a
     * long list, the same way [screenTitle] and [sectionHeader] do elsewhere
     * (rules-UX phase F).
     */
    private fun title(text: String, appearance: Int) = TextView(this).apply {
        this.text = text
        isAccessibilityHeading = true
        styleAs(appearance)
        setPadding(0, dimen(R.dimen.section_spacing), 0, dimen(R.dimen.space_xxs))
    }

    private fun caption(text: String) = TextView(this).apply {
        this.text = text
        styleAs(R.style.TextAppearance_Dementor_Caption)
    }

    companion object {
        /** Monday to Friday. */
        private val WEEKDAYS = setOf(
            DayOfWeek.MONDAY,
            DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY,
        )
        private val EVERY_DAY = DayOfWeek.entries.toSet()

        /**
         * The windows almost every family ends up describing.
         *
         * Times chosen to be plainly editable rather than plausibly correct —
         * nobody's day is exactly this, and the point is that the shape is
         * there and only the numbers need changing. Bedtime joins the three
         * the old dedicated schedules screen offered (#106): it used to have
         * its own always-on switch, but a switch is just a rule with one
         * shape, and this screen already offers every shape.
         */
        private val STARTERS = listOf(
            Starter(R.string.schedules_starter_school, WEEKDAYS, LocalTime.of(8, 30), LocalTime.of(15, 0)),
            Starter(R.string.schedules_starter_homework, WEEKDAYS, LocalTime.of(16, 0), LocalTime.of(17, 30)),
            Starter(R.string.schedules_starter_dinner, EVERY_DAY, LocalTime.of(18, 0), LocalTime.of(19, 0)),
            Starter(
                R.string.rules_bedtime_name,
                EVERY_DAY,
                Defaults.BEDTIME_START,
                Defaults.BEDTIME_END,
                fixedId = bedtimeRuleId(),
            ),
        )

        fun intent(context: Context, childId: DeviceId? = null) =
            RuleTargets.putExtra(Intent(context, ContextRulesActivity::class.java), childId)
    }
}
