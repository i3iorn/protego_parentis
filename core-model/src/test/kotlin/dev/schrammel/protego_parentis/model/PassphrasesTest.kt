package dev.schrammel.protego_parentis.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * The word list carries a claim — 256 words, one byte each, 48 bits in six — and
 * the claim is repeated to the user. These tests are what stop the list drifting
 * away from it during an ordinary edit, which nothing else in the build would
 * notice.
 */
class PassphrasesTest {

    @Test
    fun `the list is exactly one byte wide`() {
        assertEquals(Passphrases.ALPHABET_SIZE, Passphrases.words.size)
    }

    @Test
    fun `no word appears twice`() {
        // A duplicate would quietly halve that word's contribution and make the
        // advertised entropy an overstatement.
        assertEquals(Passphrases.words.size, Passphrases.words.toSet().size)
    }

    @Test
    fun `every word can be said and typed without being spelled out`() {
        Passphrases.words.forEach { word ->
            assertTrue("not lower case: $word", word == word.lowercase())
            assertTrue("not plain letters: $word", word.all { it in 'a'..'z' })
            assertTrue("too short to hear clearly: $word", word.length >= 4)
            assertTrue("too long to read aloud comfortably: $word", word.length <= 8)
        }
    }

    @Test
    fun `a suggestion has the advertised shape`() {
        val suggestion = Passphrases.suggest()
        val words = suggestion.split(Passphrases.SEPARATOR)

        assertEquals(Passphrases.WORDS, words.size)
        assertTrue(words.all { it in Passphrases.words })
    }

    @Test
    fun `a suggestion is long enough for the crypto layer to accept`() {
        // The join screen refuses anything shorter than MessageCrypto's minimum.
        // A generator that produced a phrase its own app rejects would be a
        // spectacular way to fail.
        repeat(50) {
            assertTrue(Passphrases.suggest().length >= MIN_ACCEPTED_LENGTH)
        }
    }

    @Test
    fun `two suggestions are not the same`() {
        // Not a distribution test — just the one failure that would look like it
        // was working: a generator seeded once, returning a constant.
        val seen = (1..20).map { Passphrases.suggest(SecureRandom()) }.toSet()
        assertTrue("suggestions repeated: $seen", seen.size > 1)
    }

    private companion object {
        /** Kept in step with MessageCrypto.MIN_PASSPHRASE_LENGTH by the join screen. */
        const val MIN_ACCEPTED_LENGTH = 12
    }
}
