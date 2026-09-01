package dev.schrammel.protego_parentis.ui

import android.app.Activity
import android.app.LocaleConfig
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import dev.schrammel.protego_parentis.R
import java.util.Locale

/** A language the app can be shown in. A null [tag] means "follow the system". */
data class LanguageOption(
    val tag: String?,
    val label: String,
)

/**
 * Lets anyone holding the phone change the app's language.
 *
 * Deliberately *not* behind the parent PIN. Being able to read the rules you are
 * living under is the whole transparency premise, and a child who cannot read the
 * app cannot see what is being measured or ask for anything.
 *
 * The list is read from `res/xml/locales_config.xml` at runtime rather than
 * hard-coded, so shipping another language is a translation file and one line of
 * XML. Each language is named in itself — someone looking for Swedish is looking
 * for "Svenska", not "Swedish".
 */
object LanguagePicker {

    fun show(activity: Activity) {
        val options = available(activity)
        val current = AppCompatDelegate.getApplicationLocales()
            .takeUnless { it.isEmpty }
            ?.get(0)
            ?.language

        val selected = options.indexOfFirst { it.tag == current }.takeIf { it >= 0 } ?: 0

        AlertDialog.Builder(activity)
            .setTitle(R.string.language_title)
            .setSingleChoiceItems(
                options.map { it.label }.toTypedArray(),
                selected,
            ) { dialog, index ->
                apply(options[index].tag)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** "System default" first, then every shipped language, named in itself. */
    fun available(activity: Activity): List<LanguageOption> {
        val system = LanguageOption(null, activity.getString(R.string.language_system))

        val shipped = runCatching {
            val locales = LocaleConfig(activity).supportedLocales
            (0 until (locales?.size() ?: 0)).mapNotNull { index ->
                locales?.get(index)?.let { locale ->
                    LanguageOption(
                        tag = locale.language,
                        // Capitalised because several languages, Swedish included,
                        // do not capitalise their own name by default.
                        label = locale.getDisplayLanguage(locale)
                            .replaceFirstChar { it.titlecase(locale) },
                    )
                }
            }
        }.getOrDefault(emptyList())
            .distinctBy { it.tag }
            .sortedBy { it.label }

        return listOf(system) + shipped
    }

    /**
     * Applies the choice. Android persists it per app, so it survives restarts
     * and does not need storing alongside our own settings.
     */
    private fun apply(tag: String?) {
        AppCompatDelegate.setApplicationLocales(
            if (tag == null) {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(tag)
            },
        )
    }

    /** The language currently in use, for showing next to the button. */
    fun currentLabel(activity: Activity): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return activity.getString(R.string.language_system)

        val locale: Locale = locales[0] ?: return activity.getString(R.string.language_system)
        return locale.getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
    }
}
