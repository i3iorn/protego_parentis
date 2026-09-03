package dev.schrammel.protego_parentis.sync

/**
 * Who may write what.
 *
 * Making every device a replica of the same state removes the old question
 * ("may this device send this message type?") and replaces it with a sharper
 * one: **may this device write this path?** The roster still decides who is
 * speaking — see [RosterRules] — and this decides what they are allowed to say
 * once identified.
 *
 * The rule that matters is the one the whole product rests on: *the phone being
 * limited may not change its own limits.* Symmetric replication makes that easy
 * to get wrong, because the managed phone is now a first-class writer — it
 * publishes its own usage, its own chat, its own requests. So the boundary is
 * drawn per path rather than per message, and it is drawn here, once.
 *
 * Refusal is silent and local: an op that fails this check is dropped by the
 * receiver, not answered. A device that wrote it will see its value disagree at
 * the next digest and can do nothing about it, which is the intended outcome.
 * Nothing is trusted because it arrived — only because of who signed it and
 * where it is trying to write.
 */
object ReplicationRules {

    /** Rules the family enforces on the managed phone. */
    const val POLICY = "policy"

    /** Who is in the family, and which device is which. */
    const val ROSTER = "roster"

    /** What the managed phone reports about itself: usage, battery, position. */
    const val TELEMETRY = "telemetry"

    /** Asks for more time, and the answers to them. */
    const val REQUESTS = "requests"

    /** Messages people typed. */
    const val CHAT = "chat"

    /** The shared, append-only history. */
    const val AUDIT = "audit"

    /** Proposals for the handful of decisions that need every parent's agreement. */
    const val CONSENSUS = "consensus"

    /**
     * Whether a device of [role], identified as [writerId], may write [path].
     *
     * Read as a sentence: the managed phone owns what it observes and what it
     * asks for; parents own what is imposed on it; both may talk.
     *
     * [writerId] is the identity the *roster* established for the sender — the
     * device whose signature verified against a known public key — never
     * [StateOp.origin]. `origin` is a plain field on the wire, set by whoever
     * encoded the op, and nothing here or in [StateMerge] ever checks it against
     * who actually signed the message it arrived in. Using it for authorisation
     * would let a compromised sender simply write a different id into that
     * field and claim to be someone else. It exists only to break ties between
     * two writes that land on the same path at the same logical time.
     */
    fun mayWrite(role: SyncRole?, path: String, writerId: DeviceId? = null): Boolean {
        val section = path.substringBefore(SEPARATOR)
        return when (section) {
            // A parent may write any child's rules. The managed phone may only
            // write its own — worth the paragraph, because it used to be
            // simpler than this and the simpler version was right once.
            //
            // The tempting rule is "only a parent may write the rules", and it
            // was the rule here first. It does not survive contact with the
            // product: the managed phone is where the rules actually live and
            // where they can be edited standing at it, behind the parent PIN.
            // Refusing its writes meant a parent's phone never learned the rules
            // at all — it sat on an empty screen waiting for state that by
            // construction could never arrive — and, worse, the two replicas
            // could never agree, so every digest exchange found the same
            // difference and asked for the same repair, forever.
            //
            // So the boundary for the rules is the PIN, not the device — within
            // the managed phone's *own* rules. That reasoning never argued for
            // one managed phone being able to write a second one's rules, and
            // with more than one child device in a family that is exactly what
            // an unscoped rule would allow. So a child may write only the
            // subtree at `policy/<its own id>/…`; a parent, having no subtree
            // of its own to be confined to, may write any child's.
            //
            // An edit made at the managed phone has already been authorised by
            // somebody who knows the PIN, which is the same reasoning that lets
            // that phone enrol the founding parent (see [Enrolment]). What this
            // gives up is real and worth stating plainly: a managed phone whose
            // app has been modified can publish a policy its parents will
            // believe, for its own subtree. Such a phone could already ignore
            // that policy outright, so what is lost is the parents' *view* being
            // trustworthy for that one child, not enforcement, and not any other
            // child's rules — and the shared history still records which device
            // wrote what.
            //
            // Trust itself is not on this footing. See ROSTER below.
            POLICY -> when (role) {
                SyncRole.PARENT -> true
                SyncRole.CHILD -> writerId != null && ownsPolicySubtree(path, writerId)
                null -> false
            }

            // Enrolment stays a parent's decision, exactly as it was before
            // replication existed, and unlike POLICY above it does not soften.
            // A phone that could write the roster could admit a device of its
            // own, and that device could then write everything a parent may
            // write — an escalation rather than a view being wrong. See
            // [Enrolment.shouldAccept].
            ROSTER -> role == SyncRole.PARENT

            // Only the managed phone can observe the managed phone. A parent
            // phone writing here could invent a quiet afternoon that never
            // happened, and the child would have no way to contradict it.
            TELEMETRY -> role == SyncRole.CHILD

            // Both ends of a negotiation: the child raises, a parent answers.
            // Which *fields* each may write is narrower still — see
            // [mayWriteRequestField].
            REQUESTS -> role != null

            // A conversation has two ends.
            CHAT -> role != null

            // Both sides record, and neither may rewrite: see [isAppendOnly].
            AUDIT -> role != null

            // A proposal and its approvals are a parent-only conversation. The
            // managed phone has no vote in whether its own family disbands, a
            // parent leaves, another parent joins, or a second managed phone is
            // admitted — these are decisions about the family's structure, not
            // its rules, and the child is not a party to them. Which specific
            // fields a parent may write is narrower still — see
            // [mayWriteConsensusField].
            CONSENSUS -> role == SyncRole.PARENT

            // An unknown section from a newer build. Refused rather than
            // allowed: a path this build cannot reason about is a path it
            // cannot police, and storing it would let a future section be
            // written by whoever got there first.
            else -> false
        }
    }

    /**
     * Whether [role] may write a particular field of a request.
     *
     * The section check alone is not enough here. A request carries both what
     * was asked and what was decided, and if the managed phone could write the
     * decision it could approve itself — the exact thing the request flow
     * exists to prevent.
     */
    fun mayWriteRequestField(role: SyncRole?, path: String): Boolean {
        if (path.substringBefore(SEPARATOR) != REQUESTS) return true
        val field = path.substringAfterLast(SEPARATOR, missingDelimiterValue = "")
        return when (field) {
            FIELD_STATE, FIELD_DECIDED_BY, FIELD_DECIDED_AT, FIELD_NOTE ->
                role == SyncRole.PARENT

            else -> true
        }
    }

    /**
     * Whether [writerId] may write [value] to a CONSENSUS [path].
     *
     * Approval is self-attestation: `consensus/<id>/approvals/<parentId>` may
     * only be set to a live value by the parent it names — otherwise one parent
     * approving on every other parent's behalf would make unanimity mean
     * nothing.
     *
     * A tombstone is deliberately exempt. Committing a proposal clears its
     * whole subtree, including every approval register on it, and that has to
     * be doable by whichever device notices the threshold was met — not only
     * by each approving parent's own device, which might never be reachable
     * again once its approval has already served its purpose. A tombstone can
     * only retract a claim, never fabricate one, so letting any parent write
     * one costs nothing the self-attestation rule above exists to prevent: it
     * cannot be used to forge an approval, only to help retire one that has
     * already counted.
     */
    fun mayWriteConsensusField(path: String, value: String?, writerId: DeviceId?): Boolean {
        if (path.substringBefore(SEPARATOR) != CONSENSUS) return true
        if (value == null) return true
        val segments = path.split(SEPARATOR)
        if (segments.size < 4 || segments[2] != FIELD_APPROVALS) return true
        return writerId != null && segments[3] == writerId.value
    }

    /**
     * Paths where the earliest write stands and later ones cannot revise it.
     *
     * The shared history is the one thing in this app meant to be impossible to
     * quietly rewrite — a log a parent could edit is not a log the child has any
     * reason to believe.
     *
     * How that is enforced matters. It is *not* a receiver-side refusal: see
     * [StateMerge] for why refusing a write to an already-populated path splits
     * replicas instead of protecting them. It is a merge rule, so every replica
     * independently keeps the same entry whatever order things arrived in.
     *
     * **What this does not promise.** A device already trusted by the roster can
     * still write an audit path with an early logical clock and displace what
     * was there. No last-write-wins register can prevent that, because the rule
     * has to be decidable from the two ops alone. Cross-device tamper-evidence
     * would need a signature per entry. What does hold is the local hash chain
     * in [dev.schrammel.protego_parentis.audit.Audit], which covers this phone's
     * own log — the two mechanisms answer different questions, and the replicated
     * copy is deliberately the weaker of them.
     */
    fun isAppendOnly(path: String): Boolean = path.substringBefore(SEPARATOR) == AUDIT

    /**
     * Whether a device of [role], identified as [writerId], may write this op
     * at all.
     *
     * Authorisation only. What happens when two authorised writes contend for
     * one path is [StateMerge]'s business, and deliberately not decided here —
     * anything that depends on what this replica already holds would make the
     * outcome depend on arrival order.
     */
    fun accepts(role: SyncRole?, op: StateOp, writerId: DeviceId? = null): Boolean =
        isWellFormed(op) &&
            mayWrite(role, op.path, writerId) &&
            mayWriteRequestField(role, op.path) &&
            mayWriteConsensusField(op.path, op.value, writerId)

    /**
     * Whether [path] falls under the policy subtree owned by [writerId].
     *
     * The child id is the path's second segment — `policy/<childId>/…` — so a
     * managed phone may write its own rules but not another child's, even
     * though both live in the same [POLICY] section.
     */
    private fun ownsPolicySubtree(path: String, writerId: DeviceId): Boolean =
        path.split(SEPARATOR).getOrNull(1) == writerId.value

    // ---------- what a path and a value may look like ----------

    /**
     * The longest path worth storing.
     *
     * Generous for anything this app writes — the deepest real path is a
     * category's app membership, well under a hundred characters — and a bound
     * rather than a guess, because paths arrive from other devices and a
     * replica that stored whatever it was sent could be filled with a handful
     * of enormous keys.
     */
    const val MAX_PATH_LENGTH = 512

    /** The longest value worth storing. Comfortably above a chat message. */
    const val MAX_VALUE_LENGTH = 4_096

    /**
     * Whether a path is one this app could have written.
     *
     * Empty segments are refused, which rules out both `a//b` and anything
     * leading or trailing a separator. That matters because segments are read
     * back as identifiers — a category id, a package name, a device id — and a
     * reader that received an empty one would produce a key indistinguishable
     * from its own parent.
     *
     * Note this does not need to defend against `..`: nothing here resolves a
     * path against a filesystem or a tree, and a segment named `..` is simply a
     * key nothing will ever look up. It is refused anyway, because a path this
     * app cannot have produced is one worth refusing on principle rather than
     * on a case-by-case argument about what it could reach.
     */
    fun isWellFormedPath(path: String): Boolean {
        if (path.isEmpty() || path.length > MAX_PATH_LENGTH) return false
        val segments = path.split(SEPARATOR)
        if (segments.size < 2) return false
        return segments.all { it.isNotEmpty() && it != "." && it != ".." }
    }

    /** Whether an op is worth considering at all, before asking who sent it. */
    fun isWellFormed(op: StateOp): Boolean =
        isWellFormedPath(op.path) && (op.value?.length ?: 0) <= MAX_VALUE_LENGTH

    /**
     * How many registers one section may hold.
     *
     * The bound that stops a hostile device filling every replica in the family
     * with invented keys. Enforced on *new* paths only — a section already at
     * its cap still accepts writes to keys it holds, so a full section keeps
     * converging rather than freezing mid-disagreement.
     *
     * Sized well above any real family: a policy with fifty categories and a
     * thousand app rules is a few thousand registers, and a fortnight of
     * conversation is a few hundred.
     */
    fun capacityOf(path: String): Int = when (path.substringBefore(SEPARATOR)) {
        POLICY -> 20_000
        ROSTER -> 2_000
        TELEMETRY -> 500
        REQUESTS, CHAT -> 20_000
        AUDIT -> 50_000
        // A handful of these ever exist at once — the fixed set is four kinds
        // of decision, each family raises them rarely, and a resolved one is
        // tombstoned. Sized well above any real family for the same reason as
        // ROSTER: this bounds a hostile device inventing proposals, not
        // anything a real one would come close to.
        CONSENSUS -> 2_000
        else -> 0
    }

    // ---------- how much each section is worth spending on ----------

    /**
     * Which sections the digest covers, and for how far back.
     *
     * This is the difference between a protocol that costs a few hundred bytes
     * an hour and one that repairs itself into the ground. The digest works by
     * two replicas agreeing; a section that changes every fifteen seconds never
     * agrees, so including one would mean every single exchange found a
     * difference and asked for a repair — spending far more than the ops it was
     * meant to save.
     *
     * So each section gets the treatment it actually needs:
     *
     * - [CONVERGING] — small, slow-changing, and must be identical everywhere.
     *   Fully digested. Rules and roster: a few hundred registers that change
     *   a few times a week.
     * - [RECENT] — append-mostly streams where old entries stop mattering.
     *   Digested only inside [RECENT_WINDOW_SECONDS], so a month-old chat
     *   message can never trigger a repair and can be pruned locally without
     *   one either.
     * - [VOLATILE] — latest-value telemetry. Not digested at all. It is
     *   overwritten constantly and re-reported constantly, so it heals on its
     *   own within one tick and a repair for it would be stale before it
     *   arrived.
     */
    enum class ReplicationMode { CONVERGING, RECENT, VOLATILE }

    fun modeOf(path: String): ReplicationMode =
        when (path.substringBefore(SEPARATOR)) {
            // An open proposal has to keep being repaired until every replica
            // agrees on it — a stale approval count is exactly the kind of
            // disagreement the digest exists to find. Unlike a chat message or
            // a request, there is no point in its life where it is safe to stop
            // comparing it just because it has aged.
            POLICY, ROSTER, CONSENSUS -> ReplicationMode.CONVERGING
            REQUESTS, CHAT, AUDIT -> ReplicationMode.RECENT
            else -> ReplicationMode.VOLATILE
        }

    /**
     * How far back the streams are kept in step.
     *
     * A fortnight. Long enough that a phone off for a holiday still catches up
     * on the conversation it missed; short enough that a family's second year
     * of history is not re-hashed on every digest.
     */
    const val RECENT_WINDOW_SECONDS = 14L * 24 * 60 * 60

    /**
     * Whether a path counts towards the digest at [nowSeconds].
     *
     * Stream entries are keyed by an id that begins with the second they were
     * created — see [streamId] — so this is answerable from the path alone,
     * with no need to look at the value or keep an index beside it.
     */
    fun isDigested(path: String, nowSeconds: Long): Boolean = when (modeOf(path)) {
        ReplicationMode.CONVERGING -> true
        ReplicationMode.VOLATILE -> false
        ReplicationMode.RECENT -> {
            val created = createdAt(path)
            // An id with no readable time is from a build that numbered them
            // differently. Digested rather than dropped: being slightly too
            // thorough is the safe direction.
            created == null || nowSeconds - created <= RECENT_WINDOW_SECONDS
        }
    }

    /**
     * An id for a stream entry, carrying the second it was made.
     *
     * The time is in the id rather than beside it so that a replica can decide
     * whether an entry is still current without holding its value — which is
     * what lets both the digest and local pruning work on paths alone.
     */
    fun streamId(seconds: Long, unique: String): String = "$seconds-$unique"

    /** The creation second encoded in a stream path's id, or null. */
    fun createdAt(path: String): Long? {
        val segments = path.split(SEPARATOR)
        if (segments.size < 2) return null
        return segments[1].substringBefore('-').toLongOrNull()
    }

    /**
     * Whether a stream entry is old enough to drop entirely.
     *
     * Deliberately looser than the digest window, so an entry leaves the digest
     * well before anything deletes it — pruning something two replicas were
     * still comparing would show as a difference neither could repair.
     */
    fun isPrunable(path: String, nowSeconds: Long): Boolean {
        if (modeOf(path) != ReplicationMode.RECENT) return false
        val created = createdAt(path) ?: return false
        return nowSeconds - created > RECENT_WINDOW_SECONDS * PRUNE_MULTIPLE
    }

    private const val PRUNE_MULTIPLE = 2L

    /** What separates one path segment from the next. */
    const val SEPARATOR = '/'

    private const val FIELD_STATE = "state"
    private const val FIELD_DECIDED_BY = "decidedBy"
    private const val FIELD_DECIDED_AT = "decidedAt"
    private const val FIELD_NOTE = "note"

    /** The subpath under a proposal holding one register per approving parent. */
    const val FIELD_APPROVALS = "approvals"
}
