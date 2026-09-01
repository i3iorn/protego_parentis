package dev.schrammel.protego_parentis.sync

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The plumbing [SyncPublisher] and [SyncReceiver] both need and neither owns
 * alone: one background thread for all network work, and the derived family
 * key cached against it.
 *
 * Shared and process-wide rather than per-instance, for two reasons that both
 * matter. Several screens construct a coordinator, one of them on every
 * resume — a per-instance executor meant a leaked thread each time. And
 * single-threading publish and poll against *each other*, not only against
 * themselves, is what stops a poll's replay-guard write interleaving with a
 * publish that reads the same state.
 */
internal object SyncKeying {

    private val io: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dementor-sync").apply { isDaemon = true }
    }

    /** Guarded by [io] being single-threaded; never read from anywhere else. */
    @Volatile
    private var cachedKey: Pair<Pair<String, String>, SyncKey>? = null

    fun execute(task: () -> Unit) = io.execute(task)

    /**
     * The family key, derived once and kept.
     *
     * Deriving it stretches the passphrase with 210,000 rounds of PBKDF2, which is
     * the right cost to pay against someone guessing at the passphrase and quite
     * the wrong cost to pay every fifteen seconds — which is how often the
     * enforcement tick polls while a limit is close.
     *
     * Cached against the passphrase and topic it was derived from, so changing
     * either produces a new key rather than a stale one. Must only ever be called
     * from inside a task passed to [execute] — nothing here is otherwise safe for
     * concurrent access.
     */
    fun keyFor(settings: SyncSettings): SyncKey? {
        val identity = settings.passphrase to settings.topic
        cachedKey?.let { (forIdentity, key) -> if (forIdentity == identity) return key }

        val derived = runCatching {
            MessageCrypto.deriveKey(settings.passphrase, settings.topic)
        }.getOrNull() ?: return null

        cachedKey = identity to derived
        return derived
    }
}
