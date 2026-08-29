package org.apache.solr.ide.server.query

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a query's answer into the shape a table needs.
 *
 * The fixtures are responses captured from running Solr on both supported lines, indexing three
 * books and querying `title:dune`. Two of the shapes asserted here were found by reading that output
 * rather than by imagining it: a single-valued field arriving as an array, and the scoring
 * explanation arriving as text Solr has already indented.
 */
class SolrQueryResultReaderTest {

    private fun captured(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/server-responses/$name")) { "missing fixture $name" }
            .bufferedReader().readText()

    private fun resultOf(name: String) = checkNotNull(SolrQueryResultReader.read(captured(name))) {
        "$name should read as a query response"
    }

    // --- deciding whether a response is ours at all ------------------------------------------------

    /**
     * A schema response is left alone.
     *
     * It carries `responseHeader` exactly as a query does and no documents, and it is already
     * readable JSON. Rendering an empty table over it would be this plugin taking over a view it has
     * nothing to add to.
     */
    @Test
    fun `a schema response is not a query result`() {
        assertNull(SolrQueryResultReader.read(captured("schema-10.json")))
    }

    @Test
    fun `a luke response is not a query result`() {
        assertNull(SolrQueryResultReader.read(captured("luke-10.json")))
    }

    @Test
    fun `a system info response is not a query result`() {
        assertNull(SolrQueryResultReader.read(captured("system-info-10.json")))
    }

    /**
     * Somebody else's JSON is left alone.
     *
     * This presentation is offered for every response the HTTP Client shows, so declining is the
     * common case and getting it wrong means hijacking another plugin's output.
     */
    @Test
    fun `an unrelated json response is not a query result`() {
        assertNull(SolrQueryResultReader.read("""{"items":[{"id":1}],"total":1}"""))
    }

    @Test
    fun `text that is not json is not a query result`() {
        assertNull(SolrQueryResultReader.read("<html><body>404</body></html>"))
    }

    /** A response whose `docs` is not an array is declined rather than half-read. */
    @Test
    fun `a header without documents is not a query result`() {
        assertNull(SolrQueryResultReader.read("""{"responseHeader":{"status":0},"response":{"numFound":2}}"""))
    }

    /** An empty result set is ours, and is answered differently from "not ours". */
    @Test
    fun `a query that matched nothing is still a query result`() {
        val result = SolrQueryResultReader.read(
            """{"responseHeader":{"status":0,"QTime":1},"response":{"numFound":0,"start":0,"docs":[]}}""",
        )

        assertNotNull("no documents is an answer, not a non-answer", result)
        assertEquals(0L, result!!.numFound)
        assertTrue(result.rows.isEmpty())
    }

    // --- the summary ------------------------------------------------------------------------------

    @Test
    fun `the header reports what solr said`() {
        val result = resultOf("select-10.json")

        assertEquals(0, result.status)
        assertEquals(32, result.queryTimeMillis)
    }

    /** How many matched is not how many came back, and both are reported. */
    @Test
    fun `matches and returned rows are separate numbers`() {
        val result = resultOf("select-10.json")

        assertEquals(2L, result.numFound)
        assertEquals(0L, result.start)
        assertEquals(2, result.rows.size)
    }

    // --- the columns ------------------------------------------------------------------------------

    @Test
    fun `columns are the fields the documents carry`() {
        val result = resultOf("select-10.json")

        assertEquals(listOf("id", "title", "author_s", "price_f", "year_i", "tags_ss"), result.columns)
    }

    /**
     * Solr's own internal fields are kept out of the table and named.
     *
     * `_version_` comes back on every document, is nineteen digits wide, and would decide the shape
     * of every table it appeared in while telling the reader nothing they asked for. Dropped from
     * the columns and listed, so the omission is stated rather than left to be noticed — and the
     * full JSON prints underneath regardless, so nothing is actually withheld.
     */
    @Test
    fun `internal fields are hidden and reported`() {
        val result = resultOf("select-10.json")

        assertFalse(result.columns.toString(), result.columns.contains("_version_"))
        assertTrue(result.hiddenColumns.toString(), result.hiddenColumns.containsAll(listOf("_version_", "_root_")))
    }

    /** A user field that merely starts with an underscore is not internal. */
    @Test
    fun `a field that only starts with an underscore is kept`() {
        val result = SolrQueryResultReader.read(
            """{"responseHeader":{"status":0},"response":{"docs":[{"_leading":"kept","_version_":1}]}}""",
        )

        assertEquals(listOf("_leading"), result!!.columns)
        assertEquals(listOf("_version_"), result.hiddenColumns)
    }

    /** Documents with different fields contribute all of them, in first-seen order. */
    @Test
    fun `columns are the union across documents`() {
        val result = SolrQueryResultReader.read(
            """{"responseHeader":{"status":0},"response":{"docs":[{"a":1,"b":2},{"b":3,"c":4}]}}""",
        )

        assertEquals(listOf("a", "b", "c"), result!!.columns)
    }

    // --- the cells --------------------------------------------------------------------------------

    /**
     * A single value arriving as an array renders as the value.
     *
     * `text_general` is multiValued in Solr's own `_default` configset, so `"title": ["Dune"]` is
     * what the most ordinary query returns. A renderer assuming scalars would print `["Dune"]` in a
     * column of otherwise clean values.
     */
    @Test
    fun `a single-valued field arriving as an array renders unwrapped`() {
        assertEquals("Dune", resultOf("select-10.json").rows[0]["title"])
    }

    @Test
    fun `a genuinely multivalued field joins its values`() {
        assertEquals("scifi, classic", resultOf("select-10.json").rows[0]["tags_ss"])
    }

    @Test
    fun `a string renders without its quotes`() {
        assertEquals("Frank Herbert", resultOf("select-10.json").rows[0]["author_s"])
    }

    @Test
    fun `numbers render as themselves`() {
        assertEquals("9.99", resultOf("select-10.json").rows[0]["price_f"])
        assertEquals("1965", resultOf("select-10.json").rows[0]["year_i"])
    }

    /** A field one document lacks is blank there rather than absent from the row. */
    @Test
    fun `a missing field renders blank`() {
        val result = SolrQueryResultReader.read(
            """{"responseHeader":{"status":0},"response":{"docs":[{"a":1},{"b":2}]}}""",
        )

        assertEquals("", result!!.rows[0]["b"])
        assertEquals("", result.rows[1]["a"])
    }

    @Test
    fun `an explicit null renders blank rather than as the word null`() {
        val result = SolrQueryResultReader.read(
            """{"responseHeader":{"status":0},"response":{"docs":[{"a":null}]}}""",
        )

        assertEquals("", result!!.rows[0]["a"])
    }

    // --- the scoring explanation ------------------------------------------------------------------

    /**
     * The explanation is Solr's own text, passed through rather than reformatted.
     *
     * Solr returns it already indented, keyed by document id — verified on both supported lines.
     * Re-parsing that into a structure in order to re-render it as a tree would be strictly worse
     * than showing what Solr wrote.
     */
    @Test
    fun `explanations arrive keyed by document and keep solr's own indentation`() {
        val result = resultOf("select-debug-10.json")

        assertEquals(setOf("1", "2"), result.explanations.keys)
        val first = result.explanations.getValue("1")
        assertTrue(first, first.contains("weight(title:dune"))
        assertTrue("solr's own indentation must survive", first.lines().any { it.startsWith("  ") })
    }

    @Test
    fun `the parsed query is reported where debug asked for it`() {
        assertEquals("title:dune", resultOf("select-debug-10.json").parsedQuery)
    }

    /** A query without `debugQuery` has no explanations, and that is not a failure. */
    @Test
    fun `a query without debug carries no explanations`() {
        val result = resultOf("select-10.json")

        assertTrue(result.explanations.isEmpty())
        assertNull(result.parsedQuery)
    }

    /** A debug response still reports its documents — the table does not vanish because debug asked. */
    @Test
    fun `a debug response still carries its rows`() {
        assertEquals(2, resultOf("select-debug-10.json").rows.size)
    }

    // --- both supported lines ---------------------------------------------------------------------

    @Test
    fun `solr 9 reports the same shapes`() {
        val result = resultOf("select-debug-9.json")

        assertEquals(setOf("1", "2"), result.explanations.keys)
        assertEquals("title:dune", result.parsedQuery)
        assertEquals("Dune", result.rows[0]["title"])
        assertFalse(result.columns.contains("_version_"))
    }
}
