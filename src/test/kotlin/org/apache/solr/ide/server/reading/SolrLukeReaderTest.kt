package org.apache.solr.ide.server.reading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading what an index actually holds.
 *
 * **The fixtures are responses captured from running Solr, not responses somebody imagined.** Three
 * of the shapes asserted below were found by reading real output and would not have occurred to
 * anyone writing a fixture by hand: `index` carrying prose instead of flags, a point field reporting
 * no document count at all despite holding documents, and the flag legend arriving inside the
 * response. Both supported lines are checked, because a shape that holds on one and not the other is
 * the failure this plugin has already met once.
 *
 * The documents behind them: three books indexed into a `_default` collection, with `author_s`,
 * `price_f`, `year_i` and `tags_ss` chosen so that four different dynamic patterns match.
 */
class SolrLukeReaderTest {

    private fun capturedResponse(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/server-responses/$name")) { "missing fixture $name" }
            .bufferedReader().readText()

    private fun contentsOf(name: String) = SolrLukeReader.read(capturedResponse(name))

    private fun fieldNamed(name: String, fixture: String = LINE_10) =
        contentsOf(fixture).fields.single { it.name == name }

    // --- the fact that makes this view worth having -----------------------------------------------

    /**
     * A field that exists only because a dynamic pattern matched says which pattern.
     *
     * The whole reason this is a third view rather than another half of the model: no configset can
     * report `author_s`, because the configset declares `*_s` and the index holds what matched it.
     */
    @Test
    fun `a dynamic instance names the pattern that created it`() {
        val field = fieldNamed("author_s")

        assertEquals("*_s", field.dynamicBase)
        assertTrue(field.isDynamicInstance)
    }

    @Test
    fun `a declared field names no pattern`() {
        val field = fieldNamed("id")

        assertNull(field.dynamicBase)
        assertFalse(field.isDynamicInstance)
    }

    /** Four different patterns matched, and each instance reports its own. */
    @Test
    fun `every dynamic instance is attributed to its own pattern`() {
        val bases = contentsOf(LINE_10).fields.filter { it.isDynamicInstance }.associate { it.name to it.dynamicBase }

        assertEquals(
            mapOf("author_s" to "*_s", "price_f" to "*_f", "year_i" to "*_i", "tags_ss" to "*_ss", "title_str" to "*_str"),
            bases,
        )
    }

    // --- what the flags decode to -----------------------------------------------------------------

    /**
     * Flags are decoded through the legend the response itself carries.
     *
     * A table written into the plugin would be a second list that has to agree with Solr's, which is
     * the shape of mistake this codebase has already paid for twice.
     */
    @Test
    fun `schema flags decode to the meanings solr gives them`() {
        val id = fieldNamed("id")

        assertTrue(id.schemaProperties.toString(), id.schemaProperties.contains("Indexed"))
        assertTrue(id.schemaProperties.toString(), id.schemaProperties.contains("Stored"))
        assertTrue(id.schemaProperties.toString(), id.schemaProperties.contains("DocValues"))
        assertFalse("id is a string field and is not tokenized", id.schemaProperties.contains("Tokenized"))
    }

    @Test
    fun `a tokenized field says so and a string field does not`() {
        assertTrue(fieldNamed("title").schemaProperties.contains("Tokenized"))
        assertFalse(fieldNamed("author_s").schemaProperties.contains("Tokenized"))
    }

    @Test
    fun `a multivalued field says so`() {
        assertTrue(fieldNamed("tags_ss").schemaProperties.contains("Multivalued"))
    }

    /** The `-` positions are absent flags and decode to nothing rather than to an empty name. */
    @Test
    fun `absent flags produce no property`() {
        assertFalse(fieldNamed("id").schemaProperties.any { it.isBlank() })
    }

    // --- the shapes a hand-written fixture would have missed ---------------------------------------

    /**
     * `index` is not always a flag string, and prose there is carried across as prose.
     *
     * `_root_` reports `"index": "(unstored field)"` on both supported lines. Decoded as flags, its
     * punctuation would manufacture properties out of nothing — which is worse than reporting none,
     * because it would be confidently wrong about a field the user did not create.
     */
    @Test
    fun `a field whose index is prose keeps the prose and invents no flags`() {
        val root = fieldNamed("_root_")

        assertEquals("(unstored field)", root.indexNote)
        assertTrue(root.indexProperties.toString(), root.indexProperties.isEmpty())
    }

    /**
     * A point field reports no document count, and that is not a count of zero.
     *
     * `price_f` carries three documents and Solr reports no `docs` for it at all, having no inverted
     * index to count from. Showing "0 documents" would state something false about exactly the field
     * types Solr recommends people use.
     */
    @Test
    fun `a point field reports no document count rather than zero`() {
        val price = fieldNamed("price_f")

        assertNull("null means Solr did not say, which is not the same as none", price.docs)
        assertTrue(price.indexProperties.isEmpty())
        assertNull(price.indexNote)
    }

    @Test
    fun `an inverted field reports how many documents carry it`() {
        assertEquals(3, fieldNamed("id").docs)
        assertEquals(3, fieldNamed("author_s").docs)
    }

    // --- the index in the large -------------------------------------------------------------------

    @Test
    fun `the summary reports what the index contains`() {
        val summary = contentsOf(LINE_10).summary

        assertEquals(3, summary.numDocs)
        assertEquals(3, summary.maxDoc)
        assertEquals(0, summary.deletedDocs)
        assertEquals(1, summary.segmentCount)
        assertEquals(true, summary.current)
    }

    // --- both supported lines ---------------------------------------------------------------------

    /**
     * The shapes hold on Solr 9 as well as Solr 10.
     *
     * Including the two quirks: a shape present on one line and absent on the other is precisely the
     * failure the two-line fixture set exists to catch.
     */
    @Test
    fun `solr 9 reports the same shapes`() {
        val contents = contentsOf(LINE_9)

        assertEquals(
            contentsOf(LINE_10).fields.map { it.name }.toSet(),
            contents.fields.map { it.name }.toSet(),
        )
        assertEquals("*_s", contents.fields.single { it.name == "author_s" }.dynamicBase)
        assertEquals("(unstored field)", contents.fields.single { it.name == "_root_" }.indexNote)
        assertNull(contents.fields.single { it.name == "price_f" }.docs)
        assertTrue(contents.fields.single { it.name == "id" }.schemaProperties.contains("Indexed"))
    }

    /** The fixtures are read at all — an unreadable one would otherwise pass every test above. */
    @Test
    fun `the captured responses carry fields`() {
        assertEquals(9, contentsOf(LINE_10).fields.size)
        assertEquals(9, contentsOf(LINE_9).fields.size)
    }

    // --- what is not a Luke response --------------------------------------------------------------

    @Test
    fun `text that is not json yields empty contents`() {
        assertTrue(SolrLukeReader.read("<html>404</html>").fields.isEmpty())
    }

    @Test
    fun `json without fields yields empty contents rather than failing`() {
        val contents = SolrLukeReader.read("""{"responseHeader":{"status":0}}""")

        assertTrue(contents.fields.isEmpty())
        assertNull(contents.summary.numDocs)
    }

    /** A response carrying no legend decodes no flags rather than guessing at their meaning. */
    @Test
    fun `flags with no legend decode to nothing`() {
        val contents = SolrLukeReader.read("""{"fields":{"id":{"type":"string","schema":"ITS-"}}}""")

        val id = contents.fields.single()
        assertEquals("string", id.type)
        assertTrue(id.schemaProperties.toString(), id.schemaProperties.isEmpty())
    }

    /** A letter the legend does not name is dropped, not rendered as itself. */
    @Test
    fun `an unknown flag letter is dropped`() {
        val contents = SolrLukeReader.read(
            """{"info":{"key":{"I":"Indexed"}},"fields":{"id":{"type":"string","schema":"IZ"}}}""",
        )

        assertEquals(listOf("Indexed"), contents.fields.single().schemaProperties)
    }

    @Test
    fun `a field list survives a missing type`() {
        val contents = SolrLukeReader.read("""{"fields":{"mystery":{}}}""")

        assertNotNull(contents.fields.single())
        assertEquals("", contents.fields.single().type)
    }

    private companion object {
        const val LINE_10 = "luke-10.json"
        const val LINE_9 = "luke-9.json"
    }
}
