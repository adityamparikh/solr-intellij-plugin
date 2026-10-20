package org.apache.solr.ide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `docs/compatibility.md` against the declarations it names.
 *
 * **The page promises what the build declares, and nothing made the two agree.** Its table names an
 * IDE range, the build the Verifier checks, two Solr releases and a toolchain, and its last column
 * says where in the build each is declared. Until this test, the page itself admitted that keeping it
 * in step was a reviewer's job — and a matrix promising a build the Verifier never checked is worse
 * than no matrix, because it is believed.
 *
 * Every expected value is handed over by the test task in `build.gradle.kts`, read off the same
 * declaration the build uses rather than restated here, so this asserts against the build itself. The
 * IDE range comes from the descriptor `patchPluginXml` writes, because that — not a value anyone typed
 * — is what the Marketplace reads.
 *
 * Plain JUnit over two files: no fixture, and no reason for one.
 */
class SolrCompatibilityPageTest {

    private val page: String by lazy { File(required("compat.page")).readText() }

    private val descriptor: String by lazy { File(required("compat.descriptor")).readText() }

    /** The page's section for the version this build produces, which is where the table lives. */
    @Test
    fun `the page describes the version this build produces`() {
        val version = required("compat.pluginVersion")
        assertTrue(
            "docs/compatibility.md has no '## Plugin $version' section; it describes another release",
            page.lines().any { it.trim() == "## Plugin $version" },
        )
    }

    @Test
    fun `the IDE row names the release the build compiles against`() {
        val target = required("compat.targetIde")
        val supported = cell("IDE").replace("*", "")
        assertTrue(
            "IDE row says '$supported', the build targets $target",
            supported.contains("IntelliJ IDEA $target and later"),
        )
    }

    /**
     * The verified builds on the page are exactly the ones the Verifier is told to check.
     *
     * The build declares a release (`2026.2`) and the page names the build it resolved to
     * (`IU-262.8665.258`), because the resolved number is what a reader can look up. They are compared
     * by branch — `2026.2` is branch `262` — which is the one fact both spellings carry.
     */
    @Test
    fun `the verified row names one build per release the Verifier checks`() {
        val declared = required("compat.verifiedIdes").split(",").map(::branchOf).toSet()
        val named = Regex("""IU-(\d+)\.[\d.]+""")
            .findAll(cell("Verified against"))
            .map { it.groupValues[1] }
            .toList()
        assertEquals("verified builds on the page against verifiedIdeBuilds", declared, named.toSet())
        assertEquals("a verified build is named twice on the page", named.size, named.toSet().size)
    }

    @Test
    fun `the Solr row names exactly the releases the catalogs are generated from`() {
        val declared = required("compat.solrReleases").split(",").toSet()
        val named = Regex("""\*\*([\d.]+)\*\*""")
            .findAll(cell("Apache Solr"))
            .map { it.groupValues[1] }
            .toSet()
        assertEquals("Solr releases on the page against supportedSolrLines", declared, named)
    }

    @Test
    fun `the toolchain row names the JDK the build pins`() {
        val toolchain = required("compat.toolchain")
        assertEquals("JDK $toolchain", cell("Build toolchain"))
    }

    /**
     * The range the page claims is the range the descriptor declares: a lower bound and no upper one.
     *
     * "2026.2 and later" is only true while the patched descriptor carries no `until-build`. The
     * IntelliJ Platform Gradle Plugin decides both attributes, and a version of it that started
     * writing an upper bound would quietly make the page's prose about an open range false.
     */
    @Test
    fun `the descriptor declares the range the page claims`() {
        val branch = branchOf(required("compat.targetIde"))
        assertTrue(
            "the patched descriptor's since-build is not $branch:\n${ideaVersionIn(descriptor)}",
            ideaVersionIn(descriptor).contains("""since-build="$branch""""),
        )
        assertFalse(
            "the patched descriptor declares an upper bound the page says does not exist",
            ideaVersionIn(descriptor).contains("until-build"),
        )
        assertTrue(
            "the page does not state since-build=\"$branch\"",
            page.contains("""`since-build="$branch"`"""),
        )
    }

    /** The supported cell of the table row whose first cell is [what]. */
    private fun cell(what: String): String {
        val row = page.lines().firstOrNull { it.startsWith("| $what |") }
            ?: throw AssertionError("docs/compatibility.md has no '$what' row")
        return row.split("|").map { it.trim() }[2]
    }

    private fun ideaVersionIn(xml: String): String =
        Regex("""<idea-version[^>]*/?>""").find(xml)?.value ?: throw AssertionError("no <idea-version> in the descriptor")

    /** `2026.2` → `262`: the platform's own branch numbering, year's last two digits then release. */
    private fun branchOf(release: String): String {
        val (year, minor) = release.split(".")
        return year.takeLast(2) + minor
    }

    private fun required(property: String): String = checkNotNull(System.getProperty(property)) {
        "$property is set by the test task in build.gradle.kts and is what this asserts against"
    }
}
