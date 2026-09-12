package dev.schrammel.protego_parentis.ui

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.security.DeviceRole
import dev.schrammel.protego_parentis.security.RoleStore
import dev.schrammel.protego_parentis.sync.core.DeviceId
import dev.schrammel.protego_parentis.security.LocalDeviceId
import dev.schrammel.protego_parentis.sync.RosterStore
import dev.schrammel.protego_parentis.sync.core.SyncRole
import dev.schrammel.protego_parentis.sync.core.Vouch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * #78 part 3: Rules owns every rule, on either kind of phone. The daily limit
 * and bonus time used to be the one thing a remote edit of [RulesActivity]
 * could not reach at all — this pins that both now resolve somewhere, and
 * resolve to the *right* somewhere: the local screen when
 * [dev.schrammel.protego_parentis.ui.rules.RuleScope] is this device's own,
 * the parent-side send-a-message screen when it is not.
 */
@RunWith(RobolectricTestRunner::class)
class RuleDestinationsTest {

    private val context get() = RuntimeEnvironment.getApplication()

    private fun enrolChild(id: String) =
        RosterStore(context).enrol(Vouch(id = DeviceId(id), name = id, role = SyncRole.CHILD, publicKey = "key"))

    private fun rulesActivity(): RulesActivity =
        Robolectric.buildActivity(RulesActivity::class.java, RulesActivity.intent(context)).setup().get()

    @After
    fun tearDown() {
        LocalDeviceId.useInTests(null)
        RosterStore(context).forgetAll()
        RoleStore(context).choose(DeviceRole.CHILD)
    }

    /**
     * #106: the daily limit, schedules, and context rules collapsed into one
     * screen — reached the same way regardless of which kind of phone edits
     * it, unlike the dedicated local/remote pair (`LimitsActivity`/
     * `ParentLimitActivity`, both since removed outright — the latter in
     * #107, the former in #162) it replaces.
     */
    @Test
    fun `on the managed phone itself, rules opens the unified screen`() {
        RoleStore(context).choose(DeviceRole.CHILD)
        LocalDeviceId.useInTests("THISPHONE")
        val activity = rulesActivity()

        click(activity, R.string.rules_row_rules)

        assertEquals(
            ContextRulesActivity::class.java.name,
            shadowOf(activity).nextStartedActivity?.component?.className,
        )
    }

    @Test
    fun `editing a child's rules remotely, rules opens the same unified screen`() {
        RoleStore(context).choose(DeviceRole.PARENT)
        enrolChild("CHILD1")
        val activity = rulesActivity()

        click(activity, R.string.rules_row_rules)

        assertEquals(
            ContextRulesActivity::class.java.name,
            shadowOf(activity).nextStartedActivity?.component?.className,
        )
    }

    @Test
    fun `on the managed phone itself, bonus time opens the local screen`() {
        RoleStore(context).choose(DeviceRole.CHILD)
        LocalDeviceId.useInTests("THISPHONE")
        val activity = rulesActivity()

        click(activity, R.string.rules_row_bonus)

        assertEquals(
            BonusTimeActivity::class.java.name,
            shadowOf(activity).nextStartedActivity?.component?.className,
        )
    }

    @Test
    fun `editing a child's rules remotely, bonus time sends a message instead`() {
        RoleStore(context).choose(DeviceRole.PARENT)
        enrolChild("CHILD1")
        val activity = rulesActivity()

        click(activity, R.string.rules_row_bonus)

        assertEquals(
            ParentBonusActivity::class.java.name,
            shadowOf(activity).nextStartedActivity?.component?.className,
        )
    }

    /**
     * #86: the arrival-announcement toggle only means anything on the device
     * doing the arriving, so it stays own-device-only — but the saved places
     * themselves are the same list Contextual rules already edits remotely,
     * so a remote target gets its own direct door to them instead.
     */
    @Test
    fun `on the managed phone itself, places opens arrival announcements`() {
        RoleStore(context).choose(DeviceRole.CHILD)
        LocalDeviceId.useInTests("THISPHONE")
        val activity = rulesActivity()

        click(activity, R.string.rules_row_places_alerts)

        assertEquals(
            PlaceAlertsActivity::class.java.name,
            shadowOf(activity).nextStartedActivity?.component?.className,
        )
    }

    @Test
    fun `editing a child's rules remotely, places opens place management directly`() {
        RoleStore(context).choose(DeviceRole.PARENT)
        enrolChild("CHILD1")
        val activity = rulesActivity()

        click(activity, R.string.rules_row_places)

        assertEquals(
            PlacesActivity::class.java.name,
            shadowOf(activity).nextStartedActivity?.component?.className,
        )
    }

    /** Clicks the row titled by [titleRes], the same way a tap on the screen would. */
    private fun click(activity: RulesActivity, titleRes: Int) {
        val title = activity.getString(titleRes)
        val row = requireNotNull(findClickableRow(activity.window.decorView, title)) {
            "no row titled \"$title\" was rendered"
        }
        row.performClick()
    }

    private fun findClickableRow(view: View, title: String): View? {
        if (view is TextView && view.text == title) {
            var candidate = view.parent as? View
            while (candidate != null && !candidate.isClickable) candidate = candidate.parent as? View
            return candidate
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findClickableRow(view.getChildAt(i), title)?.let { return it }
            }
        }
        return null
    }
}
