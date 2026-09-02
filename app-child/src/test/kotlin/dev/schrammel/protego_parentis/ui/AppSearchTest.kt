package dev.schrammel.protego_parentis.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [AppSearch] — the rule every app list shares for narrowing itself. */
class AppSearchTest {

    private data class App(val label: String, val pkg: String)

    private val apps = listOf(
        App("Instagram", "com.instagram.android"),
        App("Väder", "com.example.weather"),
        App("Clock", "com.android.deskclock"),
        App("Photos", "com.google.android.apps.photos"),
    )

    private fun filtered(query: String) =
        AppSearch.filter(apps, query, { it.label }, { it.pkg }).map { it.label }

    @Test
    fun `an empty query keeps everything`() {
        assertEquals(apps.map { it.label }, filtered(""))
    }

    @Test
    fun `whitespace alone keeps everything`() {
        assertEquals(apps.map { it.label }, filtered("   "))
    }

    @Test
    fun `matches a label regardless of case`() {
        assertEquals(listOf("Instagram"), filtered("INSTA"))
    }

    /** A parent often knows the package name when they cannot spot the icon. */
    @Test
    fun `matches a package name`() {
        assertEquals(listOf("Clock"), filtered("deskclock"))
    }

    @Test
    fun `matches a label part-way through`() {
        assertEquals(listOf("Photos"), filtered("hoto"))
    }

    /** The app ships in Swedish; the keyboard in use may not. */
    @Test
    fun `an accented label is found without the accent`() {
        assertEquals(listOf("Väder"), filtered("vader"))
    }

    @Test
    fun `an accented query still finds the accented label`() {
        assertEquals(listOf("Väder"), filtered("Väder"))
    }

    /** Typing more must narrow, never widen. */
    @Test
    fun `every word has to match`() {
        assertTrue(AppSearch.matches("insta android", "Instagram", "com.instagram.android"))
        assertFalse(AppSearch.matches("insta chrome", "Instagram", "com.instagram.android"))
    }

    @Test
    fun `a word may match the label and another the package name`() {
        assertTrue(AppSearch.matches("clock desk", "Clock", "com.android.deskclock"))
    }

    @Test
    fun `nothing matching gives an empty list rather than everything`() {
        assertEquals(emptyList<String>(), filtered("spreadsheet"))
    }

    /** The list arrives already ordered; filtering must not reshuffle it. */
    @Test
    fun `order is preserved`() {
        assertEquals(listOf("Instagram", "Clock", "Photos"), filtered("an"))
    }

    @Test
    fun `normalize strips accents and case`() {
        assertEquals("vader", AppSearch.normalize("Väder"))
        assertEquals("aao", AppSearch.normalize("ÅÄÖ"))
    }
}
