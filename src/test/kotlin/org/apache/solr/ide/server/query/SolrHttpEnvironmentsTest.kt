package org.apache.solr.ide.server.query

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The HTTP Client's environment files, read for the one question completion asks of them.
 *
 * **Which environment is selected is not something this plugin can ask.** The HTTP Client records it
 * in a class it does not publish, and the specification holds this plugin to the extension points it
 * does. So a variable is read across every environment, and answered only where they agree.
 */
class SolrHttpEnvironmentsTest {

    private val public = """
        {
          "local":   {"solrUrl": "http://localhost:8983/solr", "collection": "books"},
          "staging": {"solrUrl": "http://solr-staging:8983/solr", "collection": "books"}
        }
    """.trimIndent()

    @Test
    fun `each environment is read with its variables`() {
        val environments = SolrHttpEnvironments.parse(listOf(public))

        assertEquals(2, environments.size)
        assertEquals("books", environments.first()["collection"])
    }

    /** The private file is the per-user half of the same environment, and wins where both say. */
    @Test
    fun `a private file overrides the same environment's public value`() {
        val private = """{"local": {"collection": "films"}}"""

        val local = SolrHttpEnvironments.parse(listOf(public, private))
            .first { it["solrUrl"] == "http://localhost:8983/solr" }

        assertEquals("films", local["collection"])
    }

    @Test
    fun `a shared value reaches every environment that does not set its own`() {
        val environments = SolrHttpEnvironments.parse(
            listOf("""{"${'$'}shared": {"collection": "books"}, "local": {}, "staging": {"collection": "films"}}"""),
        )

        assertEquals(listOf("books", "films"), environments.mapNotNull { it["collection"] }.sorted())
    }

    /** An auth block is an object; only a string can be a collection. */
    @Test
    fun `values that are not strings are not variables for this purpose`() {
        val environments = SolrHttpEnvironments.parse(listOf("""{"local": {"collection": {"x": 1}, "rows": 5}}"""))

        assertNull(environments.single()["collection"])
        assertNull(environments.single()["rows"])
    }

    /** A file half-written in the editor must not take completion down with it. */
    @Test
    fun `a file that is not JSON is read as holding nothing`() {
        assertEquals(emptyList<Map<String, String>>(), SolrHttpEnvironments.parse(listOf("""{"local": """)))
    }

    // --- which value a variable has, when the environment cannot be known -------------------------

    @Test
    fun `every environment agreeing gives the value`() {
        val environments = SolrHttpEnvironments.parse(listOf(public))

        assertEquals("books", SolrHttpEnvironments.agreedValue("collection", environments))
    }

    /** Two answers and no way to know which is selected: offering either would be a guess. */
    @Test
    fun `environments that disagree give no value`() {
        val environments = SolrHttpEnvironments.parse(
            listOf("""{"local": {"collection": "books"}, "staging": {"collection": "films"}}"""),
        )

        assertNull(SolrHttpEnvironments.agreedValue("collection", environments))
    }

    /** An environment that says nothing about a variable does not disagree with one that does. */
    @Test
    fun `an environment silent on the variable does not count against it`() {
        val environments = SolrHttpEnvironments.parse(
            listOf("""{"local": {"collection": "books"}, "other": {"solrUrl": "http://x/solr"}}"""),
        )

        assertEquals("books", SolrHttpEnvironments.agreedValue("collection", environments))
    }

    @Test
    fun `a variable no environment defines has no value`() {
        assertNull(SolrHttpEnvironments.agreedValue("collection", SolrHttpEnvironments.parse(listOf(public)).map { it - "collection" }))
    }
}
