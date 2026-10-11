package org.apache.solr.ide.server.query.screen

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import org.apache.solr.ide.server.transport.SolrHttpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The request a filled-in form makes.
 *
 * The three readers of a request each get a different text — the server a form body, the person a
 * GET URL, the repository an `.http` file — and what is pinned is that each says the same query and
 * that none of them carries what it must not: a password, a host into a shared file, or an `&` that
 * splits a value in two.
 */
class SolrQueryScreenRequestTest {

    private val blank = SolrQueryScreenState(SolrQueryScreenSpec.bundled)

    private fun ready(state: SolrQueryScreenState = blank, collection: String = "books") =
        (SolrQueryScreenRequest.of(state, collection) as SolrQueryScreenRequest.Built.Ready).request

    @Test
    fun `the path is the collection then the handler`() {
        assertEquals("/books/select", ready().path)
        assertEquals("/books/query", ready(blank.with("qt", "/query")).path)
    }

    @Test
    fun `a collection name is encoded as a path segment`() {
        assertEquals("/my%20books/select", ready(collection = "my books").path)
    }

    @Test
    fun `a bare qt is refused rather than sent to select`() {
        val built = SolrQueryScreenRequest.of(blank.with("qt", "dismax"), "books")

        assertEquals(SolrQueryScreenRequest.Built.Refused(SolrQueryScreenRequest.Reason.HANDLER_NOT_A_PATH, "dismax"), built)
    }

    @Test
    fun `a handler carrying a query string is refused`() {
        assertTrue(SolrQueryScreenRequest.of(blank.with("qt", "/select?qt=x"), "books") is SolrQueryScreenRequest.Built.Refused)
    }

    @Test
    fun `a raw line with no name is refused naming it`() {
        val built = SolrQueryScreenRequest.of(blank.withLines("raw", listOf("oops")), "books")

        assertEquals(SolrQueryScreenRequest.Built.Refused(SolrQueryScreenRequest.Reason.RAW_WITHOUT_NAME, "oops"), built)
    }

    @Test
    fun `echoParams is sent last and not shown`() {
        val request = ready()

        assertEquals("echoParams" to "all", request.sentParameters.last())
        assertFalse(request.shownUrl("http://localhost:8983/solr").contains("echoParams"))
    }

    @Test
    fun `values with url syntax survive the form body exactly`() {
        val query = "title:\"dune & sons\" +author:herbert #1 100% é"
        val body = SolrHttpTransport.formEncoded(ready(blank.with("q", query)).sentParameters)

        val decoded = body.split("&").map { pair ->
            val (name, value) = pair.split("=", limit = 2)
            URLDecoder.decode(name, StandardCharsets.UTF_8) to URLDecoder.decode(value, StandardCharsets.UTF_8)
        }
        assertTrue(decoded.toString(), "q" to query in decoded)
    }

    @Test
    fun `the shown url hides credentials in the address`() {
        val shown = ready().shownUrl("https://admin:secret@solr.example:8983/solr/")

        assertEquals("https://solr.example:8983/solr/books/select?q=*%3A*&indent=true", shown)
    }

    @Test
    fun `the http request names variables, never a host or a credential`() {
        val text = ready(blank.with("q", "a&b")).httpRequestText()

        assertTrue(text, text.contains("POST {{solrUrl}}/{{collection}}/select"))
        assertTrue(text, text.contains("Content-Type: application/x-www-form-urlencoded"))
        assertTrue(text, text.contains("q=a%26b"))
        assertFalse(text, text.contains("Authorization"))
        assertFalse(text, text.contains("localhost"))
    }
}
