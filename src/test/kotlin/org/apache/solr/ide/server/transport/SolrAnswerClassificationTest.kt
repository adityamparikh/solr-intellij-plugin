package org.apache.solr.ide.server.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning an answer into an outcome, without a server.
 *
 * The same rules [SolrHttpTransportTest] exercises over the wire, reachable directly because the
 * query console holds the raw answer and classifies it itself: it shows what Solr sent *and* says
 * what that meant.
 */
class SolrAnswerClassificationTest {

    private fun answer(status: Int, body: String, contentType: String? = "application/json") =
        SolrRawAnswer(status, contentType, body)

    @Test
    fun `a healthy answer is a success carrying the parsed body`() {
        val result = SolrHttpTransport.classify(answer(200, """{"responseHeader":{"status":0},"response":{"numFound":3}}"""))

        assertTrue(result.toString(), result is SolrResponse.Success)
        assertEquals(3, (result as SolrResponse.Success).value.path("response").path("numFound").asInt())
    }

    @Test
    fun `a Solr error keeps Solr's message and code`() {
        val result = SolrHttpTransport.classify(
            answer(400, """{"responseHeader":{"status":400},"error":{"msg":"undefined field categry","code":400}}"""),
        )

        assertEquals(SolrResponse.SolrError(400, "undefined field categry"), result)
    }

    /** The servlet's page for a mistyped collection: the status says it failed, and no message is invented. */
    @Test
    fun `an html 404 classifies as an error without a message`() {
        val result = SolrHttpTransport.classify(answer(404, "<html><body>Not Found</body></html>", "text/html"))

        assertTrue(result.toString(), result is SolrResponse.SolrError)
        assertEquals(404, (result as SolrResponse.SolrError).code)
        assertNull(result.message)
    }

    @Test
    fun `a 200 that is not json is unrecognized`() {
        val result = SolrHttpTransport.classify(answer(200, "<response><lst/></response>", "application/xml"))

        assertTrue(result.toString(), result is SolrResponse.Unrecognized)
    }

    @Test
    fun `a non-zero status inside a 200 is an error`() {
        val result = SolrHttpTransport.classify(answer(200, """{"responseHeader":{"status":500},"error":{"msg":"boom"}}"""))

        assertEquals(SolrResponse.SolrError(500, "boom"), result)
    }

    @Test
    fun `partial results are partial, with Solr's detail`() {
        val result = SolrHttpTransport.classify(
            answer(200, """{"responseHeader":{"status":0,"partialResults":true,"partialResultsDetails":"Limits exceeded!"}}"""),
        )

        assertTrue(result.toString(), result is SolrResponse.Partial)
        assertEquals("Limits exceeded!", (result as SolrResponse.Partial).detail)
    }
}
