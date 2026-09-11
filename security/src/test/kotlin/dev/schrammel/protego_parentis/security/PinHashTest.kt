package dev.schrammel.protego_parentis.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinHashTest {

    @Test
    fun `the same pin and salt hash the same way twice`() {
        val salt = PinHash.newSalt()

        assertTrue(PinHash.of("4821", salt).contentEquals(PinHash.of("4821", salt)))
    }

    @Test
    fun `matches accepts the correct pin`() {
        val salt = PinHash.newSalt()
        val hash = PinHash.of("4821", salt)

        assertTrue(PinHash.matches("4821", salt, hash))
    }

    @Test
    fun `matches refuses a wrong pin`() {
        val salt = PinHash.newSalt()
        val hash = PinHash.of("4821", salt)

        assertFalse(PinHash.matches("4822", salt, hash))
        assertFalse(PinHash.matches("1284", salt, hash))
        assertFalse(PinHash.matches("", salt, hash))
    }

    @Test
    fun `the same pin hashes differently under a different salt`() {
        // The whole point of a salt: two families choosing the same PIN must not
        // produce the same stored hash.
        val hashA = PinHash.of("4821", PinHash.newSalt())
        val hashB = PinHash.of("4821", PinHash.newSalt())

        assertFalse(hashA.contentEquals(hashB))
    }

    @Test
    fun `a hash for one pin does not match a different pin, even with the right salt`() {
        val salt = PinHash.newSalt()
        val hash = PinHash.of("4821", salt)

        assertFalse(PinHash.matches("9999", salt, hash))
    }

    @Test
    fun `newSalt never repeats and is the declared length`() {
        val salts = List(20) { PinHash.newSalt() }

        assertTrue(salts.all { it.size == PinHash.SALT_BYTES })
        // Not a proof of uniqueness, but 20 draws colliding on a
        // SecureRandom-backed 16-byte salt would itself indicate a real bug.
        val distinct = salts.map { it.toList() }.distinct()
        assertTrue(distinct.size == salts.size)
    }
}
