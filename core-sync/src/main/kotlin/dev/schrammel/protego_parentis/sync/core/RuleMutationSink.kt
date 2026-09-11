package dev.schrammel.protego_parentis.sync.core

/**
 * Somewhere to say "enforcement should re-evaluate now" without knowing what
 * enforcement is.
 *
 * Every screen and background component that changes something enforcement
 * cares about — a rule edit, a bonus grant, an approved request, a pulled
 * policy update, raising or clearing an SOS — used to reach for
 * `EnforcementService.start()` directly, which is what made `rules`, `ui`,
 * `sync` and `setup` each depend on `enforcement` just to wake it up a little
 * early. They all want the same one thing: don't make the family wait for the
 * next tick. This is that one thing, named, with the dependency pointing the
 * other way — the composition root in the app shell owns the one
 * implementation that actually knows how to wake enforcement.
 */
fun interface RuleMutationSink {
    /** Something changed that enforcement's next tick should already reflect. */
    fun rulesChanged()
}
