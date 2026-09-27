package dev.schrammel.protego_parentis.glucose

import dev.schrammel.protego_parentis.model.Condition
import java.util.Locale
import kotlin.math.roundToInt

/**
 * How a glucose level is shown and typed. Stored values are always mg/dL, the
 * unit Nightscout itself stores; this is only ever the conversion at the edge.
 */
enum class GlucoseUnit {
    MG_DL,
    MMOL_L,
    ;

    /** [mgdl] as this unit would write it, without the unit name. */
    fun format(mgdl: Int, locale: Locale = Locale.getDefault()): String = when (this) {
        MG_DL -> mgdl.toString()
        MMOL_L -> String.format(locale, "%.1f", mgdl / MGDL_PER_MMOL)
    }

    /**
     * What someone typed, in this unit, as mg/dL; null if it isn't a number
     * or is outside what a sensor reports. Takes a comma or a point, since a
     * Swedish keyboard writes "4,0".
     */
    fun parse(text: String): Int? {
        val number = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
        val mgdl = when (this) {
            MG_DL -> number.roundToInt()
            MMOL_L -> (number * MGDL_PER_MMOL).roundToInt()
        }
        return mgdl.takeIf { it in Condition.GLUCOSE_RANGE }
    }

    companion object {
        /** The factor Nightscout itself uses, so a threshold reads the same in both apps. */
        const val MGDL_PER_MMOL = 18.0

        /** Nightscout's `settings.units`: anything mentioning mmol is mmol/L, as in its own code. */
        fun fromNightscout(units: String?): GlucoseUnit? = when {
            units.isNullOrBlank() -> null
            units.lowercase(Locale.ROOT).contains("mmol") -> MMOL_L
            else -> MG_DL
        }
    }
}
