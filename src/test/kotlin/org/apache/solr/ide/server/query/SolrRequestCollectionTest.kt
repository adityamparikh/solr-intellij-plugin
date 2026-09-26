package org.apache.solr.ide.server.query

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which collection a request in an `.http` file is querying.
 *
 * **The collection is the segment before the handler**, because a query body is only ever sent to a
 * query handler, and a query handler is one segment — `select`, `query`, or a name the configset
 * gave one. What precedes it is the collection, whatever sits in front of that: a `/solr` context, a
 * reverse proxy's prefix, or the `{{solrUrl}}` the shipped templates write.
 */
class SolrRequestCollectionTest {

    private val none: (String) -> String? = { null }

    @Test
    fun `a collection written out is read from before the handler`() {
        assertEquals("books", SolrRequestCollection.of("http://localhost:8983/solr/books/query", none))
    }

    @Test
    fun `the query string plays no part`() {
        assertEquals("books", SolrRequestCollection.of("http://localhost:8983/solr/books/select?q=*:*&rows=5", none))
    }

    @Test
    fun `a proxy prefix in front of the collection changes nothing`() {
        assertEquals("books", SolrRequestCollection.of("https://search.example.com/api/solr/books/query", none))
    }

    @Test
    fun `the shipped template's variable is resolved`() {
        val collection = SolrRequestCollection.of("{{solrUrl}}/{{collection}}/query") {
            if (it == "collection") "books" else null
        }

        assertEquals("books", collection)
    }

    @Test
    fun `whitespace inside the braces is allowed, as the HTTP Client allows it`() {
        val collection = SolrRequestCollection.of("{{solrUrl}}/{{ collection }}/query") {
            if (it == "collection") "books" else null
        }

        assertEquals("books", collection)
    }

    /** A base URL held in a variable is the base, and never mistaken for the collection. */
    @Test
    fun `a literal collection after a variable base is read directly`() {
        assertEquals("books", SolrRequestCollection.of("{{solrUrl}}/books/query", none))
    }

    @Test
    fun `a variable nothing defines is no collection`() {
        assertNull(SolrRequestCollection.of("{{solrUrl}}/{{collection}}/query", none))
    }

    /** Half a variable is no better than none: the name cannot be known from the part written. */
    @Test
    fun `a segment only partly a variable is no collection`() {
        assertNull(SolrRequestCollection.of("{{solrUrl}}/{{prefix}}books/query") { "x" })
    }

    /** A value that is itself a reference would need a second resolution this does not attempt. */
    @Test
    fun `a variable whose value is another variable is no collection`() {
        assertNull(SolrRequestCollection.of("{{solrUrl}}/{{collection}}/query") { "{{other}}" })
    }

    @Test
    fun `a request with no segment before the handler names no collection`() {
        assertNull(SolrRequestCollection.of("http://localhost:8983/query", none))
        assertNull(SolrRequestCollection.of("{{solrUrl}}/query", none))
    }
}
