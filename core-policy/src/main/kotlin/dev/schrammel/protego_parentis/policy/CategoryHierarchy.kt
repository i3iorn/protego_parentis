package dev.schrammel.protego_parentis.policy

import dev.schrammel.protego_parentis.model.Category
import dev.schrammel.protego_parentis.model.CategoryId
import dev.schrammel.protego_parentis.model.PackageName

/**
 * A category's full membership once nesting has been followed.
 *
 * Internal: [CategoryHierarchy.resolve] is only ever called from
 * [PolicyEngine.evaluate], in this module — a caller elsewhere wants
 * [CategoryHierarchy.wouldCreateCycle] instead.
 */
internal data class ResolvedHierarchy(
    /** Every app that counts towards each category, including via nested ones. */
    val packagesByCategory: Map<CategoryId, Set<PackageName>>,
    /**
     * Nesting links that were ignored because following them would loop.
     *
     * Surfaced rather than swallowed so the UI can tell a parent their "Fun holds
     * Games, Games holds Fun" arrangement is not doing what they think.
     */
    val brokenLinks: List<Pair<CategoryId, CategoryId>>,
) {
    fun packagesIn(id: CategoryId): Set<PackageName> = packagesByCategory[id].orEmpty()

    val hasCycles: Boolean get() = brokenLinks.isNotEmpty()
}

/**
 * Flattens nested categories.
 *
 * A category's apps are its own plus those of everything nested inside it, all
 * the way down. That is what makes a "Fun" category holding "Games" and "Social"
 * cap the pair of them without anyone re-listing every app.
 *
 * Two properties matter more than the flattening itself:
 *
 *  - **Loops cannot hang the app.** Nothing stops a parent putting Fun inside
 *    Games while Games is inside Fun. A naive walk would recurse until the stack
 *    gave out — in a foreground service that evaluates every few minutes, on the
 *    child's phone. Loops are detected, the offending link is dropped, and the
 *    rest of the hierarchy still resolves.
 *  - **An app reached twice is counted once.** With Fun holding both Games and
 *    Social, a game that is also social must not spend Fun's budget twice as
 *    fast. Sets, not lists, all the way through.
 */
object CategoryHierarchy {

    internal fun resolve(categories: List<Category>): ResolvedHierarchy {
        val byId = categories.associateBy { it.id }
        val resolved = HashMap<CategoryId, Set<PackageName>>()
        val broken = mutableListOf<Pair<CategoryId, CategoryId>>()

        /**
         * [ancestors] is the chain currently being walked; meeting a member of it
         * again is exactly what a loop looks like.
         */
        fun walk(id: CategoryId, ancestors: MutableSet<CategoryId>): Set<PackageName> {
            resolved[id]?.let { return it }

            val category = byId[id] ?: return emptySet()
            ancestors += id

            val packages = mutableSetOf<PackageName>()
            packages += category.packages

            for (childId in category.subcategories) {
                if (childId in ancestors) {
                    // Following this would loop. Drop the link, keep everything else.
                    broken += id to childId
                    continue
                }
                if (childId !in byId) continue // Deleted category; nothing to add.
                packages += walk(childId, ancestors)
            }

            ancestors -= id
            // Only cached once fully walked, so a partial result from inside a
            // loop is never reused as if it were complete.
            resolved[id] = packages
            return packages
        }

        categories.forEach { walk(it.id, linkedSetOf()) }

        return ResolvedHierarchy(
            packagesByCategory = resolved,
            brokenLinks = broken.distinct(),
        )
    }

    /**
     * Whether nesting [child] inside [parent] would create a loop.
     *
     * Used by the editor to refuse the change up front, which is a far better
     * experience than accepting it and silently ignoring it later.
     */
    fun wouldCreateCycle(
        categories: List<Category>,
        parent: CategoryId,
        child: CategoryId,
    ): Boolean {
        if (parent == child) return true

        val byId = categories.associateBy { it.id }
        val seen = mutableSetOf<CategoryId>()
        val queue = ArrayDeque<CategoryId>().apply { add(child) }

        // A loop appears exactly when the parent is already somewhere beneath the
        // child.
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (!seen.add(next)) continue
            if (next == parent) return true
            byId[next]?.subcategories?.forEach(queue::add)
        }
        return false
    }
}
