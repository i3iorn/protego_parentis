package dev.schrammel.dementor_watch.model

/**
 * What this app is willing to call a message.
 *
 * The chat carries text and nothing else. Not text-with-attachments, not text
 * that might later grow a file field — text. There is no image path, no download,
 * no MIME type, no link preview, no HTML, and no markup renderer anywhere between
 * the network and the `TextView` the words end up in. That is the actual defence:
 * not a filter clever enough to spot a disguised payload, but the absence of any
 * code that would do something with one if it arrived.
 *
 * What a filter *can* do is stop text from lying about itself, and that is this
 * object's job. Message text arrives from another phone in the family. That phone
 * is enrolled, signed and authorised — but "trusted enough to send the rules" is
 * not the same as "trusted to hand this device an arbitrary string", and a family
 * member's phone can be lost, borrowed or compromised. So everything below assumes
 * the string is hostile and asks only whether it can be shown safely.
 *
 * The rules, and the attack each one closes:
 *
 *  - **Format characters go**, apart from the two emoji genuinely need. This is
 *    the important one. U+202E and its relatives reverse the direction of the text
 *    that follows, which is how a message can be made to display as though someone
 *    else wrote it, or to hide a second sentence behind the first. They are
 *    invisible, so nothing on screen would give the trick away.
 *  - **Control characters go.** Nothing between the wire and the screen interprets
 *    them, but a string of them makes a message that is present in the history and
 *    invisible in the list, which is a confusing thing to be able to send.
 *  - **Private-use and unassigned code points go.** They render as the font's
 *    fallback box, differently on every device, and mean nothing.
 *  - **Combining marks are capped per base character.** A few hundred stacked on
 *    one letter — "Zalgo" — is not expressive, it is a way to make one row of a
 *    list swallow the screen and, on some text engines, to make layout take long
 *    enough to notice.
 *  - **Every kind of space becomes an ordinary one, and runs of them collapse.**
 *    Otherwise a message can be padded to any width with characters that are
 *    invisible but not blank, which is both a layout attack and a way to push the
 *    real content out of view.
 *  - **Lines and length are bounded**, so no single message can own the screen.
 *
 * What deliberately survives: every script, because a family does not owe anyone
 * an explanation for writing in Arabic or Japanese; and emoji in full, including
 * sequences joined with zero-width joiners, skin-tone modifiers, flags and
 * variation selectors, because "text and emojis" was the requirement and a filter
 * that mangles 👨‍👩‍👧 has not met it.
 *
 * Returns null when nothing survives. A message that sanitises to nothing is not
 * an empty message, it is a message that was never anything else.
 */
object ChatText {

    /**
     * Code points, not characters, and not bytes.
     *
     * Long enough for a real exchange between a parent and a child; short enough
     * that a hostile sender cannot use one message to make the history unusable.
     * Counting code points rather than UTF-16 units means an emoji costs one, so
     * the limit means the same thing whichever language a family writes in.
     */
    const val MAX_LENGTH = 500

    /** Enough for a short note. A message is not a document. */
    const val MAX_LINES = 12

    /**
     * How much of a hostile string is worth reading before giving up.
     *
     * Dropped characters cost nothing to keep, so without this a sender could hand
     * over ten megabytes of direction overrides and every receiving phone would
     * walk the whole thing to produce an empty message. Twenty times the limit
     * leaves generous room for a real message full of things that get tidied.
     */
    const val MAX_SCAN = MAX_LENGTH * 20

    /**
     * How many marks may stack on one base character.
     *
     * Four covers the languages that genuinely stack — Thai, Vietnamese,
     * Devanagari, Hebrew with points — and stops well short of the hundreds it
     * takes to make a row unbounded.
     */
    const val MAX_MARKS_PER_BASE = 4

    /** Joins the parts of a multi-person or professional emoji. */
    private const val ZERO_WIDTH_JOINER = 0x200D

    /** Chooses the emoji or the text presentation of the character before it. */
    private const val VARIATION_SELECTOR_TEXT = 0xFE0E
    private const val VARIATION_SELECTOR_EMOJI = 0xFE0F

    private const val NEWLINE = '\n'.code
    private const val CARRIAGE_RETURN = '\r'.code
    private const val TAB = '\t'.code
    private const val LINE_SEPARATOR = 0x2028
    private const val PARAGRAPH_SEPARATOR = 0x2029

    /**
     * The message this app is prepared to show, or null if there is none.
     *
     * Applied twice on purpose: once when the sender types, so nobody sends
     * something that will not survive the trip, and again on arrival, because the
     * phone at the other end is exactly what cannot be relied on.
     *
     * Idempotent, and tested to be: the receiver runs it over text the sender
     * already ran it over, and a message that changed shape in transit would be a
     * message nobody could quote back.
     */
    fun sanitise(raw: String): String? = tidy(keepCharacters(raw)).takeIf(String::isNotEmpty)

    /**
     * Drops what may not be shown, and changes nothing else.
     *
     * Separate from [sanitise] because a text box needs the character rule without
     * the tidying. Running the whole of [sanitise] over each keystroke would reject
     * the space bar: a lone space tidies away to nothing, so the field would refuse
     * every space the moment it was typed and nobody could write two words.
     *
     * The tidying is a judgement about a finished message. This is a judgement
     * about a character, and it is the only one that can be made mid-sentence.
     */
    fun keepCharacters(raw: String): String {
        val kept = StringBuilder()
        var marksOnCurrentBase = 0
        var index = 0
        var scanned = 0
        // Counted as we go rather than measured each pass: asking a builder for its
        // code point count inside its own filling loop is quadratic, which is a
        // denial of service the sanitiser would be performing on itself.
        var length = 0

        while (index < raw.length && length < MAX_LENGTH && scanned < MAX_SCAN) {
            val codePoint = raw.codePointAt(index)
            index += Character.charCount(codePoint)
            scanned++

            when {
                codePoint == CARRIAGE_RETURN -> Unit // \r\n arrives as one newline.

                codePoint == NEWLINE ||
                    codePoint == LINE_SEPARATOR ||
                    codePoint == PARAGRAPH_SEPARATOR -> {
                    marksOnCurrentBase = 0
                    length++
                    kept.append('\n')
                }

                // Every other flavour of space, tab included, becomes the ordinary
                // one. There is no legitimate message that needs an ideographic
                // space specifically, and several dishonest ones that do.
                codePoint == TAB ||
                    Character.getType(codePoint) == Character.SPACE_SEPARATOR.toInt() -> {
                    marksOnCurrentBase = 0
                    length++
                    kept.append(' ')
                }

                isMark(codePoint) -> {
                    // Dropped rather than the whole message rejected: a legitimate
                    // sender who trips this has a typo, not an attack, and losing
                    // the fifth mark is kinder than losing the sentence.
                    if (marksOnCurrentBase < MAX_MARKS_PER_BASE) {
                        marksOnCurrentBase++
                        length++
                        kept.appendCodePoint(codePoint)
                    }
                }

                isAllowed(codePoint) -> {
                    marksOnCurrentBase = 0
                    length++
                    kept.appendCodePoint(codePoint)
                }

                // Anything else — controls, direction overrides, private use,
                // unassigned, lone surrogates — is not text and is not carried.
                else -> Unit
            }
        }

        return kept.toString()
    }

    /**
     * Whether a code point is a character rather than an instruction.
     *
     * Written as an allowlist of Unicode general categories. A denylist would have
     * to be revised every time Unicode adds something, and the failure mode of
     * forgetting is that the new thing is shown.
     */
    private fun isAllowed(codePoint: Int): Boolean {
        // The two exceptions. Both are format characters, both are structural to
        // emoji, and neither can move or reorder anything around it.
        if (codePoint == ZERO_WIDTH_JOINER ||
            codePoint == VARIATION_SELECTOR_TEXT ||
            codePoint == VARIATION_SELECTOR_EMOJI
        ) {
            return true
        }

        return when (Character.getType(codePoint).toByte()) {
            Character.UPPERCASE_LETTER,
            Character.LOWERCASE_LETTER,
            Character.TITLECASE_LETTER,
            Character.MODIFIER_LETTER,
            Character.OTHER_LETTER,
            Character.DECIMAL_DIGIT_NUMBER,
            Character.LETTER_NUMBER,
            Character.OTHER_NUMBER,
            Character.CONNECTOR_PUNCTUATION,
            Character.DASH_PUNCTUATION,
            Character.START_PUNCTUATION,
            Character.END_PUNCTUATION,
            Character.INITIAL_QUOTE_PUNCTUATION,
            Character.FINAL_QUOTE_PUNCTUATION,
            Character.OTHER_PUNCTUATION,
            Character.MATH_SYMBOL,
            Character.CURRENCY_SYMBOL,
            // Skin tones and the emoji keycap modifiers live here.
            Character.MODIFIER_SYMBOL,
            // Emoji proper, and regional indicators, which is how flags are built.
            Character.OTHER_SYMBOL,
            -> true

            else -> false
        }
    }

    private fun isMark(codePoint: Int): Boolean = when (Character.getType(codePoint).toByte()) {
        Character.NON_SPACING_MARK,
        Character.COMBINING_SPACING_MARK,
        Character.ENCLOSING_MARK,
        -> true

        else -> false
    }

    /**
     * Squeezes the whitespace out, once the characters are known to be safe.
     *
     * Runs of spaces collapse, trailing spaces on a line go, blank lines collapse
     * to one, and the whole thing is trimmed and capped at [MAX_LINES]. Done after
     * filtering rather than before: dropping an invisible character can leave two
     * spaces where there was one, and only this pass sees that.
     */
    private fun tidy(text: String): String = text
        .split('\n')
        .map { line -> line.split(' ').filter(String::isNotEmpty).joinToString(" ") }
        .fold(mutableListOf<String>()) { lines, line ->
            // One blank line between paragraphs is punctuation; six is a way of
            // pushing the previous message off the screen.
            if (line.isNotEmpty() || lines.lastOrNull()?.isNotEmpty() == true) lines.add(line)
            lines
        }
        .take(MAX_LINES)
        .joinToString("\n")
        .trim()
}
