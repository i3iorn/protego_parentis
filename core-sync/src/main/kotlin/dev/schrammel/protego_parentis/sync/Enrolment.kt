package dev.schrammel.protego_parentis.sync

/** What a device may do about who else is in the family. */
enum class EnrolmentAuthority {
    /**
     * May approve devices, and tells the rest of the family when it does.
     *
     * Every parent phone. One parent letting somebody in is enough — asking all
     * of them separately would mean a second parent's phone stayed useless until
     * everyone had noticed, which in practice means until somebody complained.
     */
    APPROVES,

    /**
     * Takes the family's word for it.
     *
     * The child's phone. It applies what the parents vouch for and offers nobody
     * an approve button, because deciding who may set the rules is not a job for
     * the person the rules are about.
     */
    ACCEPTS,

    /**
     * May approve, but only to bootstrap.
     *
     * The child's phone before it trusts any parent at all. Somebody has to be
     * first, and there is no one to vouch for them — so the person physically
     * setting the phone up, who holds the PIN, enrols the founding parent. After
     * that this device never approves again.
     */
    BOOTSTRAPS,
}

/**
 * Who decides who is in the family.
 *
 * The rule is short: parents approve, the child accepts. It is worth stating why
 * the obvious alternative is wrong. If the child's phone approved parents, then
 * anyone who got as far as that phone — which is the phone the whole product
 * assumes is in the hands of the person being limited — could add a parent device
 * of their own and answer their own requests. The approval has to happen somewhere
 * that is not the phone under management.
 *
 * The exception is the founding parent, who by definition cannot be vouched for.
 * That one enrolment happens on the child's phone, behind the PIN, by whoever is
 * standing there setting it up. It is the same trust boundary as choosing the
 * device's role in the first place: whoever holds a fresh phone during setup is
 * taken to be a parent.
 */
object Enrolment {

    fun authorityOf(role: SyncRole, trustedParents: Int): EnrolmentAuthority = when {
        role == SyncRole.PARENT -> EnrolmentAuthority.APPROVES
        trustedParents == 0 -> EnrolmentAuthority.BOOTSTRAPS
        else -> EnrolmentAuthority.ACCEPTS
    }

    /** Whether this device should offer a person an approve button at all. */
    fun mayApprove(role: SyncRole, trustedParents: Int): Boolean =
        authorityOf(role, trustedParents) != EnrolmentAuthority.ACCEPTS

    /**
     * Whether a vouch from an accepted sender should be acted on.
     *
     * Two refusals worth being explicit about:
     *
     * A vouch whose key does not match its id is refused, because nothing human
     * looks at a vouch — the fingerprint check a person would have made has to
     * happen here instead.
     *
     * A vouch for a *child* is refused. The managed phone is enrolled once, at
     * setup, by somebody holding it; letting a parent add a second child device
     * remotely would let one compromised parent phone introduce a device that
     * receives every rule and every position.
     *
     * A second managed phone is admitted a different way entirely — held until
     * every current parent independently confirms it, via `ConsensusKind.ENROL_CHILD`
     * in app-child — which still never routes through a vouch. This function's
     * refusal is unconditional and unaffected by that mechanism existing.
     */
    fun shouldAccept(vouch: Vouch, selfId: DeviceId): Boolean = when {
        !vouch.isConsistent() -> false
        vouch.role != SyncRole.PARENT -> false
        // Being vouched for by somebody else does not tell this phone anything
        // about itself, and applying it would put a duplicate in its own roster.
        vouch.id == selfId -> false
        else -> true
    }

    /**
     * Whether a revoke naming a device of [targetRole] should be acted on.
     *
     * [targetRole] is what *this* device's own records say the target actually
     * is — its own roster entry for that id, or its own known role when the
     * revoke names itself — never what the revoke message claims, for the same
     * reason [shouldAccept] never trusts a vouch's claimed role either.
     *
     * A revoke naming the managed phone is refused outright, symmetrically with
     * [shouldAccept] never covering it either: un-enrolling the single device
     * the whole family is built around has to stay a local, physical, PIN-gated
     * action taken on that phone, not something one remote parent's signature
     * can do to it, or to everyone else's trust in it, all at once.
     */
    fun shouldAcceptRevoke(targetRole: SyncRole?): Boolean = targetRole == SyncRole.PARENT
}
