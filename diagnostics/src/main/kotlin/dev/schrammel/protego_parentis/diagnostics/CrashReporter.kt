package dev.schrammel.protego_parentis.diagnostics

/**
 * Carries a crash report off the phone.
 *
 * Defined here rather than in the package that implements it because
 * [CrashRecovery] runs inside the crash itself, before any composition root
 * is guaranteed to exist — it receives a supplier of this interface and only
 * resolves it when a crash actually happens, exactly as it does for
 * [RestartLauncher].
 */
fun interface CrashReporter {

    /**
     * Publishes what just threw. Called on the crashing thread inside the
     * crash handler: implementations must never throw (the caller wraps them
     * anyway) and should treat a failed network write as recoverable later —
     * the crash is recorded locally first, so a later flush can retry.
     */
    fun report(thread: String, throwable: Throwable)
}
