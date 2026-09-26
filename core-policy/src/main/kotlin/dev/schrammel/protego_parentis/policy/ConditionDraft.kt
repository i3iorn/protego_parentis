package dev.schrammel.protego_parentis.policy

import dev.schrammel.protego_parentis.model.Condition

/** How a group's conditions combine. */
sealed interface GroupMode {
    data object All : GroupMode
    data object Any : GroupMode

    /** At least [count] of the group's conditions. */
    data class AtLeast(val count: Int) : GroupMode
}

/**
 * A condition tree as the rule editor shows it: groups of conditions, each
 * group combining its children one way, and any row or group negatable.
 *
 * [Condition] can already say all of this, but not in a shape a screen can
 * edit by position: a NOT is a wrapper rather than a flag on a row, and a
 * one-condition group is indistinguishable from the condition itself. This
 * type is that editable shape, converted from a [Condition] when the editor
 * opens ([fromCondition]) and back when it saves ([toCondition]). Kept here
 * rather than in the Android layer so the conversion and every edit can be
 * tested without a device.
 *
 * Children are addressed by path: the list of child indexes from the root
 * group down. Every edit returns a new tree.
 */
sealed interface ConditionDraft {
    val negated: Boolean

    data class Leaf(val condition: Condition, override val negated: Boolean = false) : ConditionDraft

    data class Group(
        val mode: GroupMode = GroupMode.All,
        val children: List<ConditionDraft> = emptyList(),
        override val negated: Boolean = false,
    ) : ConditionDraft

    companion object {
        /**
         * How many groups deep the editor lets a parent go. Each level can
         * cost two in [ContextRules.MAX_DEPTH] (the group and its NOT), and
         * the exception clause costs two more, so this stays well inside it.
         */
        const val MAX_GROUP_DEPTH = 4

        /** The editor's root for [condition]: always a group, so there is somewhere to add a row. */
        fun fromCondition(condition: Condition): Group = when (val draft = convert(condition, negated = false)) {
            is Group -> draft
            is Leaf -> Group(children = listOf(draft))
        }

        private fun convert(condition: Condition, negated: Boolean): ConditionDraft = when (condition) {
            is Condition.Not -> convert(condition.condition, !negated)
            is Condition.AllOf -> Group(GroupMode.All, condition.conditions.map { convert(it, false) }, negated)
            is Condition.AnyOf -> Group(GroupMode.Any, condition.conditions.map { convert(it, false) }, negated)
            is Condition.AtLeast ->
                Group(GroupMode.AtLeast(condition.count), condition.conditions.map { convert(it, false) }, negated)
            else -> Leaf(condition, negated)
        }
    }
}

/**
 * The [Condition] this draft saves as, in the simplest shape that means the
 * same thing, so a rule an older app version can read stays readable:
 *
 *  - an empty "all of" group is [Condition.Always], the same as a rule with
 *    no conditions chosen yet; an empty "any"/"at least" group never holds,
 *    as [Condition.AnyOf] of nothing never has;
 *  - a group of one is that one condition;
 *  - "at least 1" is [Condition.AnyOf] and "at least all of them" is
 *    [Condition.AllOf]. A count above the number of conditions is saved as
 *    written, and never holds.
 */
fun ConditionDraft.toCondition(): Condition {
    val plain = when (this) {
        is ConditionDraft.Leaf -> condition
        is ConditionDraft.Group -> {
            val parts = children.map { it.toCondition() }
            when {
                parts.isEmpty() -> if (mode == GroupMode.All) Condition.Always else Condition.AnyOf(emptyList())
                parts.size == 1 && mode.accepts(1, of = 1) -> parts.single()
                else -> when (val m = mode) {
                    GroupMode.All -> Condition.AllOf(parts)
                    GroupMode.Any -> Condition.AnyOf(parts)
                    is GroupMode.AtLeast -> when (m.count) {
                        1 -> Condition.AnyOf(parts)
                        parts.size -> Condition.AllOf(parts)
                        else -> Condition.AtLeast(m.count, parts)
                    }
                }
            }
        }
    }
    return if (negated) Condition.Not(plain) else plain
}

private fun GroupMode.accepts(held: Int, of: Int): Boolean = when (this) {
    GroupMode.All -> held == of
    GroupMode.Any -> held >= 1
    is GroupMode.AtLeast -> held >= count
}

/** The node at [path], or null if there is none. */
fun ConditionDraft.Group.nodeAt(path: List<Int>): ConditionDraft? {
    var node: ConditionDraft = this
    for (index in path) node = (node as? ConditionDraft.Group)?.children?.getOrNull(index) ?: return null
    return node
}

/** How many groups deep [path] is, counting the root as 1. */
fun ConditionDraft.Group.groupDepthAt(path: List<Int>): Int =
    (0..path.size).count { nodeAt(path.take(it)) is ConditionDraft.Group }

/** This tree with the node at [path] replaced by what [change] returns. Unchanged if [path] leads nowhere. */
fun ConditionDraft.Group.update(path: List<Int>, change: (ConditionDraft) -> ConditionDraft): ConditionDraft.Group {
    if (path.isEmpty()) return change(this) as? ConditionDraft.Group ?: this
    val index = path.first()
    val child = children.getOrNull(index) ?: return this
    val updated = if (path.size == 1) {
        change(child)
    } else {
        (child as? ConditionDraft.Group)?.update(path.drop(1), change) ?: return this
    }
    return copy(children = children.toMutableList().also { it[index] = updated })
}

/** Adds [node] as the last child of the group at [path]. */
fun ConditionDraft.Group.add(path: List<Int>, node: ConditionDraft): ConditionDraft.Group =
    update(path) { group -> (group as? ConditionDraft.Group)?.let { it.copy(children = it.children + node) } ?: group }

/** Removes the node at [path]. The root can't be removed. */
fun ConditionDraft.Group.remove(path: List<Int>): ConditionDraft.Group {
    if (path.isEmpty()) return this
    val index = path.last()
    return update(path.dropLast(1)) { parent ->
        (parent as? ConditionDraft.Group)
            ?.takeIf { index in it.children.indices }
            ?.let { it.copy(children = it.children.filterIndexed { i, _ -> i != index }) }
            ?: parent
    }
}

fun ConditionDraft.Group.setNegated(path: List<Int>, negated: Boolean): ConditionDraft.Group = update(path) { node ->
    when (node) {
        is ConditionDraft.Leaf -> node.copy(negated = negated)
        is ConditionDraft.Group -> node.copy(negated = negated)
    }
}

fun ConditionDraft.Group.setMode(path: List<Int>, mode: GroupMode): ConditionDraft.Group = update(path) { node ->
    (node as? ConditionDraft.Group)?.copy(mode = mode) ?: node
}

/**
 * Whether any "at least N" group here has fewer than N conditions, which can
 * never hold. Usually left behind by removing rows after choosing N; the
 * editor refuses to save it rather than quietly store a rule that never fires.
 */
fun ConditionDraft.Group.hasImpossibleAtLeast(): Boolean {
    val own = (mode as? GroupMode.AtLeast)?.let { it.count > children.size } ?: false
    return own || children.any { it is ConditionDraft.Group && it.hasImpossibleAtLeast() }
}
