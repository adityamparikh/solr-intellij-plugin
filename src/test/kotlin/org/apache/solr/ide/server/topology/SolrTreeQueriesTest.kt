package org.apache.solr.ide.server.topology

import org.apache.solr.ide.server.connection.SolrConnection
import org.apache.solr.ide.server.reading.SolrIndexField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which queries a row of the collections tree offers, and the request each one opens.
 *
 * The flag words are the ones Solr's own Luke legend uses, as captured from a Solr 10 collection:
 * a `string` field reads `Indexed, Stored, DocValues, UnInvertible, …`, a `text_general` one
 * `Indexed, Tokenized, Stored, UnInvertible`.
 */
class SolrTreeQueriesTest {

    private val local = SolrConnection(id = "a", displayName = "Local Solr", baseUrl = "http://localhost:8983/solr")

    private fun collectionRow(name: String = "products") =
        SolrTopologyNode(label = name, kind = SolrTopologyNodeKind.COLLECTION)

    private fun fieldRow(name: String, vararg flags: String) = SolrTopologyNode(
        label = name,
        kind = SolrTopologyNodeKind.FIELD,
        field = SolrIndexField(name = name, type = "string", schemaProperties = flags.toList()),
    )

    private val stringFlags = arrayOf("Indexed", "Stored", "DocValues", "UnInvertible", "Omit Norms")
    private val textFlags = arrayOf("Indexed", "Tokenized", "Stored", "UnInvertible")

    // --- what a row offers -------------------------------------------------------------------------

    @Test
    fun `a collection offers a query and an explained query`() {
        val offered = SolrTreeQueries.offeredFor(collectionRow(), "products")

        assertEquals(
            listOf(SolrTreeQuery(SolrTreeQueryKind.QUERY, "products"), SolrTreeQuery(SolrTreeQueryKind.EXPLAIN, "products")),
            offered,
        )
    }

    @Test
    fun `a standalone core offers the same as a collection`() {
        val core = SolrTopologyNode(label = "books", kind = SolrTopologyNodeKind.CORE)

        assertEquals(listOf(SolrTreeQueryKind.QUERY, SolrTreeQueryKind.EXPLAIN), SolrTreeQueries.offeredFor(core, "books").map { it.kind })
    }

    @Test
    fun `a field with doc values offers both finding and counting`() {
        val offered = SolrTreeQueries.offeredFor(fieldRow("category", *stringFlags), "products")

        assertEquals(
            listOf(
                SolrTreeQuery(SolrTreeQueryKind.FIND_WITH_FIELD, "products", "category"),
                SolrTreeQuery(SolrTreeQueryKind.COUNT_VALUES, "products", "category"),
            ),
            offered,
        )
    }

    /** Counting a tokenized field's values would count its words, which is not what the item says. */
    @Test
    fun `a tokenized field offers finding and not counting`() {
        val offered = SolrTreeQueries.offeredFor(fieldRow("description", *textFlags), "products")

        assertEquals(listOf(SolrTreeQueryKind.FIND_WITH_FIELD), offered.map { it.kind })
    }

    /** An indexed string field in an older schema without doc values can still be counted by uninverting. */
    @Test
    fun `an uninvertible untokenized field without doc values can be counted`() {
        val offered = SolrTreeQueries.offeredFor(fieldRow("sku", "Indexed", "Stored", "UnInvertible"), "products")

        assertTrue(SolrTreeQueryKind.COUNT_VALUES in offered.map { it.kind })
    }

    /** A stored-only field can be neither searched nor counted, so offering either would open a request that fails. */
    @Test
    fun `a field that is only stored offers nothing`() {
        assertEquals(emptyList<SolrTreeQuery>(), SolrTreeQueries.offeredFor(fieldRow("blob", "Stored"), "products"))
    }

    @Test
    fun `a row outside any collection offers nothing`() {
        assertEquals(emptyList<SolrTreeQuery>(), SolrTreeQueries.offeredFor(fieldRow("category", *stringFlags), null))
    }

    @Test
    fun `shards, replicas, headings and the fields row offer nothing`() {
        listOf(
            SolrTopologyNodeKind.SHARD,
            SolrTopologyNodeKind.REPLICA,
            SolrTopologyNodeKind.GROUP,
            SolrTopologyNodeKind.FIELDS,
            SolrTopologyNodeKind.NODE,
        ).forEach { kind ->
            val row = SolrTopologyNode(label = "x", kind = kind)
            assertEquals(kind.name, emptyList<SolrTreeQuery>(), SolrTreeQueries.offeredFor(row, "products"))
        }
    }

    // --- the request each one opens ----------------------------------------------------------------

    @Test
    fun `a query addresses the collection on the connection's own server`() {
        val text = SolrTreeQueries.requestText(SolrTreeQuery(SolrTreeQueryKind.QUERY, "products"), local)

        assertEquals(
            """
            ### Query products on Local Solr
            GET http://localhost:8983/solr/products/select?q=*:*&rows=10
            Accept: application/json
            """.trimIndent() + "\n",
            text,
        )
    }

    @Test
    fun `an explained query asks for the scoring explanation`() {
        val text = SolrTreeQueries.requestText(SolrTreeQuery(SolrTreeQueryKind.EXPLAIN, "products"), local)

        assertTrue(text, text.contains("GET http://localhost:8983/solr/products/select?q=*:*&rows=5&debugQuery=true\n"))
    }

    @Test
    fun `finding documents with a field returns the field beside the id`() {
        val text = SolrTreeQueries.requestText(
            SolrTreeQuery(SolrTreeQueryKind.FIND_WITH_FIELD, "products", "category"),
            local,
        )

        assertTrue(text, text.startsWith("### Find documents with category in products on Local Solr\n"))
        assertTrue(text, text.contains("GET http://localhost:8983/solr/products/select?q=category:*&fl=id,category&rows=10\n"))
    }

    @Test
    fun `counting a field's values facets on it and returns no documents`() {
        val text = SolrTreeQueries.requestText(
            SolrTreeQuery(SolrTreeQueryKind.COUNT_VALUES, "products", "category"),
            local,
        )

        assertTrue(
            text,
            text.contains("GET http://localhost:8983/solr/products/select?q=*:*&rows=0&facet=true&facet.field=category&facet.limit=20\n"),
        )
    }

    /** A connection saved with a trailing slash must not produce `solr//products`. */
    @Test
    fun `a trailing slash on the server address is not doubled`() {
        val slashed = local.copy(baseUrl = "http://localhost:8983/solr/")

        val text = SolrTreeQueries.requestText(SolrTreeQuery(SolrTreeQueryKind.QUERY, "products"), slashed)

        assertTrue(text, text.contains("GET http://localhost:8983/solr/products/select?"))
    }

    /** Names are escaped where the request line needs it, and left readable where it does not. */
    @Test
    fun `names that the request line cannot carry are escaped`() {
        val text = SolrTreeQueries.requestText(
            SolrTreeQuery(SolrTreeQueryKind.FIND_WITH_FIELD, "my products", "a&b"),
            local,
        )

        assertTrue(text, text.contains("/solr/my%20products/select?q=a%26b:*&fl=id,a%26b&rows=10\n"))
    }

    // --- credentials -------------------------------------------------------------------------------

    /**
     * A scratch file is a file on disk, so a password never goes into one; the request says what is
     * missing instead. The password cannot leak here by construction: nothing passed in holds it.
     */
    @Test
    fun `a connection that signs in gets a note and no header`() {
        val secured = local.copy(username = "solr")

        val text = SolrTreeQueries.requestText(SolrTreeQuery(SolrTreeQueryKind.QUERY, "products"), secured)

        assertTrue(text, text.contains("# This connection signs in as solr. Before running, add this header under the GET line:\n"))
        assertTrue(text, text.contains("# Authorization: Basic solr <password>\n"))
        assertFalse(text, text.lines().any { it.startsWith("Authorization") })
    }

    @Test
    fun `a blank username is no sign-in`() {
        val text = SolrTreeQueries.requestText(SolrTreeQuery(SolrTreeQueryKind.QUERY, "products"), local.copy(username = " "))

        assertFalse(text, text.lines().any { it.startsWith("# ") })
    }

    @Test
    fun `a connection that does not sign in gets no note`() {
        val text = SolrTreeQueries.requestText(SolrTreeQuery(SolrTreeQueryKind.QUERY, "products"), local)

        assertFalse(text, text.lines().any { it.startsWith("# ") })
    }

    // --- the scratch file's name -------------------------------------------------------------------

    @Test
    fun `the scratch file is named for the collection`() {
        assertEquals("solr-products.http", SolrTreeQueries.scratchFileName(SolrTreeQuery(SolrTreeQueryKind.QUERY, "products")))
    }
}
