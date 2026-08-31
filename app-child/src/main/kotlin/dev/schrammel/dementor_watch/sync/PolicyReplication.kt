package dev.schrammel.dementor_watch.sync

import android.content.Context
import dev.schrammel.dementor_watch.model.InstallPolicy
import dev.schrammel.dementor_watch.rules.LocalPolicyStore
import dev.schrammel.dementor_watch.rules.PolicySnapshot
import dev.schrammel.dementor_watch.rules.StoredCategory
import dev.schrammel.dementor_watch.rules.applySnapshot
import dev.schrammel.dementor_watch.rules.snapshot
import dev.schrammel.dementor_watch.security.KeystoreSigner

/**
 * The rules, as paths.
 *
 * The bridge between the two representations the app needs: the domain one that
 * [LocalPolicyStore] and every rules screen already work in, and the flat
 * register space [StateMerge] converges on. [PolicySnapshot] sits in the middle
 * of both directions, so there is one definition of "the editable rules" rather
 * than a second one hidden in here.
 *
 * ## Why collections become paths
 *
 * The tempting shortcut is to store a category's app list as one encoded value.
 * That would make every membership change a write to a single register, which
 * means two parents adding two different apps to the same category are a
 * conflict, and one of them loses — silently, and for no reason a person could
 * work out.
 *
 * So membership is one register per member:
 *
 * ```
 * policy/categories/games/packages/com.example.a = "1"
 * policy/categories/games/packages/com.example.b = "1"
 * ```
 *
 * Now the two parents write different paths, both survive, and removing an app
 * is a tombstone on its own path rather than a rewrite of the whole list. The
 * same reasoning applies to app limits, exemptions, blocks and every other set
 * the policy holds.
 *
 * ## On [PolicySnapshot.version]
 *
 * Carried but no longer load-bearing. It existed so a whole-policy push could be
 * refused when it was built from a stale copy; under replication there is no
 * such thing as a stale edit — a change to one field merges with a concurrent
 * change to another, and two changes to the *same* field are ordered by their
 * Lamport stamps. Nothing reads it any more, and it is kept only so the type
 * still round-trips through its existing tested codec.
 */
class PolicyReplication(context: Context) {

    private val appContext = context.applicationContext
    private val store = LocalPolicyStore(appContext)
    private val state = ReplicatedStateStore(appContext)

    /**
     * Records this phone's own rules as replicated writes.
     *
     * Called on the managed phone after any local rule edit, and again on every
     * poll. Unchanged fields produce no ops, so the common case — a screen
     * re-rendering, or a save that moved one slider — publishes one line rather
     * than the whole policy, and a poll that changed nothing publishes nothing.
     */
    fun pushFromLocalPolicy(): List<StateOp> = write(store.snapshot())

    /** Records an edit made on a parent's phone, which holds no local rules. */
    fun write(snapshot: PolicySnapshot): List<StateOp> {
        val origin = runCatching { KeystoreSigner().deviceId.value }.getOrNull() ?: return emptyList()
        return state.writeAll(flatten(snapshot), origin)
    }

    /**
     * Writes the replicated rules back onto this phone.
     *
     * Only ever called on the managed phone, and only for state that arrived
     * from a device permitted to write it — [ReplicationRules] has already
     * refused anything else by the time this runs.
     */
    fun pullIntoLocalPolicy() {
        store.applySnapshot(read() ?: return)
    }

    /**
     * The replicated rules, or null if none have arrived.
     *
     * Null rather than an empty policy on purpose: a family whose other phones
     * are still on an older build, or a replica before its first exchange, holds
     * no policy paths at all — and applying an empty one would silently clear
     * every rule the family had.
     */
    fun read(): PolicySnapshot? {
        val held = state.entries
        if (held.keys.none { it.startsWith("$POLICY/") }) return null

        fun value(path: String): String? = held["$POLICY/$path"]?.value
        fun number(path: String, fallback: Int) = value(path)?.toIntOrNull() ?: fallback

        return PolicySnapshot(
            version = 0L,
            dailyLimitMinutes = number(PATH_DAILY_LIMIT, 0),
            bedtimeEnabled = value(PATH_BEDTIME_ENABLED)?.toBooleanStrictOrNull() ?: false,
            bedtimeStartMinute = number(PATH_BEDTIME_START, 0),
            bedtimeEndMinute = number(PATH_BEDTIME_END, 0),
            bedtimeDays = members("$POLICY/$PATH_BEDTIME_DAYS").mapNotNull(String::toIntOrNull).toSet(),
            categories = state.idsUnder("$POLICY/$PATH_CATEGORIES").mapNotNull { id ->
                StoredCategory(
                    id = id,
                    // A category whose name is gone is a category that was
                    // deleted; its other registers may still be catching up.
                    name = value("$PATH_CATEGORIES/$id/name") ?: return@mapNotNull null,
                    limitMinutes = number("$PATH_CATEGORIES/$id/limitMinutes", 0),
                    packages = members("$POLICY/$PATH_CATEGORIES/$id/packages"),
                    subcategories = members("$POLICY/$PATH_CATEGORIES/$id/subcategories"),
                )
            },
            appLimits = state.children("$POLICY/$PATH_APP_LIMITS")
                .mapNotNull { (pkg, minutes) -> minutes.toIntOrNull()?.let { pkg to it } }
                .toMap(),
            exemptPackages = members("$POLICY/$PATH_EXEMPT"),
            blockedPackages = members("$POLICY/$PATH_BLOCKED"),
            hiddenPackages = members("$POLICY/$PATH_HIDDEN"),
            networkBlockedPackages = members("$POLICY/$PATH_NETWORK_BLOCKED"),
            installPolicy = value(PATH_INSTALL_POLICY) ?: InstallPolicy.NOTIFY.name,
            installWindowUntil = value(PATH_INSTALL_WINDOW)?.toLongOrNull(),
        )
    }

    private fun members(root: String): Set<String> = state.children(root).keys

    /**
     * Every rule as a path, including the ones that should now be absent.
     *
     * Membership that has gone is written as a tombstone rather than omitted: an
     * op saying "this app is no longer in this category" is the only thing that
     * removes it from a replica that still has it. Leaving it out would let
     * every other device keep the app forever, and the next digest repair would
     * hand it back.
     */
    private fun flatten(snapshot: PolicySnapshot): Map<String, String?> = buildMap {
        put("$POLICY/$PATH_DAILY_LIMIT", snapshot.dailyLimitMinutes.toString())
        put("$POLICY/$PATH_BEDTIME_ENABLED", snapshot.bedtimeEnabled.toString())
        put("$POLICY/$PATH_BEDTIME_START", snapshot.bedtimeStartMinute.toString())
        put("$POLICY/$PATH_BEDTIME_END", snapshot.bedtimeEndMinute.toString())
        putMembers("$POLICY/$PATH_BEDTIME_DAYS", snapshot.bedtimeDays.map(Int::toString).toSet())

        snapshot.categories.forEach { category ->
            val root = "$POLICY/$PATH_CATEGORIES/${category.id}"
            put("$root/name", category.name)
            put("$root/limitMinutes", category.limitMinutes.toString())
            putMembers("$root/packages", category.packages)
            putMembers("$root/subcategories", category.subcategories)
        }
        // A category deleted here has to be deleted everywhere, and its whole
        // subtree with it — a stale name left behind would be a category that
        // reappeared at the next repair.
        val live = snapshot.categories.map { it.id }.toSet()
        state.idsUnder("$POLICY/$PATH_CATEGORIES")
            .filterNot { it in live }
            .forEach { gone ->
                state.entries.keys
                    .filter { it.startsWith("$POLICY/$PATH_CATEGORIES/$gone/") }
                    .forEach { put(it, null) }
            }

        putMembers("$POLICY/$PATH_EXEMPT", snapshot.exemptPackages)
        putMembers("$POLICY/$PATH_BLOCKED", snapshot.blockedPackages)
        putMembers("$POLICY/$PATH_HIDDEN", snapshot.hiddenPackages)
        putMembers("$POLICY/$PATH_NETWORK_BLOCKED", snapshot.networkBlockedPackages)
        putValues("$POLICY/$PATH_APP_LIMITS", snapshot.appLimits.mapValues { it.value.toString() })

        put("$POLICY/$PATH_INSTALL_POLICY", snapshot.installPolicy)
        put("$POLICY/$PATH_INSTALL_WINDOW", snapshot.installWindowUntil?.toString())
    }

    private fun MutableMap<String, String?>.putMembers(root: String, members: Set<String>) =
        putValues(root, members.associateWith { PRESENT })

    private fun MutableMap<String, String?>.putValues(root: String, values: Map<String, String>) {
        values.forEach { (key, value) -> put("$root/$key", value) }
        state.children(root).keys
            .filterNot { it in values }
            .forEach { gone -> put("$root/$gone", null) }
    }

    private companion object {
        const val POLICY = ReplicationRules.POLICY
        const val PRESENT = "1"

        const val PATH_DAILY_LIMIT = "dailyLimitMinutes"
        const val PATH_BEDTIME_ENABLED = "bedtime/enabled"
        const val PATH_BEDTIME_START = "bedtime/start"
        const val PATH_BEDTIME_END = "bedtime/end"
        const val PATH_BEDTIME_DAYS = "bedtime/days"
        const val PATH_CATEGORIES = "categories"
        const val PATH_APP_LIMITS = "appLimits"
        const val PATH_EXEMPT = "exempt"
        const val PATH_BLOCKED = "blocked"
        const val PATH_HIDDEN = "hidden"
        const val PATH_NETWORK_BLOCKED = "networkBlocked"
        const val PATH_INSTALL_POLICY = "installPolicy"
        const val PATH_INSTALL_WINDOW = "installWindowUntil"
    }
}
