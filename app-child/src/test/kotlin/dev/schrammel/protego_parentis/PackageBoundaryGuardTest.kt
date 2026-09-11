package dev.schrammel.protego_parentis

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Door-jammer for the package dependency graph inside [app-child].
 *
 * Every directed edge between [knownPackages] that currently exists is recorded
 * in [allowedEdges].  A new edge that is not in the set fails the build until
 * someone adds it here — at which point the architectural intent is visible in
 * the diff.
 *
 * As Phase 1 steps remove edges, delete the corresponding entries from
 * [allowedEdges] and re-run the build.  The edge must vanish from the source
 * code first.
 */
class PackageBoundaryGuardTest {

    companion object {
        private const val PREFIX = "dev.schrammel.protego_parentis."
        private const val SRC = "src/main/kotlin/dev/schrammel/protego_parentis"

        /** Packages whose source lives in app-child. */
        private val knownPackages = setOf(
            "audit", "context", "diagnostics", "dpc", "enforcement",
            "rules", "security", "setup", "sync", "ui", "ui.rules", "usage",
        )

        /** First-segment prefixes of packages defined in other Gradle modules. */
        private val externalPrefixes = setOf(
            "model", "policy", "provisioning", "sync.core",
        )

        /**
         * Every directed edge (source → target) that currently exists between
         * [knownPackages].  Any edge not in this set fails the build.
         */
        private val allowedEdges: Set<Pair<String, String>> = setOf(
            // Edges to external modules (model, policy, provisioning, sync.core)
            // are filtered out by the scanner and not tracked here.
            "audit" to "sync",
            "diagnostics" to "ui",
            "dpc" to "rules",
            "enforcement" to "audit",
            "enforcement" to "context",
            "enforcement" to "diagnostics",
            "enforcement" to "dpc",
            "enforcement" to "rules",
            "enforcement" to "security",
            "enforcement" to "sync",
            "enforcement" to "ui",
            "enforcement" to "usage",
            "rules" to "audit",
            "rules" to "enforcement",
            "rules" to "sync",
            "rules" to "usage",
            "setup" to "dpc",
            "setup" to "enforcement",
            "setup" to "security",
            "setup" to "ui",
            "sync" to "audit",
            "sync" to "context",
            "sync" to "diagnostics",
            "sync" to "enforcement",
            "sync" to "rules",
            "sync" to "security",
            "sync" to "ui",
            "sync" to "usage",
            "ui" to "audit",
            "ui" to "context",
            "ui" to "diagnostics",
            "ui" to "enforcement",
            "ui" to "rules",
            "ui" to "security",
            "ui" to "sync",
            "ui" to "ui.rules",
            "ui" to "usage",
            "ui.rules" to "enforcement",
            "ui.rules" to "rules",
            "ui.rules" to "sync",
            "ui.rules" to "ui",
            "ui.rules" to "usage",
        )
    }

    private fun scanEdges(): Set<Pair<String, String>> {
        val root = File(System.getProperty("user.dir"), SRC)
        assertTrue(
            "Source root not found — is this test running inside app-child? ${root.absolutePath}",
            root.isDirectory,
        )

        val edges = mutableSetOf<Pair<String, String>>()

        for (file in root.walkTopDown().filter { it.isFile && it.extension == "kt" }) {
            val lines = file.readLines()

            val pkgLine = lines.firstOrNull { it.startsWith("package ") } ?: continue
            val pkgFqn = pkgLine.removePrefix("package ").trim()
            if (!pkgFqn.startsWith(PREFIX)) continue
            val sourcePkg = toKnownPackage(pkgFqn.removePrefix(PREFIX)) ?: continue

            for (line in lines) {
                if (!line.startsWith("import $PREFIX")) continue
                val fqn = line.removePrefix("import $PREFIX").trim()

                if (externalPrefixes.any { fqn == it || fqn.startsWith("$it.") }) continue
                val targetPkg = toKnownPackage(fqn) ?: continue
                if (targetPkg != sourcePkg) edges.add(sourcePkg to targetPkg)
            }
        }

        return edges
    }

    /**
     * Maps the part after [PREFIX] of a fully-qualified name to a [knownPackages]
     * entry, or returns null if it does not land inside one.
     */
    private fun toKnownPackage(fqn: String): String? {
        val segments = fqn.split(".")
        val first = segments[0]
        val second = segments.getOrNull(1)
        val pkg = if (first == "ui" && second == "rules") "ui.rules" else first
        return pkg.takeIf { it in knownPackages }
    }

    @Test
    fun `no dependency edges exist outside the baseline`() {
        val actual = scanEdges()
        val unknown = (actual - allowedEdges).sortedBy { "${it.first}→${it.second}" }

        assertTrue(
            "New dependency edges not in baseline:\n" +
                unknown.joinToString("\n") { "  ${it.first} → ${it.second}" } +
                "\n\nAdd these to allowedEdges if intentional, or remove the imports.",
            unknown.isEmpty(),
        )
    }

    @Test
    fun `allowedEdges only reference known packages`() {
        val bad = allowedEdges.filter { it.first !in knownPackages || it.second !in knownPackages }
        assertTrue(
            "allowedEdges references unknown packages: $bad",
            bad.isEmpty(),
        )
    }
}
