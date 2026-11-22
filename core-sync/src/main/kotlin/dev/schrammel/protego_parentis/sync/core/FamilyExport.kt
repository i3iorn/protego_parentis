package dev.schrammel.protego_parentis.sync.core

import java.security.SecureRandom
import java.util.Base64

/**
 * Everything a family needs to bring a new (or wiped) phone back into the fold.
 *
 * The family token, the passphrase that unlocks it, and the relay's own
 * credentials if the relay demands any. With those three a phone rejoins the
 * topic exactly as a phone joining by code and passphrase would — which is the
 * point: migration must not be a back door. The phone's signing key is
 * deliberately **not** part of an export, so a restored phone arrives as a new
 * device, waits in the enrolment queue and is approved by a parent like any
 * other newcomer. One export file therefore cannot impersonate the phone that
 * wrote it.
 *
 * The file itself is sealed with its own passphrase — not necessarily the
 * family's — because it is the one artifact likely to be emailed, messaged or
 * left in a downloads folder. See [FamilyExports.seal] for what that costs and
 * why it is worth paying.
 */
data class FamilyExport(
    val token: FamilyToken,
    val passphrase: String,
    /** The relay's bearer token, or empty for no token. */
    val relayToken: String = "",
    /** The relay's basic-auth pair, or empty strings for none. */
    val relayUsername: String = "",
    val relayPassword: String = "",
    /** A name to suggest for the phone being restored, or empty. */
    val deviceName: String = "",
)

/**
 * Seals and opens [FamilyExport] files.
 *
 * The envelope is the same authenticated encryption the topic traffic uses —
 * [MessageCrypto]'s AES-256-GCM over a PBKDF2-derived key — wrapped in a
 * prefix, a version and a per-file random salt so one stolen file cannot speed
 * up attacks against any other. A file that was not produced by somebody holding
 * the export passphrase does not open; a file that was altered does not open;
 * and those two failures are deliberately indistinguishable, for the same
 * reason they are in [MessageCrypto].
 *
 * The cost of the derivation is [MessageCrypto.PBKDF2_ITERATIONS] — about a
 * second on a phone — paid once per export and once per import. For the single
 * most valuable file the family ever produces, that is the right trade.
 */
object FamilyExports {

    private const val PREFIX = "DEP1"
    private const val SALT_BYTES = 16
    private const val FIELD_SEPARATOR = '\u001F'

    /** Fixed field order of the sealed payload; versioned by [PREFIX]. */
    private const val FIELD_COUNT = 8

    /**
     * Seals an export into a self-contained file body.
     *
     * Throws if the export passphrase is too short or the export holds an
     * unusable token: an export that could never be opened back is a bug to
     * stop at the keyboard, not a file to hand someone.
     */
    fun seal(export: FamilyExport, exportPassphrase: String): String {
        require(export.token.isValid) { "not a usable family token" }
        require(export.passphrase.length >= MessageCrypto.MIN_PASSPHRASE_LENGTH) {
            "the family passphrase is too short to export"
        }
        requireRelayPair(export.relayToken, export.relayUsername, export.relayPassword)

        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val saltText = Base64.getUrlEncoder().withoutPadding().encodeToString(salt)
        val sealed = MessageCrypto.seal(
            MessageCrypto.deriveKey(exportPassphrase, saltText),
            encodePayload(export),
        )
        // The salt must be stored with the file: it is per-export, so the
        // importer cannot rederive the key without it.
        return "$PREFIX:$saltText:$sealed"
    }

    /**
     * Opens an export file body, or null.
     *
     * Null covers every failure — not ours, wrong version, wrong passphrase,
     * altered in transit, malformed fields — because the caller's response is
     * the same for all of them: the file did not restore, try again or give up.
     */
    fun open(body: String, exportPassphrase: String): FamilyExport? {
        val parts = body.trim().split(':')
        if (parts.size != 3 || parts[0] != PREFIX) return null

        val salt = runCatching {
            Base64.getUrlDecoder().decode(parts[1])
        }.getOrNull()
        if (salt == null || salt.size != SALT_BYTES) return null

        val key = runCatching {
            MessageCrypto.deriveKey(exportPassphrase, parts[1])
        }.getOrNull() ?: return null

        return when (val result = MessageCrypto.open(key, parts[2])) {
            is DecryptResult.Opened -> decodePayload(result.plaintext)
            is DecryptResult.Failed -> null
        }
    }

    private fun requireRelayPair(token: String, username: String, password: String) {
        val hasBasic = username.isNotBlank() || password.isNotBlank()
        require(!(hasBasic && (username.isBlank() || password.isBlank()))) {
            "basic-auth credentials need both a username and a password"
        }
    }

    private fun encodePayload(export: FamilyExport): String = listOf(
        export.token.kind.name,
        export.token.serverUrl,
        export.token.topic,
        export.passphrase,
        export.relayToken,
        export.relayUsername,
        export.relayPassword,
        export.deviceName,
    ).joinToString(FIELD_SEPARATOR.toString()) { escape(it) }

    private fun decodePayload(payload: String): FamilyExport? {
        val fields = payload.split(FIELD_SEPARATOR)
        if (fields.size != FIELD_COUNT) return null
        val values = fields.map(::unescape)
        val kind = TransportKind.parse(values[0]) ?: return null
        val token = runCatching { FamilyToken(kind, values[1], values[2]) }.getOrNull()
            ?: return null
        if (values[3].length < MessageCrypto.MIN_PASSPHRASE_LENGTH) return null
        return FamilyExport(
            token = token,
            passphrase = values[3],
            relayToken = values[4],
            relayUsername = values[5],
            relayPassword = values[6],
            deviceName = values[7],
        )
    }

    /**
     * Escapes the separator, the escape character and control characters, the
     * same approach [StateCodec] takes: values are data a person typed, and a
     * person can type anything.
     */
    private fun escape(value: String): String = buildString {
        for (character in value) {
            when {
                character == '\\' -> append("\\\\")
                character == FIELD_SEPARATOR || character < ' ' -> append("\\u%04x".format(character.code))
                else -> append(character)
            }
        }
    }

    private fun unescape(value: String): String {
        if ('\\' !in value) return value
        return buildString {
            var index = 0
            while (index < value.length) {
                val character = value[index]
                if (character != '\\' || index == value.lastIndex) {
                    append(character)
                    index++
                    continue
                }
                when (value[index + 1]) {
                    '\\' -> {
                        append('\\')
                        index += 2
                    }
                    'u' -> {
                        val code = value.substring(index + 2, index + 6).toIntOrNull(16)
                        if (code == null) {
                            append(character)
                            index++
                        } else {
                            append(code.toChar())
                            index += 6
                        }
                    }
                    else -> {
                        append(character)
                        index++
                    }
                }
            }
        }
    }
}
