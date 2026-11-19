package dev.schrammel.protego_parentis.sync

/**
 * How this phone authenticates to the family's ntfy relay.
 *
 * ntfy accepts either a single bearer token or a username/password pair.
 * Neither is part of the shared family code: a code only says where the topic
 * is and which backend carries it, while these credentials are this phone's own
 * proof that it may publish there and read from it. They travel separately,
 * sealed in this phone's keystore exactly like the family passphrase.
 *
 * A token and a username/password pair are mutually exclusive — ntfy honours
 * whichever header it finds first, and sending both would only be confusing.
 */
sealed class NtfyCredentials {

    /** No authentication: anyone who knows the topic name can publish and poll. */
    object None : NtfyCredentials()

    /** A single bearer token, sent as `Authorization: Bearer <token>`. */
    data class Token(val token: String) : NtfyCredentials() {
        init { require(token.isNotBlank()) { "token must not be blank" } }
    }

    /** A username/password pair, sent as `Authorization: Basic base64(user:pass)`. */
    data class Basic(val username: String, val password: String) : NtfyCredentials() {
        init {
            require(username.isNotBlank()) { "username must not be blank" }
            require(password.isNotBlank()) { "password must not be blank" }
        }
    }
}
