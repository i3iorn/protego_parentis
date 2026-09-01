package dev.schrammel.protego_parentis.policy

import dev.schrammel.protego_parentis.model.Category
import dev.schrammel.protego_parentis.model.CategoryId
import dev.schrammel.protego_parentis.model.PackageName
import dev.schrammel.protego_parentis.model.WeeklyBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryHierarchyTest {

    private val fun_ = CategoryId("fun")
    private val games = CategoryId("games")
    private val social = CategoryId("social")
    private val deep = CategoryId("deep")

    private val clash = PackageName("com.example.clash")
    private val chat = PackageName("com.example.chat")
    private val forum = PackageName("com.example.forum")

    private fun category(
        id: CategoryId,
        packages: Set<PackageName> = emptySet(),
        subcategories: Set<CategoryId> = emptySet(),
    ) = Category(
        id = id,
        name = id.value,
        budget = WeeklyBudget.UNLIMITED,
        packages = packages,
        subcategories = subcategories,
    )

    // ---------- flattening ----------

    @Test
    fun `a flat category resolves to its own apps`() {
        val resolved = CategoryHierarchy.resolve(listOf(category(games, setOf(clash))))

        assertEquals(setOf(clash), resolved.packagesIn(games))
        assertFalse(resolved.hasCycles)
    }

    @Test
    fun `a parent category inherits its children's apps`() {
        val resolved = CategoryHierarchy.resolve(
            listOf(
                category(fun_, subcategories = setOf(games, social)),
                category(games, setOf(clash)),
                category(social, setOf(chat)),
            ),
        )

        assertEquals(setOf(clash, chat), resolved.packagesIn(fun_))
        // Children are unaffected by being nested.
        assertEquals(setOf(clash), resolved.packagesIn(games))
    }

    @Test
    fun `nesting is transitive to any depth`() {
        val resolved = CategoryHierarchy.resolve(
            listOf(
                category(fun_, subcategories = setOf(games)),
                category(games, subcategories = setOf(deep)),
                category(deep, setOf(forum)),
            ),
        )

        assertEquals(setOf(forum), resolved.packagesIn(fun_))
    }

    @Test
    fun `a parent keeps its own apps as well as its children's`() {
        val resolved = CategoryHierarchy.resolve(
            listOf(
                category(fun_, packages = setOf(forum), subcategories = setOf(games)),
                category(games, setOf(clash)),
            ),
        )

        assertEquals(setOf(forum, clash), resolved.packagesIn(fun_))
    }

    @Test
    fun `an app reachable by two routes is counted once`() {
        // Fun holds both Games and Social, and one app is in both. It must spend
        // Fun's budget once, not twice as fast.
        val resolved = CategoryHierarchy.resolve(
            listOf(
                category(fun_, subcategories = setOf(games, social)),
                category(games, setOf(clash)),
                category(social, setOf(clash, chat)),
            ),
        )

        assertEquals(setOf(clash, chat), resolved.packagesIn(fun_))
        assertEquals(2, resolved.packagesIn(fun_).size)
    }

    @Test
    fun `a reference to a deleted category is ignored`() {
        val resolved = CategoryHierarchy.resolve(
            listOf(category(fun_, setOf(forum), subcategories = setOf(CategoryId("gone")))),
        )

        assertEquals(setOf(forum), resolved.packagesIn(fun_))
    }

    @Test
    fun `an unknown category has no apps`() {
        val resolved = CategoryHierarchy.resolve(emptyList())

        assertEquals(emptySet<PackageName>(), resolved.packagesIn(games))
    }

    // ---------- loops ----------

    @Test
    fun `a direct loop is broken rather than followed`() {
        // Without this the walk recurses until the stack gives out, in a service
        // running every few minutes on the child's phone.
        val resolved = CategoryHierarchy.resolve(
            listOf(
                category(fun_, setOf(forum), subcategories = setOf(games)),
                category(games, setOf(clash), subcategories = setOf(fun_)),
            ),
        )

        assertTrue(resolved.hasCycles)
        // Everything still resolves; only the looping link is dropped.
        assertEquals(setOf(forum, clash), resolved.packagesIn(fun_))
    }

    @Test
    fun `a longer loop is also broken`() {
        val resolved = CategoryHierarchy.resolve(
            listOf(
                category(fun_, subcategories = setOf(games)),
                category(games, subcategories = setOf(deep)),
                category(deep, setOf(forum), subcategories = setOf(fun_)),
            ),
        )

        assertTrue(resolved.hasCycles)
        assertEquals(setOf(forum), resolved.packagesIn(fun_))
    }

    @Test
    fun `a category nested in itself is broken`() {
        val resolved = CategoryHierarchy.resolve(
            listOf(category(games, setOf(clash), subcategories = setOf(games))),
        )

        assertTrue(resolved.hasCycles)
        assertEquals(setOf(clash), resolved.packagesIn(games))
    }

    @Test
    fun `the broken link is reported so the parent can be told`() {
        val resolved = CategoryHierarchy.resolve(
            listOf(
                category(fun_, subcategories = setOf(games)),
                category(games, subcategories = setOf(fun_)),
            ),
        )

        assertEquals(1, resolved.brokenLinks.size)
    }

    // ---------- refusing a loop up front ----------

    @Test
    fun `nesting a category inside itself is refused`() {
        assertTrue(CategoryHierarchy.wouldCreateCycle(emptyList(), games, games))
    }

    @Test
    fun `nesting an ancestor inside its descendant is refused`() {
        val categories = listOf(
            category(fun_, subcategories = setOf(games)),
            category(games),
        )

        // Fun already holds Games, so putting Fun inside Games would loop.
        assertTrue(CategoryHierarchy.wouldCreateCycle(categories, games, fun_))
    }

    @Test
    fun `an ordinary nesting is allowed`() {
        val categories = listOf(category(fun_), category(games))

        assertFalse(CategoryHierarchy.wouldCreateCycle(categories, fun_, games))
    }

    @Test
    fun `a second route to the same child is allowed`() {
        // Fun holds Games and Social; Social may also hold Games. That is a
        // diamond, not a loop.
        val categories = listOf(
            category(fun_, subcategories = setOf(games, social)),
            category(games),
            category(social),
        )

        assertFalse(CategoryHierarchy.wouldCreateCycle(categories, social, games))
    }

    @Test
    fun `a deep ancestor is still detected`() {
        val categories = listOf(
            category(fun_, subcategories = setOf(games)),
            category(games, subcategories = setOf(deep)),
            category(deep),
        )

        assertTrue(CategoryHierarchy.wouldCreateCycle(categories, deep, fun_))
    }
}
