package dev.schrammel.protego_parentis.rules

import android.content.Context
import dev.schrammel.protego_parentis.sync.PolicyReplication

/**
 * The rules as this phone currently believes them, for a device that does not
 * enforce them.
 *
 * Once a separate cache written by whole-policy pushes; now a thin read over the
 * replicated state, because there is nothing left to cache separately — every
 * device holds the same registers, and a parent's copy is as current as its last
 * poll rather than as its last explicit request.
 *
 * Kept as a type of its own rather than folded into [PolicyReplication] so the
 * screens that only *read* the rules say so, and cannot accidentally be given
 * something that writes them.
 */
class PolicyMirrorStore(context: Context) {

    private val replication = PolicyReplication(context)

    /** Null until rules have replicated to this device at all. */
    val snapshot: PolicySnapshot?
        get() = replication.read()
}
