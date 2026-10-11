package org.apache.solr.ide.server.query.screen

import org.apache.solr.ide.server.transport.SolrRawAnswer
import org.apache.solr.ide.server.transport.SolrResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What an answer to the console amounts to.
 *
 * The four cases are what the results panel switches on, so each is pinned against the answer that
 * produces it — and every case that arrived keeps its raw body, which is the part the console shows
 * whatever it concluded.
 */
class SolrQueryScreenAnswerTest {

    private val oneDocument = """{"responseHeader":{"status":0,"QTime":3},"response":{"numFound":1,"start":0,"docs":[{"id":"1"}]}}"""

    private fun arrived(status: Int, body: String, type: String? = "application/json") =
        SolrResponse.Success(SolrRawAnswer(status, type, body))

    @Test
    fun `a json query result has a table`() {
        val answer = SolrQueryScreenAnswer.of(arrived(200, oneDocument)) as SolrQueryScreenAnswer.Table

        assertEquals(1L, answer.result.numFound)
        assertEquals(oneDocument, answer.raw.body)
    }

    @Test
    fun `a partial answer has a table and says so`() {
        val body = """{"responseHeader":{"status":0,"partialResults":true,"partialResultsDetails":"timeAllowed"},"response":{"numFound":1,"docs":[]}}"""

        val answer = SolrQueryScreenAnswer.of(arrived(200, body)) as SolrQueryScreenAnswer.Table

        assertTrue(answer.partial)
        assertEquals("timeAllowed", answer.partialDetail)
    }

    @Test
    fun `an xml answer is shown raw`() {
        val answer = SolrQueryScreenAnswer.of(arrived(200, "<response/>", "application/xml"))

        assertEquals(SolrQueryScreenAnswer.Raw(SolrRawAnswer(200, "application/xml", "<response/>")), answer)
    }

    @Test
    fun `json that is not a query result is shown raw`() {
        assertTrue(SolrQueryScreenAnswer.of(arrived(200, """{"responseHeader":{"status":0},"cluster":{}}""")) is SolrQueryScreenAnswer.Raw)
    }

    @Test
    fun `solr's refusal keeps its words and its page`() {
        val body = """{"responseHeader":{"status":400},"error":{"msg":"undefined field categry","code":400}}"""

        val answer = SolrQueryScreenAnswer.of(arrived(400, body)) as SolrQueryScreenAnswer.Refused

        assertEquals(400, answer.code)
        assertEquals("undefined field categry", answer.message)
        assertEquals(body, answer.raw.body)
    }

    @Test
    fun `an html 404 is a refusal with no message`() {
        val answer = SolrQueryScreenAnswer.of(arrived(404, "<html>Not Found</html>", "text/html")) as SolrQueryScreenAnswer.Refused

        assertEquals(404, answer.code)
        assertEquals(null, answer.message)
    }

    @Test
    fun `no answer is reported in the transport's words`() {
        assertEquals(
            SolrQueryScreenAnswer.NotSent("Connection refused"),
            SolrQueryScreenAnswer.of(SolrResponse.TransportFailure("Connection refused")),
        )
    }

    @Test
    fun `the highlighting follows the content type`() {
        assertEquals("json", SolrQueryScreenAnswer.extensionFor("application/json; charset=UTF-8"))
        assertEquals("xml", SolrQueryScreenAnswer.extensionFor("application/xml"))
        assertEquals("xml", SolrQueryScreenAnswer.extensionFor("text/xml;charset=utf-8"))
        assertEquals("html", SolrQueryScreenAnswer.extensionFor("text/html"))
        assertEquals("txt", SolrQueryScreenAnswer.extensionFor("text/csv"))
        assertEquals("txt", SolrQueryScreenAnswer.extensionFor(null))
    }
}
