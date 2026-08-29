package org.apache.solr.ide.server.query

import com.intellij.httpClient.execution.common.CommonClientResponseBody
import com.intellij.httpClient.http.request.run.console.HttpResponseCustomPresentation
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/**
 * What the presentation prints, and — more importantly — what it does not.
 *
 * **The extension point has no applicability test**, so this class is called for every response the
 * HTTP Client shows, including responses belonging to other plugins and to services that have
 * nothing to do with Solr. Declining is therefore the common case, and printing over somebody
 * else's response is the failure worth the most care. The same discipline the inspections follow:
 * write the clean fixtures first.
 */
class SolrQueryResponsePresentationTest : SolrConfigsetTestCase() {

    private fun summaryFor(body: CommonClientResponseBody): String? =
        SolrQueryResponsePresentation().summaryFor(body)

    private fun summaryForText(text: String): String? =
        summaryFor(CommonClientResponseBody.Text(text))

    private fun captured(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/server-responses/$name")) { "missing $name" }
            .bufferedReader().readText()

    // --- what it declines -------------------------------------------------------------------------

    /** Somebody else's JSON is left exactly as it was. */
    fun testAnUnrelatedJsonResponseIsLeftAlone() {
        assertNull(summaryForText("""{"items":[{"id":1}],"total":1}"""))
    }

    fun testAnHtmlResponseIsLeftAlone() {
        assertNull(summaryForText("<html><body>Not found</body></html>"))
    }

    fun testAnEmptyBodyIsLeftAlone() {
        assertNull(summaryFor(CommonClientResponseBody.Empty()))
    }

    /**
     * A Solr response that is not a *query* is left alone.
     *
     * A schema response is already readable JSON and there is no table to draw over it. Printing a
     * summary of nothing would be this plugin taking over a view it has nothing to add to.
     */
    fun testASolrSchemaResponseIsLeftAlone() {
        assertNull(summaryForText(captured("schema-10.json")))
    }

    fun testASolrLukeResponseIsLeftAlone() {
        assertNull(summaryForText(captured("luke-10.json")))
    }

    // --- what it prints ---------------------------------------------------------------------------

    fun testAQueryResponseIsSummarised() {
        val printed = checkNotNull(summaryForText(captured("select-10.json")))

        assertTrue(printed, printed.contains("2 documents matched"))
        assertTrue(printed, printed.contains("Dune"))
    }

    fun testAQueryWithDebugAlsoExplainsTheScores() {
        val printed = checkNotNull(summaryForText(captured("select-debug-10.json")))

        assertTrue(printed, printed.contains("Why each document scored"))
        assertTrue(printed, printed.contains("weight(title:dune"))
    }

    /** A query that matched nothing still says so, because that is an answer. */
    fun testAnEmptyResultIsStillReported() {
        val printed = checkNotNull(
            summaryForText("""{"responseHeader":{"status":0,"QTime":1},"response":{"numFound":0,"start":0,"docs":[]}}"""),
        )

        assertTrue(printed, printed.contains("No documents matched"))
    }

    // --- where it prints --------------------------------------------------------------------------

    /**
     * After the header, so the HTTP Client's own status line stays first.
     *
     * This adds to the response's account of itself rather than displacing it.
     */
    fun testItPrintsAfterTheResponseHeader() {
        assertEquals(
            HttpResponseCustomPresentation.Order.AFTER_HEADER,
            SolrQueryResponsePresentation().order,
        )
    }
}
