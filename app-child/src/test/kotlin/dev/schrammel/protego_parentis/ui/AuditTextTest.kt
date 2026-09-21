package dev.schrammel.protego_parentis.ui

import android.content.Context
import dev.schrammel.protego_parentis.model.AuditAction
import dev.schrammel.protego_parentis.model.AuditActor
import dev.schrammel.protego_parentis.model.AuditEntry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Instant

/**
 * [AuditText.describe]'s own `when` is exhaustive at compile time — adding an
 * action and not deciding how it reads is already a build failure. What this
 * pins is the one thing that check can't catch: that the format string
 * actually takes the arguments [AuditText] passes it, for the newest branch
 * (#207's [AuditAction.APP_UNINSTALLED_BY_CHILD]).
 */
@RunWith(RobolectricTestRunner::class)
class AuditTextTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `a child uninstalling a blocked app names the app in the sentence`() {
        val entry = AuditEntry(
            id = "e1",
            at = Instant.now(),
            actor = AuditActor.CHILD,
            action = AuditAction.APP_UNINSTALLED_BY_CHILD,
            subject = "Some Game",
        )

        val text = AuditText.describe(context, entry)

        assertTrue(text.contains("Some Game"))
    }
}
