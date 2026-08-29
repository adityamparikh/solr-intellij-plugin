package org.apache.solr.ide.server

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Nothing on the editor path may reach a running server, and this is what holds that to more than
 * prose.
 *
 * **Stated as an allowlist of server consumers rather than a denylist of editor packages, and that
 * is the whole of the design.** An earlier draft of the rule enumerated the editor-facing packages
 * to forbid, and two of the five named did not exist — a hand-maintained list of what to forbid was
 * wrong on the day it was written and would have gone quietly wronger with every package added,
 * because a package missing from a denylist is a package the test permits. This plugin has already
 * paid for a hand-maintained copy of a list, twice, in the supported Solr lines that drifted in both
 * directions until a test compared them against what the build generated.
 *
 * Inverted, the failure mode inverts with it: a new package that reaches for the server fails this
 * test until someone adds it to [ALLOWED_CONSUMERS] deliberately, in a diff a reviewer sees.
 *
 * Until Step 11 there was nothing to enforce, because nothing on the editor path had a server
 * package to import. There is now.
 */
class SolrServerBoundaryContractTest {

    /**
     * Package prefixes permitted to reach `org.apache.solr.ide.server`.
     *
     * The server surface itself, and — as the specification anticipates — the tool windows and
     * actions that exist to talk to a server. Those live under `server` too, per the code
     * organization's rule that `org.apache.solr.ide.server.*` *is* the live-server surface, so this
     * list has one entry rather than a growing tail of UI packages.
     *
     * Adding to it is a deliberate act. Anything that would need adding because an editor feature
     * wanted "just to check the live server" is the thing this test exists to stop.
     */
    private val allowedConsumers = listOf("org.apache.solr.ide.server")

    private val serverPackage = "org.apache.solr.ide.server"

    private fun sourceRoot(): File {
        // Gradle runs tests from the project directory; the worktree layout puts the sources under
        // it either way. Resolved rather than assumed so a failure names a missing directory instead
        // of silently walking nothing and passing.
        val root = File("src/main/kotlin")
        assertTrue("expected Kotlin sources at ${root.absolutePath}", root.isDirectory)
        return root
    }

    private fun packageOf(file: File): String =
        file.readLines().firstOrNull { it.startsWith("package ") }?.removePrefix("package ")?.trim().orEmpty()

    /**
     * Lines mentioning the server package, with comments and KDoc dropped.
     *
     * A doc comment may legitimately *name* the boundary — this plugin's prose does so constantly —
     * and failing on a sentence about the rule would make the rule unstateable.
     */
    private fun serverReferencesIn(file: File): List<String> = file.readLines()
        .map { it.trim() }
        .filterNot { it.startsWith("*") || it.startsWith("//") || it.startsWith("/*") }
        .filter { it.contains(serverPackage) }

    @Test
    fun `only the live-server surface reaches the live-server surface`() {
        val trespassers = sourceRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { file -> allowedConsumers.any { packageOf(file).startsWith(it) } }
            .mapNotNull { file ->
                serverReferencesIn(file).takeIf { it.isNotEmpty() }?.let { file.path to it }
            }
            .toList()

        if (trespassers.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("Nothing on the editor path may reach a running server.")
                    appendLine("These files import or name org.apache.solr.ide.server:")
                    trespassers.forEach { (path, lines) ->
                        appendLine("  $path")
                        lines.forEach { appendLine("      $it") }
                    }
                    appendLine()
                    append("If one of them genuinely exists to talk to a server, add its package to allowedConsumers.")
                },
            )
        }
    }

    /**
     * The test can actually see the sources it claims to check.
     *
     * Without this the suite above passes just as well against an empty directory, a wrong working
     * directory, or a glob that matches nothing — a green tick carrying no information, which is the
     * failure mode this plugin has hit four times and now checks for on purpose.
     */
    @Test
    fun `the walk reaches the sources it claims to check`() {
        val files = sourceRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

        assertTrue("expected to find Kotlin sources, found ${files.size}", files.size > 50)
        assertTrue(
            "expected to find the server package itself among them",
            files.any { packageOf(it).startsWith(serverPackage) },
        )
    }
}
