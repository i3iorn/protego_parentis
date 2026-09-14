package dev.schrammel.protego_parentis.diagnostics

import android.content.Context
import androidx.core.content.edit

/**
 * The one exception [DiagnosticEvent.EVALUATION_FAILED] itself cannot say.
 *
 * [Diagnostics] is deliberately a closed set of events with no free text —
 * see its own doc comment — so it stays safe to hand to a stranger. That is
 * also exactly why it cannot answer "what actually threw": the tick's own
 * catch block already logs the real exception with [android.util.Log], but
 * that only reaches a computer plugged in with USB debugging on, which a
 * managed phone's device owner is free to switch off (and, on a real
 * family's phone, does) — leaving nobody able to read it at all.
 *
 * This is not part of the shareable log by default. It holds one raw stack
 * trace, which can name a rule, a category or a place the exception happened
 * to be holding when it threw — content the child already sees on the Rules
 * screen, but not something this file should mix silently into a log whose
 * whole design promise is "nothing free-form". [DiagnosticsActivity] shows it
 * on its own, separately labelled, so showing it is still a choice rather
 * than something that just happens.
 */
object CrashCapture {

    private const val FILE_NAME = "crash_capture"
    private const val KEY_LAST_EVALUATION_FAILURE = "lastEvaluationFailure"

    /** Long enough for a real stack trace; short enough this never grows without bound. */
    private const val MAX_CHARS = 4000

    fun recordEvaluationFailure(context: Context, error: Throwable) {
        prefs(context).edit {
            putString(KEY_LAST_EVALUATION_FAILURE, error.stackTraceToString().take(MAX_CHARS))
        }
    }

    /** The most recent evaluation failure's stack trace, or null if none is on record. */
    fun lastEvaluationFailure(context: Context): String? =
        prefs(context).getString(KEY_LAST_EVALUATION_FAILURE, null)

    fun clear(context: Context) = prefs(context).edit { remove(KEY_LAST_EVALUATION_FAILURE) }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
}
