package dev.schrammel.protego_parentis.policy

import dev.schrammel.protego_parentis.model.Condition
import dev.schrammel.protego_parentis.model.PlaceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConditionDraftTest {

    private val school = Condition.OnWifi("School")
    private val home = Condition.AtPlace(PlaceId("home"))
    private val low = Condition.BatteryBelow(20)

    private fun roundTrip(condition: Condition) = ConditionDraft.fromCondition(condition).toCondition()

    @Test
    fun `every shape the old editor saved comes back unchanged`() {
        listOf(
            Condition.Always,
            school,
            Condition.Not(school),
            Condition.AllOf(listOf(school, low)),
            Condition.AnyOf(listOf(school, low)),
            Condition.Not(Condition.AllOf(listOf(school, low))),
            Condition.AllOf(listOf(school, Condition.Not(low))),
        ).forEach { assertEquals(it, roundTrip(it)) }
    }

    @Test
    fun `nested groups and at-least groups come back unchanged`() {
        val condition = Condition.AnyOf(
            listOf(
                Condition.AllOf(listOf(school, Condition.Not(home))),
                Condition.Not(Condition.AtLeast(2, listOf(school, home, low))),
            ),
        )

        assertEquals(condition, roundTrip(condition))
    }

    @Test
    fun `a single condition opens as a group of one`() {
        val draft = ConditionDraft.fromCondition(school)

        assertEquals(ConditionDraft.Group(children = listOf(ConditionDraft.Leaf(school))), draft)
    }

    @Test
    fun `at least 1 saves as any, and at least all of them saves as all`() {
        val two = listOf(ConditionDraft.Leaf(school), ConditionDraft.Leaf(low))

        assertEquals(Condition.AnyOf(listOf(school, low)), ConditionDraft.Group(GroupMode.AtLeast(1), two).toCondition())
        assertEquals(Condition.AllOf(listOf(school, low)), ConditionDraft.Group(GroupMode.AtLeast(2), two).toCondition())
    }

    @Test
    fun `an empty all-group is always, an empty any-group never holds`() {
        assertEquals(Condition.Always, ConditionDraft.Group(GroupMode.All).toCondition())
        assertEquals(Condition.AnyOf(emptyList()), ConditionDraft.Group(GroupMode.Any).toCondition())
    }

    @Test
    fun `rows can be added, negated and removed by path`() {
        var draft = ConditionDraft.fromCondition(school)
        draft = draft.add(emptyList(), ConditionDraft.Group(GroupMode.Any))
        draft = draft.add(listOf(1), ConditionDraft.Leaf(home))
        draft = draft.add(listOf(1), ConditionDraft.Leaf(low))
        draft = draft.setNegated(listOf(1, 0), true)

        assertEquals(
            Condition.AllOf(listOf(school, Condition.AnyOf(listOf(Condition.Not(home), low)))),
            draft.toCondition(),
        )

        draft = draft.remove(listOf(1, 1))
        assertEquals(Condition.AllOf(listOf(school, Condition.Not(home))), draft.toCondition())
    }

    @Test
    fun `a group's mode and negation can be changed by path`() {
        var draft = ConditionDraft.fromCondition(Condition.AllOf(listOf(school, low, home)))
        draft = draft.setMode(emptyList(), GroupMode.AtLeast(2))
        draft = draft.setNegated(emptyList(), true)

        assertEquals(Condition.Not(Condition.AtLeast(2, listOf(school, low, home))), draft.toCondition())
    }

    @Test
    fun `a path that leads nowhere changes nothing`() {
        val draft = ConditionDraft.fromCondition(school)

        assertEquals(draft, draft.remove(listOf(5)))
        assertEquals(draft, draft.setNegated(listOf(0, 3), true))
        assertEquals(draft, draft.add(listOf(0), ConditionDraft.Leaf(low)))
        assertNull(draft.nodeAt(listOf(2)))
    }

    @Test
    fun `group depth counts the root`() {
        val draft = ConditionDraft.Group(
            children = listOf(ConditionDraft.Group(children = listOf(ConditionDraft.Leaf(school)))),
        )

        assertEquals(1, draft.groupDepthAt(emptyList()))
        assertEquals(2, draft.groupDepthAt(listOf(0)))
        assertEquals(2, draft.groupDepthAt(listOf(0, 0)))
    }
}
