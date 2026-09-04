package org.apache.solr.ide.server.query

import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/**
 * What the summary above a response actually says.
 *
 * Extends the platform test case only for the message bundle; nothing here needs a project. The
 * assertions are about text a human reads, so they are written against the text.
 */
class SolrQueryResultRendererTest : SolrConfigsetTestCase() {

    private fun captured(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/server-responses/$name")) { "missing $name" }
            .bufferedReader().readText()

    private fun renderOf(name: String) =
        SolrQueryResultRenderer.render(checkNotNull(SolrQueryResultReader.read(captured(name))))

    private fun renderOfJson(json: String) =
        SolrQueryResultRenderer.render(checkNotNull(SolrQueryResultReader.read(json)))

    // --- the summary line -------------------------------------------------------------------------

    fun testTheSummarySaysHowManyMatchedAndHowLongItTook() {
        val rendered = renderOf("select-10.json")

        assertTrue(rendered, rendered.lines().first().contains("2 documents matched"))
        assertTrue(rendered, rendered.lines().first().contains("32 ms"))
    }

    /**
     * One match reads as one document, not as "1 documents".
     *
     * The count is the first thing the summary says and the first thing anyone reads, so it is the
     * one place a plural taken on faith is certain to be seen. A single hit is also the ordinary
     * case for a query on a unique key, rather than the edge the phrasing can afford to get wrong.
     */
    fun testASingleMatchIsNotPlural() {
        val rendered = renderOfJson(
            """{"responseHeader":{"status":0,"QTime":9},
                "response":{"numFound":1,"start":0,"docs":[{"id":"1"}]}}""",
        )

        assertTrue(rendered, rendered.lines().first().contains("1 document matched"))
        assertFalse(rendered, rendered.lines().first().contains("1 documents"))
    }

    /**
     * Matching and returning are stated as different numbers when they differ.
     *
     * The failure this prevents is a reader concluding their query found ten documents when it found
     * nine thousand and showed ten — which the raw JSON says plainly and a careless summary hides.
     */
    fun testAWindowOfALargerResultSaysSo() {
        val rendered = renderOfJson(
            """{"responseHeader":{"status":0,"QTime":5},
                "response":{"numFound":9000,"start":0,"docs":[{"id":"1"},{"id":"2"}]}}""",
        )

        // Separators stripped: the bundle formats 9000 as "9,000" and that grouping is
        // locale-dependent, which is a property of the message formatter rather than of this code.
        assertTrue(rendered, rendered.replace(",", "").contains("9000 documents matched"))
        assertTrue(rendered, rendered.contains("showing 1 to 2"))
    }

    fun testAWindowFurtherInReportsItsOffset() {
        val rendered = renderOfJson(
            """{"responseHeader":{"status":0,"QTime":5},
                "response":{"numFound":9000,"start":20,"docs":[{"id":"1"},{"id":"2"}]}}""",
        )

        assertTrue(rendered, rendered.contains("showing 21 to 22"))
    }

    fun testNoMatchesSaysSoRatherThanShowingAnEmptyTable() {
        val rendered = renderOfJson(
            """{"responseHeader":{"status":0,"QTime":1},"response":{"numFound":0,"start":0,"docs":[]}}""",
        )

        assertTrue(rendered, rendered.contains("No documents matched"))
        assertFalse("an empty table is noise", rendered.contains("---"))
    }

    // --- the table --------------------------------------------------------------------------------

    fun testTheTableCarriesAHeaderAndARowPerDocument() {
        val lines = renderOf("select-10.json").lines()
        val header = lines.indexOfFirst { it.startsWith("id") }

        assertTrue("expected a header row", header >= 0)
        // The rule is as wide as its column, and the first column here is `id` — two characters.
        assertTrue("expected a rule under it", lines[header + 1].startsWith("--"))
        assertTrue(lines[header + 2].startsWith("1 "))
        assertTrue(lines[header + 3].startsWith("2 "))
    }

    /** Columns line up, which is the whole reason to render a table rather than a list. */
    fun testColumnsAreAligned() {
        val lines = renderOfJson(
            """{"responseHeader":{"status":0},"response":{"numFound":2,"start":0,
                "docs":[{"id":"1","title":"Dune"},{"id":"22","title":"Neuromancer"}]}}""",
        ).lines()
        val rows = lines.filter { it.startsWith("1 ") || it.startsWith("22") }

        assertEquals(2, rows.size)
        assertEquals("the title column must start at one offset", rows[0].indexOf("Dune"), rows[1].indexOf("Neuro"))
    }

    fun testAMultivaluedCellIsJoined() {
        assertTrue(renderOf("select-10.json").contains("scifi, classic"))
    }

    /** A single value that arrived as an array is not printed with its brackets. */
    fun testASingleValuedArrayCellIsUnwrapped() {
        val rendered = renderOf("select-10.json")

        assertTrue(rendered, rendered.contains("Dune"))
        assertFalse(rendered, rendered.contains("[\"Dune\"]"))
    }

    /**
     * A long value is cut rather than wrapped.
     *
     * Wrapping destroys the alignment that makes the table worth having, and the full value is in
     * the JSON printed underneath — which is what makes cutting safe rather than lossy.
     */
    fun testALongCellIsCutAndMarked() {
        val long = "x".repeat(200)
        val rendered = renderOfJson(
            """{"responseHeader":{"status":0},"response":{"numFound":1,"start":0,"docs":[{"id":"1","note":"$long"}]}}""",
        )

        assertFalse("nothing may run to 200 characters", rendered.lines().any { it.length > 120 })
        assertTrue("the cut must be visible", rendered.contains("…"))
    }

    /** A newline inside a value must not break the row it sits in. */
    fun testACellWithANewlineStaysOnOneRow() {
        val rendered = renderOfJson(
            """{"responseHeader":{"status":0},"response":{"numFound":1,"start":0,
                "docs":[{"id":"1","note":"first\nsecond"}]}}""",
        )
        val header = rendered.lines().indexOfFirst { it.startsWith("id") }

        assertEquals("one document is one row", rendered.lines().size, header + 3)
    }

    /** The fields left out are named, so their absence is stated rather than noticed. */
    fun testHiddenInternalFieldsAreNamed() {
        val rendered = renderOf("select-10.json")

        assertTrue(rendered, rendered.contains("internal fields are not shown"))
        assertTrue(rendered, rendered.contains("_version_"))
    }

    // --- the scoring explanation ------------------------------------------------------------------

    /** Solr's own explanation text, under the document it belongs to. */
    fun testExplanationsAppearUnderTheirDocument() {
        val rendered = renderOf("select-debug-10.json")

        assertTrue(rendered, rendered.contains("Why each document scored"))
        assertTrue(rendered, rendered.contains("Document 1"))
        assertTrue(rendered, rendered.contains("weight(title:dune"))
    }

    /**
     * Solr's own indentation survives, because it is what makes the explanation a tree.
     *
     * The nesting is the information: which term contributed what, and how idf and tf combined into
     * it. Flattening it would leave a list of numbers with nothing saying which produced which.
     */
    fun testTheExplanationKeepsItsNesting() {
        val rendered = renderOf("select-debug-10.json")
        val explanation = rendered.lines().dropWhile { !it.contains("Document 1") }

        assertTrue("expected deeper-indented lines under the first", explanation.any { it.startsWith("      ") })
    }

    fun testTheParsedQueryIsShown() {
        assertTrue(renderOf("select-debug-10.json").contains("Solr read the query as: title:dune"))
    }

    /** A query with no debug prints no explanation section rather than an empty one. */
    fun testNoDebugMeansNoExplanationSection() {
        assertFalse(renderOf("select-10.json").contains("Why each document scored"))
    }

    fun testSolr9RendersTheSameWay() {
        val rendered = renderOf("select-debug-9.json")

        assertTrue(rendered, rendered.contains("Document 1"))
        assertTrue(rendered, rendered.contains("Solr read the query as: title:dune"))
    }
}
