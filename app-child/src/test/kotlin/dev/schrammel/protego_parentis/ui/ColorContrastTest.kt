package dev.schrammel.protego_parentis.ui

import android.graphics.Color
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import dev.schrammel.protego_parentis.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.math.pow

/**
 * A guard against a colour edit that makes text unreadable, in either theme.
 *
 * `values-night/colors.xml` is a separate palette, and nothing else checks the
 * two stay legible — a captions pass on white and a caption colour that only
 * works on white is exactly the kind of change that ships un-noticed. Every pair
 * below is text on a surface the app actually draws, checked to WCAG AA.
 *
 * The thresholds are the standard ones: 4.5:1 for ordinary text, which is what
 * all of these are.
 */
@RunWith(RobolectricTestRunner::class)
class ColorContrastTest {

    private fun colour(@ColorRes id: Int): Int =
        ContextCompat.getColor(RuntimeEnvironment.getApplication(), id)

    private fun luminance(colour: Int): Double {
        fun channel(value: Int): Double {
            val s = value / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(Color.red(colour)) +
                0.7152 * channel(Color.green(colour)) +
                0.0722 * channel(Color.blue(colour))
    }

    private fun contrast(foreground: Int, background: Int): Double {
        val lighter = maxOf(luminance(foreground), luminance(background))
        val darker = minOf(luminance(foreground), luminance(background))
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun assertReadable(@ColorRes foreground: Int, @ColorRes background: Int, what: String) {
        val ratio = contrast(colour(foreground), colour(background))
        assertTrue(
            "$what: contrast ${"%.2f".format(ratio)}:1 is below the 4.5:1 AA minimum",
            ratio >= 4.5,
        )
    }

    private fun checkBothSurfaces() {
        assertReadable(
            R.color.text_primary,
            R.color.window_background,
            "text_primary on window_background"
        )
        assertReadable(R.color.text_primary, R.color.card, "text_primary on card")
        assertReadable(
            R.color.text_secondary,
            R.color.window_background,
            "text_secondary on window_background"
        )
        assertReadable(R.color.text_secondary, R.color.card, "text_secondary on card")
        assertReadable(R.color.on_brand, R.color.brand, "on_brand on brand")
        assertReadable(R.color.state_ok, R.color.card, "state_ok on card")
        assertReadable(R.color.state_warning, R.color.card, "state_warning on card")
        assertReadable(R.color.state_critical, R.color.card, "state_critical on card")
    }

    @Test
    fun `light theme text is readable on every surface`() {
        RuntimeEnvironment.setQualifiers("+notnight")
        checkBothSurfaces()
    }

    @Test
    fun `dark theme text is readable on every surface`() {
        RuntimeEnvironment.setQualifiers("+night")
        checkBothSurfaces()
    }
}
