package dev.schrammel.protego_parentis

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 2 of the Dementor Watch decomposition plan: a guard against the
 * cycles Phase 1 was supposed to remove growing back, or new ones forming,
 * while `app-child` is still one module and nothing at compile time stops
 * a package from importing anything else in it.
 *
 * This walks `app-child`'s own source tree and builds the package-import
 * graph directly from `import dev.schrammel.protego_parentis.*` lines —
 * deliberately not a hand-maintained list, so a new file can't silently
 * add an edge the test never sees.
 *
 * An SCC analysis of the graph as it stands today (see the PR that added
 * this test) found the plan's claim that Phase 1 leaves a DAG is false in
 * two ways. The smaller one — `ui` and `ui.rules` importing each other —
 * was fixed ahead of this test rather than allow-listed. The larger one —
 * [knownCycle], five packages and about sixty individual imports deep — is
 * real, pre-dates this test, and is too large to unwind as a side effect
 * of writing a guard test. It is allow-listed here, by name, as a single
 * known exception: this test does not grade its internal edges, only that
 * the cycle is exactly this set of packages and does not grow, shrink
 * unexpectedly, or gain company elsewhere in the graph. Untangling it is
 * follow-up work, not something Phase 2 is scoped to do.
 *
 * `model`, `policy`, `provisioning`, `security`, `sync.core` and `usage`
 * (the `:core-model`, `:core-policy`, `:core-provisioning`, `:security`,
 * `:core-sync` and `:usage` modules) are deliberately excluded from the
 * graph this test builds: each is a separate Gradle module, not a package
 * of `app-child`,
 * and Gradle's own one-directional module dependency already rules out a
 * cycle through any of them. Without the exclusion, an import like
 * `model.DeviceContext` — or, since `:security` was extracted out of
 * `app-child` in Phase 3, `security.RoleStore` — fails to match a real
 * `app-child` directory at its full length and falls through to matching
 * bare `basePackage` instead, misattributing it as a *root*-package import.
 *
 * An early draft of this test made exactly that mistake for `sync.core`
 * specifically, and it briefly looked like `security` belonged in
 * [knownCycle] too. A later pass, made while extracting `:security`,
 * assumed removing its directory would be enough on its own — it is not:
 * the loop still falls through to matching bare `basePackage`, the same
 * bug, just newly triggered by a package that used to be a real `app-child`
 * directory and no longer is. Both `security`'s case and the pre-existing,
 * undetected one for `model`/`policy`/`provisioning` (harmless to the cycle
 * check, since root is already exempt either way, but wrong about what the
 * graph actually looks like) are covered by the same explicit list now,
 * rather than special-casing each one.
 */
class PackageGraphTest {

    private val basePackage = "dev.schrammel.protego_parentis"

    /**
     * The root package is the composition root ([AppRegistry],
     * [DementorApplication]) — it is wired from, and wires, nearly every
     * other package by design, so bidirectional edges through it are the
     * intended shape, not a violation. It is excluded from cycle detection
     * entirely rather than allow-listed like [knownCycle], since a
     * violation there would be "the composition root exists," which is
     * never the finding this test is looking for.
     */
    private val exemptFromCycles = setOf(basePackage)

    /**
     * The one already-known, deferred cycle. See the class doc for why this
     * is a set of packages and not a list of individual edges.
     */
    private val knownCycle = setOf(
        "$basePackage.audit",
        "$basePackage.dpc",
        "$basePackage.enforcement",
        "$basePackage.rules",
        "$basePackage.sync",
    )

    /**
     * Packages that share `app-child`'s own namespace but actually live in a
     * different Gradle module. See the comment on `resolveImportPackage`
     * (inside `buildGraph`) for why these need an explicit list rather than
     * just being absent from the known-directories set.
     */
    private val externalModulePackages = setOf(
        "$basePackage.model",
        "$basePackage.policy",
        "$basePackage.provisioning",
        "$basePackage.security",
        "$basePackage.sync.core",
        "$basePackage.usage",
    )

    private fun findSourceRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val relative = "app-child/src/main/kotlin/dev/schrammel/protego_parentis"
        while (true) {
            val candidate = File(dir, relative)
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile ?: error("could not locate $relative above ${System.getProperty("user.dir")}")
        }
    }

    private fun packageOf(root: File, file: File): String {
        val relative = file.parentFile!!.relativeTo(root).path
        return if (relative.isEmpty()) basePackage else "$basePackage.${relative.replace(File.separatorChar, '.')}"
    }

    private fun buildGraph(): Map<String, Set<String>> {
        val root = findSourceRoot()
        val knownPackages = root.walkTopDown()
            .filter { it.isDirectory }
            .map { packageOf(root, File(it, "x")) }
            .toSet() + basePackage

        val importLine = Regex("""^import\s+(dev\.schrammel\.protego_parentis(?:\.[a-zA-Z0-9_]+)*)""", RegexOption.MULTILINE)

        fun resolveImportPackage(import: String): String? {
            // These packages share app-child's own namespace but live in other
            // Gradle modules (:core-model, :core-policy, :core-provisioning,
            // :core-sync, :security) — Gradle's one-directional module
            // dependencies already forbid a cycle through any of them. Without
            // this check, an import like model.DeviceContext fails to match any
            // real app-child directory at its full length and then falls
            // through the loop below all the way down to matching bare
            // `basePackage` — misattributing it as a root-package import,
            // exactly the mistake an early draft of this test made for
            // sync.core specifically (see the class doc).
            if (externalModulePackages.any { import == it || import.startsWith("$it.") }) return null
            val parts = import.split(".")
            for (length in parts.size - 1 downTo 1) {
                val candidate = parts.subList(0, length).joinToString(".")
                if (candidate in knownPackages) return candidate
            }
            return null
        }

        val edges = mutableMapOf<String, MutableSet<String>>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val ownPackage = packageOf(root, file)
            val text = file.readText()
            importLine.findAll(text).forEach { match ->
                val importedPackage = resolveImportPackage(match.groupValues[1])
                if (importedPackage != null && importedPackage != ownPackage) {
                    edges.getOrPut(ownPackage) { mutableSetOf() }.add(importedPackage)
                }
            }
        }
        return edges
    }

    /** Tarjan's SCC algorithm, restricted to nodes not in [exemptFromCycles]. */
    private fun stronglyConnectedComponents(graph: Map<String, Set<String>>): List<Set<String>> {
        val nodes = (graph.keys + graph.values.flatten()).toSet() - exemptFromCycles
        var index = 0
        val indexOf = mutableMapOf<String, Int>()
        val lowLink = mutableMapOf<String, Int>()
        val onStack = mutableSetOf<String>()
        val stack = ArrayDeque<String>()
        val result = mutableListOf<Set<String>>()

        fun edgesFrom(node: String) = (graph[node] ?: emptySet()) - exemptFromCycles

        fun strongConnect(v: String) {
            indexOf[v] = index
            lowLink[v] = index
            index++
            stack.addLast(v)
            onStack.add(v)

            for (w in edgesFrom(v)) {
                if (w !in indexOf) {
                    strongConnect(w)
                    lowLink[v] = minOf(lowLink.getValue(v), lowLink.getValue(w))
                } else if (w in onStack) {
                    lowLink[v] = minOf(lowLink.getValue(v), indexOf.getValue(w))
                }
            }

            if (lowLink[v] == indexOf[v]) {
                val component = mutableSetOf<String>()
                while (true) {
                    val w = stack.removeLast()
                    onStack.remove(w)
                    component.add(w)
                    if (w == v) break
                }
                result.add(component)
            }
        }

        for (node in nodes) {
            if (node !in indexOf) strongConnect(node)
        }
        return result
    }

    @Test
    fun `the only cycle in the package graph is the known, deferred one`() {
        val graph = buildGraph()
        val cycles = stronglyConnectedComponents(graph).filter { it.size > 1 }

        assertTrue(
            "expected exactly one known cycle ($knownCycle) but found: $cycles",
            cycles.size <= 1,
        )
        cycles.firstOrNull()?.let { found ->
            assertTrue(
                "the known cycle changed shape.\n  expected: $knownCycle\n  found:    $found\n" +
                    "If it shrank, narrow the allow-list. If it grew, that's a regression: " +
                    "a new package started depending on the cycle in a way that pulled it in.",
                found == knownCycle,
            )
        }
    }

    @Test
    fun `no package outside the known cycle has a two-way relationship with it`() {
        // Belt and braces: even if a bug in the SCC computation above ever
        // hid a real change, this independently checks the one thing that
        // would actually mean the cycle grew — some package outside
        // knownCycle both importing from, and being imported by, a package
        // inside it.
        val graph = buildGraph()
        val offenders = graph.keys.filter { pkg ->
            pkg !in knownCycle && pkg !in exemptFromCycles &&
                (graph[pkg] ?: emptySet()).any { it in knownCycle } &&
                knownCycle.any { member -> pkg in (graph[member] ?: emptySet()) }
        }

        assertTrue(
            "these packages now form a two-way relationship with the known cycle and should either " +
                "join the allow-list (if that's an intended, understood expansion) or have the new edge " +
                "removed: $offenders",
            offenders.isEmpty(),
        )
    }
}
