package dev.schrammel.dementor_watch.model

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
) {
    init {
        require(name.isNotBlank()) { "a category needs a name to show on the block screen" }
    }

    fun contains(pkg: PackageName): Boolean = pkg in packages
}
