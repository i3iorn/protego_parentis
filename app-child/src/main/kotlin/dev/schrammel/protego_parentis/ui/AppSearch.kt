package dev.schrammel.protego_parentis.ui

import java.text.Normalizer
import java.util.Locale

/**
 * Narrowing a list of apps by what someone types.
 *
 * A real phone has 80–150 launchable apps, so every screen that lists them needs
 * this and they should all behave identically. Deliberately free of Android
 * types: the matching rule is the part worth testing, and it does not need a
 * device to be wrong.
 */
internal object AppSearch {

    private val COMBINING = Regex("\\p{Mn}+")

    /**
     * Lowercased and stripped of accents.
     *
     * The app ships in English and Swedish and app labels are localised, so
     * "Väder" has to be findable by typing "vader" — a parent hunting for one app
     * in a hundred should not have to reach for the right keyboard first.
     */
    fun normalize(text: String): String =
        COMBINING.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "")
            .lowercase(Locale.ROOT)

    /**
     * True when every word typed appears somewhere in the label or the package
     * name.
     *
     * Package name as well as label, because a parent looking for a specific app
     * often knows `com.instagram.android` when they cannot pick the icon out of a
     * list. Every word rather than any, so a second word narrows rather than
     * widens — typing more should never give more results.
     */
    fun matches(query: String, label: String, packageName: String): Boolean {
        val words = normalize(query).split(' ', '\t', '\n').filter(String::isNotEmpty)
        if (words.isEmpty()) return true
        val haystack = "${normalize(label)} ${normalize(packageName)}"
        return words.all(haystack::contains)
    }

    /** Filters an already-loaded list. Never re-reads `PackageManager`. */
    fun <T> filter(
        items: List<T>,
        query: String,
        label: (T) -> String,
        packageName: (T) -> String,
    ): List<T> =
        if (query.isBlank()) items else items.filter { matches(query, label(it), packageName(it)) }
}
