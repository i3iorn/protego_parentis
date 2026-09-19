package dev.schrammel.protego_parentis.ui

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.model.Condition
import dev.schrammel.protego_parentis.model.ContextRule
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.RuleEffect
import dev.schrammel.protego_parentis.model.RuleTarget
import dev.schrammel.protego_parentis.security.DeviceRole
import dev.schrammel.protego_parentis.security.LocalDeviceId
import dev.schrammel.protego_parentis.security.RoleStore
import dev.schrammel.protego_parentis.sync.RosterStore
import dev.schrammel.protego_parentis.sync.core.DeviceId
import dev.schrammel.protego_parentis.sync.core.SyncRole
import dev.schrammel.protego_parentis.sync.core.Vouch
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * [AppControlActivity]'s blocked switch no longer owns its own storage — a
 * block it sets is an ordinary [ContextRule] at a well-known per-app id, the
 * same shape [dev.schrammel.protego_parentis.rules.LocalPolicyStore.migrateAppRulesToUnifiedRules]
 * produces from an old-format block. These pin [AppControlActivity.isBlocked]'s
 * read of that rule directly, without driving the async app-catalog load.
 */
@RunWith(RobolectricTestRunner::class)
class AppControlActivityTest {

    private val activity get() = Robolectric.buildActivity(AppControlActivity::class.java).get()

    private val pkg = PackageName("com.example.game")

    @Test
    fun `an app with a block rule at its well-known id reads as blocked`() {
        val rule = ContextRule(
            id = activity.blockRuleId(pkg),
            name = "Blocked",
            enabled = true,
            condition = Condition.Always,
            effect = RuleEffect.Block(),
            target = RuleTarget.Apps(setOf(pkg)),
        )

        assertTrue(activity.isBlocked(pkg, listOf(rule)))
    }

    @Test
    fun `an app with no rule at all reads as not blocked`() {
        assertFalse(activity.isBlocked(pkg, emptyList()))
    }

    @Test
    fun `a rule under a different id is not this screen's own block`() {
        val rule = ContextRule(
            id = "some-other-rule",
            name = "Unrelated",
            enabled = true,
            condition = Condition.Always,
            effect = RuleEffect.Block(),
            target = RuleTarget.Apps(setOf(pkg)),
        )

        assertFalse(activity.isBlocked(pkg, listOf(rule)))
    }

    @Test
    fun `a budget rule at the same id is not read as a block`() {
        val rule = ContextRule(
            id = activity.blockRuleId(pkg),
            name = "Budget",
            enabled = true,
            condition = Condition.Always,
            effect = RuleEffect.SafetyExempt,
            target = RuleTarget.Apps(setOf(pkg)),
        )

        assertFalse(activity.isBlocked(pkg, listOf(rule)))
    }

    @Test
    fun `each app's block rule id is stable and distinct`() {
        val other = PackageName("com.example.other")

        assertTrue(activity.blockRuleId(pkg) == activity.blockRuleId(pkg))
        assertFalse(activity.blockRuleId(pkg) == activity.blockRuleId(other))
    }
}

/**
 * The list-update button: only meaningful for a remote target, since this
 * device's own catalog ([dev.schrammel.protego_parentis.ui.rules.LocalAppCatalog])
 * reads the package manager directly and is never stale. Driven through
 * [onGatedContentVisible] like [AppPickerActivityTest], since
 * [AppControlActivity.supportsReadOnly] lets it render without a PIN unlock.
 */
@RunWith(RobolectricTestRunner::class)
class AppControlActivityRequestListButtonTest {

    private val context get() = RuntimeEnvironment.getApplication()

    private fun enrolChild(id: String) =
        RosterStore(context).enrol(Vouch(id = DeviceId(id), name = id, role = SyncRole.CHILD, publicKey = "key"))

    @After
    fun tearDown() {
        RoleStore(context).choose(DeviceRole.CHILD)
        RosterStore(context).forgetAll()
        LocalDeviceId.useInTests(null)
    }

    @Test
    fun `offers a list-update button for a remote target`() {
        RoleStore(context).choose(DeviceRole.PARENT)
        enrolChild("CHILD1")
        val activity = Robolectric.buildActivity(
            AppControlActivity::class.java,
            AppControlActivity.intent(context, DeviceId("CHILD1")),
        ).setup().get()

        val button = awaitRow(activity) {
            findButton(it, activity.getString(R.string.apps_request_list_update))
        }
        requireNotNull(button) { "a remote target should offer to request a fresh app list" }

        // Sync is not configured in a test, so publishing is a no-op inside
        // SyncPublisher rather than a network call — this pins that the button
        // is wired to something at all, and that clicking it does not crash.
        button.performClick()
        shadowOf(Looper.getMainLooper()).idleFor(6, java.util.concurrent.TimeUnit.SECONDS)
    }

    @Test
    fun `does not offer a list-update button for this device's own rules`() {
        RoleStore(context).choose(DeviceRole.CHILD)
        LocalDeviceId.useInTests("THISPHONE")
        val activity = Robolectric.buildActivity(
            AppControlActivity::class.java,
            AppControlActivity.intent(context),
        ).setup().get()

        // Waits on the section header rather than on the absence of a button,
        // so this cannot pass merely by asserting before the screen loaded.
        requireNotNull(awaitRow(activity) { findButton(it, activity.getString(R.string.context_tutorial_open)) }) {
            "the screen should have finished rendering"
        }
        assertNull(findButton(activity.window.decorView, activity.getString(R.string.apps_request_list_update)))
    }

    /** Mirrors [AppPickerActivityTest.awaitRow] — see its doc comment for why this is needed. */
    private fun <T> awaitRow(activity: Activity, find: (View) -> T?): T? {
        val main = shadowOf(Looper.getMainLooper())
        repeat(ATTEMPTS) {
            main.idle()
            find(activity.window.decorView)?.let { return it }
            Thread.sleep(POLL_MILLIS)
        }
        main.idle()
        return find(activity.window.decorView)
    }

    private fun findButton(view: View, text: String): Button? {
        if (view is Button && view.text == text) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findButton(view.getChildAt(i), text)?.let { return it }
            }
        }
        return null
    }

    private companion object {
        const val ATTEMPTS = 100
        const val POLL_MILLIS = 10L
    }
}
