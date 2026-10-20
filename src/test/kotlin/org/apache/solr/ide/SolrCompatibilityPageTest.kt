package org.apache.solr.ide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
 * Plain JUnit over two files: no fixture, and no reason for one. The last few tests exercise the
 * readers at the bottom of this file on tables written here, because a reader that only ever saw the
 * real page would fail the first time a Markdown formatter padded a column — and a drift test that
 * breaks on a reformat gets deleted rather than fixed.
 */
class SolrCompatibilityPageTest {

    /**
     * The page under test. The build names it; outside Gradle the working directory is the project
     * root, as the demo-configset tests assume, so a runner without the build's properties still
     * finds it.
     */
    private val page: String by lazy {
        File(System.getProperty("compat.page") ?: File(System.getProperty("user.dir"), "docs/compatibility.md").path)
            .readText()
    }

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
        val supported = plain(cell(page, "IDE"))
        assertTrue(
            "IDE row says '$supported', the build targets $target",
            supported.contains("IntelliJ IDEA $target and later"),
        )
    }

    /**
     * The verified builds on the page are exactly the builds the Verifier runs against, in full.
     *
     * The build declares a release (`2026.2`) and the page names the build it resolved to
     * (`IU-262.8665.258`), because the resolved number is what a reader can look up. The test task
     * hands over the notation read from each IDE `verifyPlugin` resolves, so this compares the whole
     * number: comparing by branch alone let a page name `IU-262.1111.111`, a build nothing checked,
     * and still pass.
     */
    @Test
    fun `the verified row names exactly the builds the Verifier checks`() {
        val resolved = verifiedBuilds()
        val named = buildNotationsIn(cell(page, "Verified against"))
        assertEquals("verified builds on the page against what verifyPlugin resolves", resolved, named.toSet())
        assertEquals("a verified build is named twice on the page", named.size, named.toSet().size)
    }

    /** The prose repeats the verified build, and a stale copy there is believed as much as one in the table. */
    @Test
    fun `no build named anywhere on the page is one the Verifier skips`() {
        val resolved = verifiedBuilds()
        val stray = buildNotationsIn(page).toSet() - resolved
        assertTrue("the page names $stray, which the Verifier does not check (it checks $resolved)", stray.isEmpty())
    }

    /**
     * Each release `verifiedIdeBuilds` declares resolved to one build on that release's branch.
     *
     * This checks the build rather than the page: it is what makes the resolved builds a faithful
     * stand-in for the declared releases, so the two tests above hold the page to the declaration.
     */
    @Test
    fun `each declared release resolved to a build on its own branch`() {
        val declared = required("compat.verifiedIdes").split(",").map(::branchOf).sorted()
        val resolved = verifiedBuilds().map(::branchOf).sorted()
        assertEquals("branches of verifiedIdeBuilds against the builds verifyPlugin resolved", declared, resolved)
    }

    @Test
    fun `the Solr row names exactly the releases the catalogs are generated from`() {
        val declared = required("compat.solrReleases").split(",").toSet()
        val named = Regex("""\b\d+\.\d+\.\d+\b""")
            .findAll(cell(page, "Apache Solr"))
            .map { it.value }
            .toSet()
        assertEquals("Solr releases on the page against supportedSolrLines", declared, named)
    }

    @Test
    fun `the toolchain row names the JDK the build pins`() {
        val toolchain = required("compat.toolchain")
        assertEquals("JDK $toolchain", plain(cell(page, "Build toolchain")))
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

    @Test
    fun `a row a table formatter padded is still found`() {
        val padded = """
            | What             | Supported                                | Declared in |
            |------------------|------------------------------------------|-------------|
            | IDE              | **IntelliJ IDEA 2026.2 and later**       | x           |
            | Build toolchain  | **`JDK 21`**                             | y           |
        """.trimIndent()
        assertEquals("**IntelliJ IDEA 2026.2 and later**", cell(padded, "IDE"))
        assertEquals("JDK 21", plain(cell(padded, "Build toolchain")))
    }

    @Test
    fun `a row is found by its whole first cell, not a prefix of it`() {
        val table = """
            | IDE version | wrong |
            | IDE | right |
        """.trimIndent()
        assertEquals("right", cell(table, "IDE"))
        val missing = assertThrows(AssertionError::class.java) { cell(table, "Apache Solr") }
        assertTrue(missing.message.orEmpty().contains("'Apache Solr'"))
    }

    @Test
    fun `a build is read in full rather than by branch`() {
        assertEquals(
            listOf("IU-262.1111.111", "IU-263.12.4"),
            buildNotationsIn("runs against `IU-262.1111.111`, and **IU-263.12.4** next"),
        )
    }

    @Test
    fun `a branch is read from a release, a build number or an IDE notation alike`() {
        assertEquals(
            listOf("262", "262", "262"),
            listOf("2026.2", "262.8665.258", "IU-262.8665.258").map(::branchOf),
        )
    }

    @Test
    fun `a value with no branch in it says so rather than guessing`() {
        val failure = assertThrows(IllegalArgumentException::class.java) { branchOf("latest") }
        assertTrue(failure.message.orEmpty().contains("'latest'"))
    }

    private fun verifiedBuilds(): Set<String> = required("compat.verifiedBuilds").split(",").toSet()

    private fun ideaVersionIn(xml: String): String =
        Regex("""<idea-version[^>]*/?>""").find(xml)?.value ?: throw AssertionError("no <idea-version> in the descriptor")

    private fun required(property: String): String = checkNotNull(System.getProperty(property)) {
        "$property is set by the test task in build.gradle.kts and is what this asserts against"
    }
}

/**
 * The supported cell of the table row in [markdown] whose first cell is [what].
 *
 * Matched on the trimmed first cell, so a table a formatter has padded to align its columns reads the
 * same as one written tight, and `IDE` does not take the row `IDE version`.
 */
private fun cell(markdown: String, what: String): String {
    val first = Regex("""^\|\s*${Regex.escape(what)}\s*\|""")
    val row = markdown.lines().firstOrNull { first.containsMatchIn(it.trim()) }
        ?: throw AssertionError("docs/compatibility.md has no '$what' row")
    return row.split("|").map { it.trim() }[2]
}

/** A cell's words without its emphasis or code spans: what it says, not how it is set. */
private fun plain(cell: String): String = cell.replace("*", "").replace("`", "").trim()

/** Every full IDE build notation in [text], like `IU-262.8665.258`, in order of appearance. */
private fun buildNotationsIn(text: String): List<String> =
    Regex("""\b[A-Z]{2}-\d{3}(?:\.\d+)+\b""").findAll(text).map { it.value }.toList()

/**
 * The platform branch [version] belongs to: `2026.2` is `262`, the year's last two digits then the
 * release. A build number (`262.8665.258`) or IDE notation (`IU-262.8665.258`) already leads with its
 * branch, so either is read as that; anything else fails naming the value rather than guessing.
 */
private fun branchOf(version: String): String {
    val release = Regex("""^(\d{4})\.(\d+)$""").find(version)
    val build = Regex("""^(?:[A-Z]{2}-)?(\d{3})(?:\.\d+)*$""").find(version)
    return when {
        release != null -> release.groupValues[1].takeLast(2) + release.groupValues[2]
        build != null -> build.groupValues[1]
        else -> throw IllegalArgumentException(
            "'$version' is neither a release like 2026.2 nor a build like 262.8665.258, so it has no branch",
        )
    }
}
