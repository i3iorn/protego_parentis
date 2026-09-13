package dev.schrammel.protego_parentis.ui

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.rules.GrantStore
import dev.schrammel.protego_parentis.rules.LocalPolicyStore
import dev.schrammel.protego_parentis.rules.StoredCategory
import dev.schrammel.protego_parentis.security.ParentSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.time.Instant
import java.util.concurrent.TimeUnit

/** [BonusTimeActivity]'s device/category grant buttons and its "clear" action. */
@RunWith(RobolectricTestRunner::class)
class BonusTimeActivityTest {

    private val context get() = RuntimeEnvironment.getApplication()

    private fun grants() = GrantStore(context)

    @Before
    fun setUp() = ParentSession.unlock()

    @After
    fun tearDown() = ParentSession.lock()

    private fun activity() =
        Robolectric.buildActivity(BonusTimeActivity::class.java, BonusTimeActivity.intent(context)).setup().get()

    @Test
    fun `tapping a device amount grants that much bonus time`() {
        val activity = activity()

        clickButton(activity, "+10 min")

        val active = grants().active(Instant.now())
        assertEquals(1, active.size)
        assertEquals(10, active.single().amount.inWholeMinutes.toInt())
    }

    @Test
    fun `with no categories set up, the category section explains why instead of vanishing`() {
        val activity = activity()

        val texts = findAll(activity.window.decorView) { it is TextView }
            .filterIsInstance<TextView>()
            .map { it.text.toString() }

        assertTrue(texts.contains(activity.getString(R.string.bonus_category_header)))
        assertTrue(texts.contains(activity.getString(R.string.bonus_no_categories)))
    }

    @Test
    fun `each category gets its own amount row, alongside the device row`() {
        LocalPolicyStore(context).categories = listOf(
            StoredCategory(id = "games-id", name = "Games", limitMinutes = 60, packages = emptySet()),
        )
        val activity = activity()

        // One device row plus one category row, each offering the same amounts —
        // two "+15 min" buttons rather than one confirms the category row was
        // actually rendered, not just that "a" grant button exists somewhere.
        val fifteenMinButtons = findAll(activity.window.decorView) {
            it is Button && it.text.toString() == "+15 min"
        }
        assertEquals(2, fifteenMinButtons.size)
    }

    @Test
    fun `granting bonus time to a category records it under that target`() {
        LocalPolicyStore(context).categories = listOf(
            StoredCategory(id = "games-id", name = "Games", limitMinutes = 60, packages = emptySet()),
        )
        val activity = activity()

        // The second "+10 min" button belongs to the category row: the device
        // row is built first and always offers the same amounts.
        val tenMinButtons = findAll(activity.window.decorView) {
            it is Button && it.text.toString() == "+10 min"
        }.filterIsInstance<Button>()
        tenMinButtons[1].performClick()

        val active = grants().active(Instant.now())
        assertEquals(1, active.size)
        assertTrue(
            "the grant should target the category, not the whole device",
            active.single().target is dev.schrammel.protego_parentis.model.GrantTarget.Category,
        )
    }

    @Test
    fun `a fast double-tap on the same amount grants it only once`() {
        val activity = activity()
        val button = findAll(activity.window.decorView) { it is Button && it.text.toString() == "+10 min" }
            .filterIsInstance<Button>()
            .first()

        button.performClick()
        button.performClick()

        assertEquals(1, grants().active(Instant.now()).size)
    }

    @Test
    fun `a second tap after the debounce window still grants again`() {
        val activity = activity()
        val button = findAll(activity.window.decorView) { it is Button && it.text.toString() == "+10 min" }
            .filterIsInstance<Button>()
            .first()

        button.performClick()
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        button.performClick()

        assertEquals(2, grants().active(Instant.now()).size)
    }

    @Test
    fun `clear removes every grant made today`() {
        val activity = activity()
        clickButton(activity, "+10 min")
        assertTrue(grants().active(Instant.now()).isNotEmpty())

        clickButton(activity, "Remove bonus time for today")

        assertTrue(grants().active(Instant.now()).isEmpty())
    }

    private fun clickButton(activity: BonusTimeActivity, text: String) {
        val button = findAll(activity.window.decorView) { it is Button && it.text.toString() == text }
            .filterIsInstance<Button>()
            .firstOrNull()
        requireNotNull(button) { "no button titled \"$text\" was rendered" }.performClick()
    }

    private fun findAll(view: View, predicate: (View) -> Boolean): List<View> {
        val matches = mutableListOf<View>()
        fun walk(v: View) {
            if (predicate(v)) matches += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(view)
        return matches
    }
}
