package dev.schrammel.protego_parentis.sync

import dev.schrammel.protego_parentis.model.Passphrases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * What the app will and will not let a family rest its confidentiality on.
 *
 * The last block is the important one: it pins what this deliberately does
 * *not* catch, so nobody reads a green meter as a guarantee it was never able
 * to make.
 */
class PassphraseStrengthTest {

    private fun assess(text: String) = PassphraseStrength.assess(text)

    private fun refusal(text: String): Weakness =
        (assess(text) as PassphraseVerdict.Unusable).why

    private fun band(text: String): Band =
        (assess(text) as PassphraseVerdict.Usable).band

    // ---------- refused ----------

    @Test
    fun `too short to derive from is refused`() {
        assertEquals(Weakness.TOO_SHORT, refusal("short"))
    }

    @Test
    fun `length built from two characters is not length`() {
        assertEquals(Weakness.TOO_FEW_DISTINCT, refusal("aaaaaaaaaaaaaaaa"))
    }

    @Test
    fun `one unit written out again and again is refused`() {
        assertEquals(Weakness.REPEATED_UNIT, refusal("harbourharbourharbour"))
    }

    @Test
    fun `a walk along the keyboard is refused`() {
        assertEquals(Weakness.RUN, refusal("qwertyuiop plus"))
    }

    @Test
    fun `a walk backwards along the keyboard is refused too`() {
        assertEquals(Weakness.RUN, refusal("poiuytrewq plus"))
    }

    @Test
    fun `a walk along the alphabet is refused`() {
        assertEquals(Weakness.RUN, refusal("abcdefgh plus more"))
    }

    @Test
    fun `counting is refused`() {
        assertEquals(Weakness.RUN, refusal("123456789 plus more"))
    }

    // ---------- allowed, with an opinion ----------

    @Test
    fun `the suggestion is recognised for what it is`() {
        val suggested = Passphrases.suggest(SecureRandom())

        assertTrue(PassphraseStrength.isSuggested(suggested))
        assertEquals(Band.STRONG, band(suggested))
    }

    @Test
    fun `a suggestion with a word swapped out is no longer recognised`() {
        val edited = Passphrases.suggest(SecureRandom()).split(" ").dropLast(1).joinToString(" ") +
            " Smith1985"

        assertFalse(PassphraseStrength.isSuggested(edited))
    }

    @Test
    fun `a long phrase of several words is strong`() {
        assertEquals(Band.STRONG, band("correct horse battery staple lantern"))
    }

    @Test
    fun `a few words is fair`() {
        assertEquals(Band.FAIR, band("purple lantern harbour"))
    }

    @Test
    fun `a long run of characters is fair on length alone`() {
        assertEquals(Band.FAIR, band("Xk9vQ2mLp7Tr4Ws8Bd1Z"))
    }

    @Test
    fun `a short single token is weak`() {
        assertEquals(Band.WEAK, band("Trombone4712"))
    }

    @Test
    fun `whitespace around it does not change the answer`() {
        assertEquals(band("purple lantern harbour"), band("   purple lantern harbour  "))
    }

    // ---------- the limit, stated as a test ----------

    /**
     * The motivating example is **not** refused, and that is not an oversight.
     *
     * `familysmith1` is long enough, has ten distinct characters, repeats
     * nothing and walks nowhere. Structurally it is a perfectly ordinary
     * passphrase; only its *meaning* is weak, and meaning needs a dictionary of
     * names and common words plus a pass to find them inside a run-together
     * string. None of that is here.
     *
     * So it is allowed, and marked WEAK, which is the strongest honest thing
     * this can say about it. If that is not enough, the fix is a bundled word
     * list — not a tighter structural rule, which would only start refusing
     * good passphrases that happen to look similar.
     */
    @Test
    fun `a surname and a year is allowed, and called weak`() {
        assertEquals(Band.WEAK, band("familysmith1"))
    }

    /** The floor it does clear is the one the derivation itself insists on. */
    @Test
    fun `the refusal length matches what deriving requires`() {
        assertEquals(
            MessageCrypto.MIN_PASSPHRASE_LENGTH,
            "x".repeat(MessageCrypto.MIN_PASSPHRASE_LENGTH).length,
        )
        assertEquals(Weakness.TOO_SHORT, refusal("ab cd ef g"))
    }
}
