package dev.schrammel.dementor_watch.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sanitiser is the only thing standing between another phone's string and this
 * one's screen, so both halves of its job are tested: that ordinary messages come
 * through untouched, and that the specific tricks it exists to stop are stopped.
 */
class ChatTextTest {

    @Test
    fun `an ordinary message is left alone`() {
        assertEquals("Can I have 20 more minutes?", ChatText.sanitise("Can I have 20 more minutes?"))
    }

    @Test
    fun `every script a family might write in survives`() {
        listOf(
            "Nej, läggdags nu",
            "هذا نص عربي",
            "今晩は宿題をやりましたか",
            "Πού είσαι;",
            "Спокойной ночи",
        ).forEach { assertEquals(it, ChatText.sanitise(it)) }
    }

    // ---------- emoji ----------

    @Test
    fun `a plain emoji survives`() {
        assertEquals("goodnight 🌙", ChatText.sanitise("goodnight 🌙"))
    }

    @Test
    fun `a joined family emoji is not taken apart`() {
        // Four people and three zero-width joiners. Filtering format characters
        // without an exception for those would turn one figure into four.
        val family = "👨‍👩‍👧‍👦"
        assertEquals(family, ChatText.sanitise(family))
    }

    @Test
    fun `a skin tone modifier survives`() {
        val wave = "👋🏽"
        assertEquals(wave, ChatText.sanitise(wave))
    }

    @Test
    fun `a flag survives`() {
        // Two regional indicators, which are ordinary symbols rather than format
        // characters — worth pinning, because it looks like neither.
        val sweden = "🇸🇪"
        assertEquals(sweden, ChatText.sanitise(sweden))
    }

    @Test
    fun `an emoji variation selector survives`() {
        val heart = "❤️"
        assertEquals(heart, ChatText.sanitise(heart))
    }

    // ---------- the attacks ----------

    @Test
    fun `a right-to-left override cannot be sent`() {
        // The one that matters most: everything after U+202E renders reversed, so
        // a message can be made to read as something other than what it says.
        val spoofed = "Dad said yes‮on dias daD"
        val cleaned = checkNotNull(ChatText.sanitise(spoofed))
        assertTrue("override survived: $cleaned", cleaned.none { it == '‮' })
    }

    @Test
    fun `no format character survives except the emoji ones`() {
        val sneaky = "a​b‎c‏d‪e⁦f؜g﻿h"
        assertEquals("abcdefgh", ChatText.sanitise(sneaky))
    }

    @Test
    fun `control characters are dropped`() {
        // Built from the code point rather than typed. A raw control byte in a
        // source file survives some editors and not others, and the version of
        // this test that lost its payload would assert "ab" == "ab" and pass.
        listOf(0x00, 0x07, 0x1B, 0x7F, 0x9B).forEach { control ->
            assertEquals("ab", ChatText.sanitise("a" + codePoint(control) + "b"))
        }
    }

    @Test
    fun `a message of nothing but invisibles is not a message`() {
        assertNull(ChatText.sanitise("​‮ "))
        assertNull(ChatText.sanitise("   "))
        assertNull(ChatText.sanitise(""))
    }

    @Test
    fun `stacked combining marks are capped`() {
        val zalgo = "e" + "́".repeat(200)
        val cleaned = checkNotNull(ChatText.sanitise(zalgo))
        assertEquals(1 + ChatText.MAX_MARKS_PER_BASE, cleaned.length)
    }

    @Test
    fun `marks are counted per base character, not per message`() {
        // Two accented letters must not share one allowance between them.
        val accented = "éá"
        assertEquals(accented, ChatText.sanitise(accented))
    }

    @Test
    fun `exotic spaces become ordinary ones and runs collapse`() {
        assertEquals("hi there", ChatText.sanitise("hi  　 there"))
    }

    @Test
    fun `private use and unassigned code points are dropped`() {
        // U+E000 starts the basic private-use area, U+F0000 a supplementary
        // one, and U+0378 has never been assigned to anything at all.
        listOf(0xE000, 0xF0000, 0x0378).forEach { unwanted ->
            assertEquals("ab", ChatText.sanitise("a" + codePoint(unwanted) + "b"))
        }
    }

    /** Keeps hostile characters out of this file and in the assertion. */
    private fun codePoint(value: Int): String = String(Character.toChars(value))

    @Test
    fun `a lone surrogate is dropped rather than crashing`() {
        assertEquals("ab", ChatText.sanitise("a\uD83Db"))
    }

    // ---------- the rule a text box uses ----------

    @Test
    fun `a lone space survives the character rule`() {
        // The bug this pins: an input filter running the whole of sanitise would
        // see a space tidy away to nothing and refuse every space as it was typed,
        // leaving a chat box nobody could write two words in.
        assertEquals(" ", ChatText.keepCharacters(" "))
        assertEquals("hi  ", ChatText.keepCharacters("hi  "))
        assertEquals("a\n", ChatText.keepCharacters("a\n"))
    }

    @Test
    fun `the character rule still refuses what may not be shown`() {
        assertEquals("ab", ChatText.keepCharacters("a‮b"))
        assertEquals("", ChatText.keepCharacters("‮​"))
    }

    @Test
    fun `an ordinary keystroke comes back unchanged, so a filter can accept it`() {
        // Returning the input untouched is what lets the filter answer null, which
        // is what keeps a predictive-text IME from being disturbed mid-word.
        listOf("a", " ", "?", "🌙").forEach {
            assertEquals(it, ChatText.keepCharacters(it))
        }
    }

    // ---------- bounds ----------

    @Test
    fun `length is capped in code points, so an emoji costs one`() {
        val many = "🌙".repeat(ChatText.MAX_LENGTH * 2)
        val cleaned = checkNotNull(ChatText.sanitise(many))
        assertEquals(ChatText.MAX_LENGTH, cleaned.codePointCount(0, cleaned.length))
    }

    @Test
    fun `line count is capped`() {
        val tall = (1..100).joinToString("\n") { "line $it" }
        val cleaned = checkNotNull(ChatText.sanitise(tall))
        assertEquals(ChatText.MAX_LINES, cleaned.count { it == '\n' } + 1)
    }

    @Test
    fun `runs of blank lines collapse to one`() {
        assertEquals("a\n\nb", ChatText.sanitise("a\n\n\n\n\n\nb"))
    }

    @Test
    fun `windows line endings do not double up`() {
        assertEquals("a\nb", ChatText.sanitise("a\r\nb"))
    }

    @Test
    fun `a huge string of nothing but rubbish gives up rather than walking it all`() {
        // Every character is dropped, so nothing accumulates and only the scan
        // bound ends the loop. Without it this is a free denial of service against
        // every phone in the family.
        val flood = "‮".repeat(ChatText.MAX_SCAN * 10)
        assertNull(ChatText.sanitise(flood))
    }

    @Test
    fun `sanitising twice changes nothing the second time`() {
        // The receiver runs this over text the sender already ran it over. If the
        // two passes disagreed, a message would change shape in transit.
        listOf(
            "hello 🌙‮x",
            "a  b",
            "e" + "́".repeat(50),
            (1..40).joinToString("\n") { "l$it" },
        ).forEach { raw ->
            val once = checkNotNull(ChatText.sanitise(raw))
            assertEquals(once, ChatText.sanitise(once))
        }
    }
}
