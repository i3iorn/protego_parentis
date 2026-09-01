package dev.schrammel.protego_parentis.model

/**
 * A named group of apps with its own daily allowance — "Games", "Social",
 * "School".
 *
 * Membership is deliberately many-to-many: an app can belong to any number of
 * categories, and every category it belongs to is enforced independently. A game
 * with chat in it is genuinely both a game and social, and forcing a single
 * bucket would make the parent choose which limit to give up.
 *
 * Time spent in an app counts against *every* category it is in. That is the
 * honest reading of "no more than an hour of games and no more than an hour of
 * social": thirty minutes in a social game spends thirty minutes of both.
 */
data class Category(
    val id: CategoryId,
    val name: String,
    val budget: WeeklyBudget,
    val packages: Set<PackageName> = emptySet(),
    /**
     * Categories nested inside this one. Their apps count towards this category
     * too, so "Fun" can hold "Games" and "Social" and cap the pair of them without
     * anyone re-listing every app.
     *
     * Resolution is transitive and lives in `CategoryHierarchy`, which also breaks
     * any loop a parent manages to create.
     */
    val subcategories: Set<CategoryId> = emptySet(),
) {
    init {
        require(name.isNotBlank()) { "a category needs a name to show on the block screen" }
    }

    /** Direct membership only. For the nested view use `CategoryHierarchy`. */
    fun contains(pkg: PackageName): Boolean = pkg in packages
}
