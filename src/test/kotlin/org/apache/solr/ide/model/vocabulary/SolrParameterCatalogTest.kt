package org.apache.solr.ide.model.vocabulary

import org.apache.solr.ide.model.SolrVersionSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The generated parameter resource, and the reader that turns it into a vocabulary.
 *
 * Plain JUnit 4: nothing here touches the platform, because nothing about reading a resource does.
 */
class SolrParameterCatalogTest {

    private val latest = SolrVersionSelection.DEFAULT
    private val solr9 = SolrVersionSelection.fromLuceneMatchVersion("9.12.0")

    @Test
    fun `every supported line ships a parameter resource`() {
        for (line in SolrParameterCatalog.SUPPORTED_LINES) {
            val version = SolrVersionSelection.fromLuceneMatchVersion("$line.0.0")
            assertTrue(
                "line $line ships no parameters",
                SolrParameterCatalog.parametersFor(version).size > 100,
            )
            assertTrue(
                "line $line ships no query parser names",
                SolrParameterCatalog.queryParserNamesFor(version).size > 20,
            )
        }
    }

    /**
     * No shipped row is dropped for naming a kind this reader has never heard of.
     *
     * **The guard the class catalog learned to want the hard way.** [SolrParameterCatalog.parse] skips a
     * row whose kind it does not recognize, which is deliberate — a resource written by a newer build
     * must cost one row rather than the file — but it also means a generator that starts emitting a third
     * kind changes nothing at all, with no test failing. That is precisely how eighteen class kinds and
     * five hundred rows once arrived without altering a single thing the editor did.
     */
    @Test
    fun `every kind the shipped resources name is known to the reader`() {
        val known = setOf(SolrParameterCatalog.PARAMETER_KIND, SolrParameterCatalog.QUERY_PARSER_KIND)
        for (line in SolrParameterCatalog.SUPPORTED_LINES) {
            val stream = SolrParameterCatalog::class.java
                .getResourceAsStream("/solr-catalog/parameters-$line.tsv")
            assertNotNull("line $line ships no resource", stream)
            val tokens = stream!!.bufferedReader().useLines { rows ->
                rows.filterNot { it.startsWith("#") || it.isBlank() }
                    .map { it.substringBefore('\t') }
                    .toSet()
            }
            assertTrue("line $line ships no rows", tokens.isNotEmpty())
            assertTrue("line $line names kinds this reader drops: ${tokens - known}", (tokens - known).isEmpty())
        }
    }

    /**
     * The parameters a reader asked about by name, each with Solr's own sentence behind it.
     *
     * **Named rather than counted**, for the reason the class catalog's assertions give: a count moves
     * with the Solr line and fails for the wrong reason. These four are the ones reported as inert, and
     * the documentation assertion is the part that would break silently — a selection rule that started
     * dropping the declaring field name would leave the names present and every summary blank.
     */
    @Test
    fun `the everyday parameters carry Solr's own description`() {
        val expected = mapOf(
            "qf" to "query fields",
            "pf" to "phrase boost",
            "df" to "default query field",
            "rows" to "number of documents",
        )
        for ((name, fragment) in expected) {
            val entry = SolrParameterCatalog.parameter(name, latest)
            assertNotNull("expected $name", entry)
            assertTrue(
                "expected $name to read like '$fragment', got '${entry!!.summary}'",
                entry.summary?.contains(fragment) == true,
            )
        }
    }

    /**
     * `defType`, `q.op` and `sow` are present despite being declared outside the params package.
     *
     * They live in `org.apache.solr.search.QueryParsing`, and the first version of the generator scoped
     * itself to `org.apache.solr.common.params` — producing 340 parameters with the single most
     * asked-about one missing. A plausible list and no error, which is this generator's standing failure
     * mode; naming them here is what makes a repeat a failure.
     */
    @Test
    fun `the parameters declared outside the params package are present`() {
        for (name in listOf("defType", "q.op", "sow")) {
            assertNotNull("expected $name", SolrParameterCatalog.parameter(name, latest))
        }
    }

    /** A parameter Solr does not declare is absent, and absence is not invalidity. */
    @Test
    fun `an unknown parameter resolves to nothing`() {
        assertNull(SolrParameterCatalog.parameter("my.own.param", latest))
    }

    /**
     * The selection rules held, against the constants that motivated each of them.
     *
     * A request path, a response key and a prefix are all `public static final String` in the same
     * interfaces as the parameters, and all three reached the first generated resource.
     */
    @Test
    fun `no request path response key or prefix reached the resource`() {
        val names = SolrParameterCatalog.parametersFor(latest).map { it.name }
        assertTrue("a path is not a parameter: ${names.filter { '/' in it }}", names.none { '/' in it })
        assertTrue("a prefix is not a parameter: ${names.filter { it.endsWith('.') }}", names.none { it.endsWith('.') })
        assertTrue("a response key is not a parameter", "OK" !in names && "FAILURE" !in names)
        assertTrue("an operator is not a parameter", "AND" !in names && "OR" !in names)
        // The admin vocabularies, which are sent to `/admin/collections` and never written in a configset.
        assertTrue("an admin parameter does not belong here", "replicationFactor" !in names)
    }

    /** The registered names are what `defType` takes, and they are not class names. */
    @Test
    fun `the query parser names are registry keys rather than classes`() {
        val names = SolrParameterCatalog.queryParserNamesFor(latest).map { it.name }
        assertTrue("expected edismax among $names", "edismax" in names)
        assertTrue("no class name belongs here", names.none { it.startsWith("solr.") || it.endsWith("Plugin") })
        val edismax = SolrParameterCatalog.queryParserName("edismax", latest)
        assertEquals("org.apache.solr.search.ExtendedDismaxQParserPlugin", edismax?.owner)
    }

    /** Both lines ship their own resource, read separately. */
    @Test
    fun `each line answers from its own resource`() {
        assertNotNull(SolrParameterCatalog.parameter("qf", solr9))
        assertNotNull(SolrParameterCatalog.queryParserName("edismax", solr9))
    }

    /** A version this plugin does not support falls back to the newest line rather than to silence. */
    @Test
    fun `an unsupported line falls back to the newest`() {
        val ancient = SolrVersionSelection.fromLuceneMatchVersion("7.0.0")
        assertEquals(
            SolrParameterCatalog.parametersFor(latest),
            SolrParameterCatalog.parametersFor(ancient),
        )
    }

    // --- the parser, against input the generator would never produce -------------------------------

    /**
     * A malformed row costs its own line, not the file. This parser is the one place a half-written or
     * truncated resource would reach the editor.
     */
    @Test
    fun `a malformed row is dropped and the rest survive`() {
        val parsed = SolrParameterCatalog.parse(
            sequenceOf(
                "# a comment",
                "",
                "   ",
                "parameter\tqf\torg.apache.solr.common.params.DisMaxParams\tquery fields",
                "truncated\trow",
                "notAKind\tsomething\towner\tdocumentation",
                "parameter\t\towner\ta nameless row",
                "queryParserName\tedismax\torg.apache.solr.search.ExtendedDismaxQParserPlugin\t",
            ),
        )
        assertEquals(listOf("qf"), parsed.parameters.map { it.name })
        assertEquals(listOf("edismax"), parsed.queryParserNames.map { it.name })
        // A blank documentation column reads as nothing to show rather than as an empty sentence.
        assertNull(parsed.queryParserNames.single().summary)
    }

    /** A row from a resource generated before the documentation column existed still parses. */
    @Test
    fun `a row without a documentation column parses`() {
        val parsed = SolrParameterCatalog.parse(
            sequenceOf("parameter\tqf\torg.apache.solr.common.params.DisMaxParams"),
        )
        val entry = parsed.parameters.single()
        assertEquals("qf", entry.name)
        assertNull(entry.summary)
    }
}
