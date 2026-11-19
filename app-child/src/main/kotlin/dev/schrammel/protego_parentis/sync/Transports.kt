package dev.schrammel.protego_parentis.sync

import dev.schrammel.protego_parentis.sync.core.FamilyTransport
import dev.schrammel.protego_parentis.sync.core.TransportKind
/**
 * Picks the backend a family's settings name.
 *
 * One `when`, exhaustive, so adding a backend is a compile error in exactly one
 * place rather than a silent fallback to ntfy somewhere. That matters more than
 * it looks: falling back would send a family's traffic to a server they did not
 * choose, and it would do it quietly.
 *
 * Everything above this line — the sealing, the signing, the roster — is transport
 * independent already. A new backend implements two methods and changes nothing
 * about who can read a message or who is allowed to have sent it.
 */
object Transports {

    fun of(settings: SyncSettings): FamilyTransport = when (settings.transport) {
        TransportKind.NTFY -> NtfyTransport(settings.serverUrl, settings.topic, settings.credentials)
    }
}
