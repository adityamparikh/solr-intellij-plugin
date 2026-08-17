package org.apache.solr.ide.model.vocabulary

import org.apache.solr.ide.model.SolrVersionSelection
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Solr lines the code declares, against the ones the build actually generated.
 *
 * **Two lists say which lines are supported and nothing made them agree.**
 * [SolrClassCatalog.SUPPORTED_LINES] and its counterpart inside [SolrElementCatalog] are written by
 * hand, and each carries a KDoc promising it is "kept in step with the `supportedSolrLines` the build
 * declares". `supportedSolrLines` in `build.gradle.kts` is the only place a line is really added or
 * dropped; these are copies of it in another language, and a copy nobody checks is a copy that drifts.
 *
 * **Both directions fail silently, which is why this is a test rather than a convention.** A line
 * declared here with no generated resource behind it reads as *empty* — deliberately, so a missing
 * catalog cannot take the editor down — so completion and documentation would simply stop answering
 * for that line with nothing in the log. A line generated but not declared is worse in a quieter way:
 * the resource ships inside the plugin, adding weight to every install, and every configset targeting
 * it silently falls back to the newest line's answers.
 *
 * Plain JUnit against the shipped resources, which is what makes it cheap enough to state three times.
 */
class SolrCatalogResourceTest {

    @Test
    fun `every declared line ships every generated resource`() {
        for (line in SolrClassCatalog.SUPPORTED_LINES) {
            for (resource in resourceNamesFor(line)) {
                assertTrue(
                    "line $line is declared but $resource was not generated",
                    SolrClassCatalog::class.java.getResource(resource) != null,
                )
            }
        }
    }

    /**
     * Every resource the plugin reads is the generator's own output, and `clean` removes it.
     *
     * **This is what "the catalog regenerates from a clean build" reduces to.** The wiring is one line
     * of `build.gradle.kts` — the generator's output directory is a resource directory of the main
     * source set, which is what makes `processResources` depend on it — and the two ways it stops
     * being true are both silent. Copies checked into `src/main/resources` would keep every other test
     * in this file green while the generator's output went stale beside them; an output directory
     * moved out from under the build directory would survive `clean`, so a build after one would ship
     * whatever the last generator to run happened to write, indefinitely.
     *
     * The two paths come from the build rather than from a literal here, so this asserts against the
     * wiring itself: the directory named is the task's own `outputDirectory`, and the directory it
     * must sit inside is the one `clean` deletes. Byte equality with the classpath copy is what says
     * the resource the plugin actually loads is that file and not another one shadowing it.
     */
    @Test
    fun `every shipped resource is the generator's output, from inside the directory clean removes`() {
        val generated = File(requiredPath("solr.catalog.generated.dir"))
        val buildDirectory = File(requiredPath("solr.build.dir"))
        assertTrue(
            "the generator writes to $generated, which clean does not remove",
            generated.canonicalPath.startsWith(buildDirectory.canonicalPath + File.separator),
        )
        for (line in SolrClassCatalog.SUPPORTED_LINES) {
            for (resource in resourceNamesFor(line)) {
                val written = File(generated, resource.removePrefix("/"))
                assertTrue("$resource was not written by the generator", written.isFile)
                assertArrayEquals(
                    "the $resource on the classpath is not the one the generator wrote",
                    written.readBytes(),
                    checkNotNull(SolrClassCatalog::class.java.getResourceAsStream(resource)) {
                        "$resource is not on the test classpath"
                    }.use { it.readBytes() },
                )
            }
        }
    }

    private fun requiredPath(property: String): String = checkNotNull(System.getProperty(property)) {
        "$property is set by the test task in build.gradle.kts and is what this asserts against"
    }

    /**
     * Nothing ships that is not declared, which is the direction a hand-written list forgets.
     *
     * Probed over a range rather than by listing a directory: a resource read through the classpath
     * has no directory to list once the plugin is a jar, and the range costs microseconds.
     */
    @Test
    fun `every shipped catalog is a declared line`() {
        val shipped = PLAUSIBLE_LINES.filter {
            SolrClassCatalog::class.java.getResource("/solr-catalog/solr-$it.tsv") != null
        }
        assertEquals(
            "the generated catalogs and SUPPORTED_LINES disagree",
            SolrClassCatalog.SUPPORTED_LINES.sorted(),
            shipped.sorted(),
        )
    }

    /**
     * Every resource for one line was read from the same Solr release.
     *
     * The generator writes the release into each header, and the passes take their input from
     * one resolved configuration — so a disagreement means a partially stale build directory, where
     * one catalog was regenerated for a new release and the others were served from the cache. The
     * result is a plugin that documents a class from one release and its parameters from another,
     * with nothing to suggest anything is wrong.
     */
    @Test
    fun `one line's catalogs were all read from one release`() {
        for (line in SolrClassCatalog.SUPPORTED_LINES) {
            val releases = resourceNamesFor(line).associateWith { releaseRecordedIn(it) }
            assertEquals(
                "line $line was generated from more than one release: $releases",
                1,
                releases.values.toSet().size,
            )
        }
    }

    /**
     * Each line's catalogs came from *that* line's release, not from another's.
     *
     * The check above would pass just as well if every resource on both lines had been generated from
     * the same Solr — which is the shape a mistake in the build's per-line wiring would take, and it
     * would leave the plugin answering identically for both while appearing to support two.
     */
    @Test
    fun `each line's release is of that line`() {
        for (line in SolrClassCatalog.SUPPORTED_LINES) {
            val release = releaseRecordedIn("/solr-catalog/solr-$line.tsv")
            assertTrue(
                "line $line was generated from $release",
                release.startsWith("$line."),
            )
        }
    }

    /**
     * Every line answers from its own resource rather than falling through to the newest.
     *
     * [SolrElementCatalog] keeps its line list private, so the only honest way to hold it to the same
     * promise is to ask it something. A vocabulary that fell back would still answer here — hence the
     * pair below, which asserts the lines differ where Solr differs.
     */
    @Test
    fun `every declared line answers from all three catalogs`() {
        for (line in SolrClassCatalog.SUPPORTED_LINES) {
            val version = versionFor(line)
            assertTrue(
                "line $line has no classes",
                SolrClassCatalog.entriesFor(version).size > 100,
            )
            assertTrue(
                "line $line has no parameters",
                SolrParameterCatalog.parametersFor(version).size > 100,
            )
            assertTrue(
                "line $line has no solrconfig.xml elements",
                SolrElementCatalog.element("requestHandler", "", version) != null,
            )
        }
    }

    /**
     * The lines are not each other, proven on the one element that separates them.
     *
     * `featureVectorCache` was added in Solr 10 and exists on no 9 release, and it is the *only*
     * difference the element vocabularies have — which makes it both the sole available proof and a
     * fragile one, so it is named here rather than left implicit. The day a line drops something this
     * gains a second witness; until then, this single element is what stands between "two catalogs"
     * and "one catalog shipped twice".
     */
    @Test
    fun `the two lines are not the same catalog`() {
        val onlyInTen = "featureVectorCache"
        // A cache, and Solr reads it off the query node — so it is looked up there rather than at the
        // root. The catalog used to answer at the root for everything whose parent it could not see.
        val itsParent = "query"
        assertTrue(
            "$onlyInTen should be part of Solr 10's vocabulary",
            SolrElementCatalog.element(onlyInTen, itsParent, versionFor(10)) != null,
        )
        assertTrue(
            "$onlyInTen should not be part of Solr 9's vocabulary",
            SolrElementCatalog.element(onlyInTen, itsParent, versionFor(9)) == null,
        )
    }

    /**
     * Everything `:generateSolrCatalog` writes for one line.
     *
     * The field-property list is here with the other three even though only a test reads it: what
     * these assertions are about is the generator's output being complete and current, and a resource
     * left out of the list is one nothing would notice going stale.
     */
    /**
     * No shipped element carries a position the generator could not establish.
     *
     * The generator marks such a parent `?` rather than leaving it empty, because empty already
     * means *top level* and conflating the two is what placed `<nrtMode>` under `<config>` — a
     * position Solr never reads — while the discontinued-element rule stayed silent on the one that
     * stops a core starting. Nothing consumes a `?` row today, so an element wearing one is simply
     * absent from every surface: invisible rather than wrong, which is the intended failure.
     *
     * **This asserts the count is still zero, which is a claim about Solr as much as about the
     * scan.** Both supported lines currently reach every element through a shape the pass can
     * follow. A future line that stores a node in a local inside `SolrConfig` would break that, and
     * the first symptom is an element quietly missing from completion — which no other test here
     * would notice, because every other assertion names elements that *are* present.
     */
    @Test
    fun `no shipped element has a position the scan could not establish`() {
        listOf(9, 10).forEach { line ->
            val resource = "/solr-catalog/elements-$line.tsv"
            val text = checkNotNull(SolrClassCatalog::class.java.getResourceAsStream(resource)) {
                "$resource is not on the test classpath"
            }.use { it.readBytes().decodeToString() }

            val unplaced = text.lineSequence()
                .filterNot { it.startsWith("#") || it.isBlank() }
                .map { it.split('\t') }
                .filter { it.size > 1 && (it[1] == "?" || it[1].startsWith("?/")) }
                .map { it[0] }
                .toList()

            assertTrue(
                "Solr $line ships elements the scan could not place, so they reach no surface: $unplaced",
                unplaced.isEmpty(),
            )
        }
    }

    private fun resourceNamesFor(line: Int) = listOf(
        "/solr-catalog/solr-$line.tsv",
        "/solr-catalog/parameters-$line.tsv",
        "/solr-catalog/elements-$line.tsv",
        "/solr-catalog/field-properties-$line.txt",
    )

    /**
     * The release named in a generated resource's header, as the generator wrote it.
     *
     * The header line reads `# Solr line 9, read from 9.10.1.` — the same sentence the class catalog
     * already reads its Reference Guide segment out of, so this asserts against what the plugin
     * itself consults rather than against a second recording of the same fact.
     */
    private fun releaseRecordedIn(resource: String): String {
        val text = checkNotNull(SolrClassCatalog::class.java.getResourceAsStream(resource)) {
            "$resource is not on the test classpath"
        }.use { it.readBytes().decodeToString() }
        val header = text.lineSequence().first { it.startsWith("# Solr line ") }
        return checkNotNull(RELEASE.find(header)) { "no release in: $header" }.value
    }

    private fun versionFor(line: Int) = SolrVersionSelection.fromLuceneMatchVersion("$line.0.0")

    private companion object {
        /**
         * The majors worth probing for an undeclared resource.
         *
         * Solr 1 shipped in 2006 and this range runs well past anything the build could target, so
         * the upper bound is the thing that would have to be raised — long after a released Solr 40
         * had made it obvious.
         */
        val PLAUSIBLE_LINES = 1..40

        val RELEASE = Regex("""\d+\.\d+\.\d+""")
    }
}
