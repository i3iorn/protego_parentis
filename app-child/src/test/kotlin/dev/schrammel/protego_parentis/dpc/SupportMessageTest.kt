package dev.schrammel.protego_parentis.dpc

import android.content.Context
import android.content.res.Configuration
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.policy.reconcile.SupportMessages
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.Locale

/**
 * The admin support messages, checked as resources rather than as behaviour.
 *
 * Android truncates a short support message past 200 characters and says nothing
 * about it, so an over-long string fails on the phone in the one place nobody is
 * looking: mid-sentence, in front of the child. There is no logic here to
 * exercise, and it is still worth a test.
 *
 * Both languages every time. The Swedish text runs longer than the English, and
 * it is the one an English-speaking author will not think to count.
 */
@RunWith(RobolectricTestRunner::class)
class SupportMessageTest {

    private val english: Context get() = RuntimeEnvironment.getApplication()

    /**
     * A second Context configured for Swedish, rather than switching the global
     * qualifiers and putting them back. Both strings are then readable in one
     * test without either reading depending on the order they happen in.
     */
    private val swedish: Context
        get() = english.createConfigurationContext(
            Configuration(english.resources.configuration)
                .apply { setLocale(Locale.forLanguageTag("sv")) },
        )

    private fun Context.shortMessage() = getString(R.string.support_message_short)

    private fun Context.longMessage() =
        getString(R.string.support_message_long, getString(R.string.app_name))

    @Test
    fun `the english short message fits what the platform will show`() {
        val message = english.shortMessage()
        assertTrue("${message.length} characters", message.length <= SupportMessages.SHORT_LIMIT)
    }

    @Test
    fun `the swedish short message fits what the platform will show`() {
        val message = swedish.shortMessage()
        assertTrue("${message.length} characters", message.length <= SupportMessages.SHORT_LIMIT)
    }

    /**
     * A missing `values-sv` entry falls back to English silently, which on this
     * particular string means a Swedish-speaking child reads the one thing the
     * platform was already getting wrong, in the wrong language.
     */
    @Test
    fun `both messages are translated rather than falling back`() {
        assertNotEquals(english.shortMessage(), swedish.shortMessage())
        assertNotEquals(english.longMessage(), swedish.longMessage())
    }

    /**
     * The long message names the app. A placeholder that went missing would leave
     * the one sentence explaining what manages this phone without its name in it.
     */
    @Test
    fun `the long message carries the app name in both languages`() {
        val appName = english.getString(R.string.app_name)
        listOf(english.longMessage(), swedish.longMessage()).forEach { message ->
            assertTrue(message.contains(appName))
            assertFalse("placeholder left unsubstituted", message.contains("%1"))
        }
    }

    @Test
    fun `neither message is blank`() {
        listOf(english, swedish).forEach {
            assertTrue(it.shortMessage().isNotBlank())
            assertTrue(it.longMessage().isNotBlank())
        }
    }
}
