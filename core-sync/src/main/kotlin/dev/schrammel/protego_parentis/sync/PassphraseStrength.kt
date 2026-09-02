package dev.schrammel.protego_parentis.sync

import dev.schrammel.protego_parentis.model.Passphrases

/** Why a passphrase is not worth offering a family. */
enum class Weakness {
    /** Shorter than the derivation will accept at all. */
    TOO_SHORT,

    /** Long, but built from a handful of characters: `aaaaaaaaaaaa`. */
    TOO_FEW_DISTINCT,

    /** One short unit repeated to length: `abababababab`. */
    REPEATED_UNIT,

    /** A walk along the keyboard or the alphabet: `qwertyuiop`, `123456789`. */
    RUN,
}

/** How much a passphrase is worth, coarsely. */
enum class Band { WEAK, FAIR, STRONG }

sealed interface PassphraseVerdict {
    /** Refused outright. Nothing this bad should become a family's only secret. */
    data class Unusable(val why: Weakness) : PassphraseVerdict

    /** Allowed, with an opinion attached. */
    data class Usable(val band: Band) : PassphraseVerdict
}

/**
 * A judgement on a passphrase somebody typed instead of using the suggestion.
 *
 * ## What this can and cannot do
 *
 * It is worth being plain about the limit, because the obvious example is one it
 * does not catch. `familysmith1` is twelve characters, ten of them distinct, no
 * repetition and no run — structurally it is indistinguishable from a good
 * passphrase, and only *meaning* separates them. Recognising it as a surname and
 * a year needs a dictionary of names and common words, and a segmentation pass
 * to find them inside a run-together string. That is real work and a real
 * download, and none of it is here.
 *
 * So this refuses what is bad by construction and gives an opinion on the rest.
 * The opinion is deliberately coarse: a bit count would imply a precision this
 * does not have, and a strength meter that says "58 bits" about a phrase it has
 * not understood is worse than one that says "weak".
 *
 * The one case it judges exactly is the app's own suggestion, because there the
 * arithmetic is known — six words drawn uniformly from 256 is 48 bits, and
 * [Passphrases] guarantees the list is exactly that size.
 *
 * Pure and in core-sync so it can be tested without a phone, next to the
 * derivation whose minimum length it shares.
 */
object PassphraseStrength {

    /** Fewer distinct characters than this and length is not doing any work. */
    const val MIN_DISTINCT = 5

    /** A sequential walk this long is a pattern, not a choice. */
    const val MAX_RUN = 6

    /** Rows of a qwerty keyboard, for spotting a finger dragged along one. */
    private val ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm", "1234567890")

    fun assess(passphrase: String): PassphraseVerdict {
        val text = passphrase.trim()

        if (text.length < MessageCrypto.MIN_PASSPHRASE_LENGTH) {
            return PassphraseVerdict.Unusable(Weakness.TOO_SHORT)
        }
        if (text.toSet().size < MIN_DISTINCT) {
            return PassphraseVerdict.Unusable(Weakness.TOO_FEW_DISTINCT)
        }
        if (isOneUnitRepeated(text)) {
            return PassphraseVerdict.Unusable(Weakness.REPEATED_UNIT)
        }
        if (hasRun(text.lowercase())) {
            return PassphraseVerdict.Unusable(Weakness.RUN)
        }
        return PassphraseVerdict.Usable(bandOf(text))
    }

    /**
     * Whether this is the suggestion, unedited.
     *
     * Every word drawn from the list and at least as many as [Passphrases.WORDS],
     * which is the only case where the entropy is known rather than guessed at.
     */
    fun isSuggested(passphrase: String): Boolean {
        val words = passphrase.trim().lowercase().split(" ").filter(String::isNotEmpty)
        if (words.size < Passphrases.WORDS) return false
        val known = Passphrases.words.toSet()
        return words.all { it in known }
    }

    /**
     * A coarse opinion, and coarse on purpose.
     *
     * Word count carries more than length does — `correct horse battery staple`
     * beats a shorter string of punctuation — so it is counted first, with
     * length as the fallback for anything typed as one run of characters.
     */
    private fun bandOf(text: String): Band {
        if (isSuggested(text)) return Band.STRONG

        val words = text.split(" ", "-", "_").filter(String::isNotEmpty)
        return when {
            words.size >= 5 && text.length >= 24 -> Band.STRONG
            words.size >= 3 || text.length >= 20 -> Band.FAIR
            else -> Band.WEAK
        }
    }

    /** Whether the whole string is one unit written out again and again. */
    private fun isOneUnitRepeated(text: String): Boolean {
        for (unit in 1..text.length / 2) {
            if (text.length % unit != 0) continue
            val head = text.take(unit)
            if ((0 until text.length / unit).all { text.regionMatches(it * unit, head, 0, unit) }) {
                return true
            }
        }
        return false
    }

    /** Whether any [MAX_RUN]-long stretch walks the alphabet or a keyboard row. */
    private fun hasRun(text: String): Boolean {
        if (sequentialRun(text) >= MAX_RUN) return true
        return ROWS.any { row ->
            (0..row.length - MAX_RUN).any { start ->
                val stretch = row.substring(start, start + MAX_RUN)
                text.contains(stretch) || text.contains(stretch.reversed())
            }
        }
    }

    /** The longest stretch whose characters each step one place from the last. */
    private fun sequentialRun(text: String): Int {
        var longest = 1
        var current = 1
        for (index in 1 until text.length) {
            val step = text[index].code - text[index - 1].code
            current = if (step == 1 || step == -1) current + 1 else 1
            longest = maxOf(longest, current)
        }
        return longest
    }
}
