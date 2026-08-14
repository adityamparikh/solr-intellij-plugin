package org.apache.solr.ide.model.vocabulary

import org.apache.solr.ide.model.SolrVersionSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `solrconfig.xml` element vocabulary, as the editor reads it.
 *
 * Two halves, and they fail differently. [parse] is asserted against rows the generator would never
 * write, because this is the one place a malformed resource reaches the editor and the guarantee
 * worth having is that a bad row costs a row rather than the file. The rest is asserted against the
 * shipped resource, because a reader that parses beautifully and disagrees with what was generated
 * is the failure that matters.
 */
class SolrElementCatalogTest {

    private val ten = SolrVersionSelection.DEFAULT

    // --- the shipped resource ---------------------------------------------------------------------

    /** The two elements that prompted the vocabulary question, and could not be hand-written. */
    fun assertPresent(name: String, parent: String = "") {
        assertNotNull(
            "$name should be in the shipped vocabulary",
            SolrElementCatalog.element(name, parent, ten),
        )
    }

    @Test
    fun `the elements the question was about are carried`() {
        assertPresent("luceneMatchVersion")
        assertPresent("dataDir")
        assertPresent("requestHandler")
    }

    /**
     * Element against attribute is the whole of what the resource says about kind, and the only part
     * a consumer reads — so it is asserted against the shipped file rather than a fixture.
     *
     * `maxDocs` is the one that matters. It sits under `updateHandler/autoCommit` exactly as
     * `luceneMatchVersion` sits under the root, and nothing about the name says which is written as a
     * tag and which inside one.
     */
    @Test
    fun `element against attribute comes through from the generator`() {
        assertFalse(SolrElementCatalog.element("luceneMatchVersion", "", ten)!!.isAttribute)
        assertFalse(SolrElementCatalog.element("lib", "", ten)!!.isAttribute)
        assertFalse(SolrElementCatalog.element("dataDir", "", ten)!!.isAttribute)
        assertTrue(SolrElementCatalog.element("maxDocs", "updateHandler/autoCommit", ten)!!.isAttribute)
    }

    /** Nesting is the fact the class catalog had nowhere to put, and the descriptor's whole input. */
    @Test
    fun `children are known by their parent`() {
        val underConfig = SolrElementCatalog.childrenOf("", ten).map { it.name }
        assertTrue("expected top-level elements, got $underConfig", "requestHandler" in underConfig)
        assertTrue("expected top-level elements, got $underConfig", "luceneMatchVersion" in underConfig)

        val underQuery = SolrElementCatalog.childrenOf("query", ten).map { it.name }
        assertTrue("expected caches under query, got $underQuery", "filterCache" in underQuery)
        assertFalse("a top-level element is not a child of query: $underQuery", "dataDir" in underQuery)
    }

    /**
     * A discontinued element is carried and marked, never silently dropped.
     *
     * Completion declines it; what the marker buys is that something can say *why* rather than
     * treating a name Solr explicitly retired as one it has never heard of.
     */
    @Test
    fun `a discontinued element is marked with Solr's own words`() {
        val nrtMode = SolrElementCatalog.element("nrtMode", "", ten)
        assertNotNull("nrtMode should be carried, not dropped", nrtMode)
        assertTrue("expected Solr's notice: ${nrtMode!!.discontinued}", "discontinued" in nrtMode.discontinued)
    }

    /** What a reader should be offered excludes what Solr has retired. */
    @Test
    fun `the offerable children exclude discontinued elements`() {
        val offerable = SolrElementCatalog.offerableChildrenOf("", ten).map { it.name }
        assertTrue("expected live elements, got $offerable", "requestHandler" in offerable)
        assertFalse("a discontinued element must not be offered: $offerable", "nrtMode" in offerable)
        assertFalse("nor this one: $offerable", "mainIndex" in offerable)
    }

    @Test
    fun `an element no resource names is not invented`() {
        assertNull(SolrElementCatalog.element("notAnElement", "", ten))
    }

    /** Both supported lines answer, so a 9 configset is not read against 10's vocabulary. */
    @Test
    fun `both shipped lines carry a vocabulary`() {
        for (version in listOf(SolrVersionSelection.fromLuceneMatchVersion("9.12.0"), ten)) {
            assertTrue(
                "expected a vocabulary for $version",
                SolrElementCatalog.childrenOf("", version).size > 20,
            )
        }
    }

    // --- the parser ------------------------------------------------------------------------------

    @Test
    fun `comments and blank lines are not rows`() {
        val parsed = SolrElementCatalog.parse(sequenceOf("# a comment", "", "  "))
        assertTrue(parsed.isEmpty())
    }

    /** A row too short to mean anything costs that row, not the file. */
    @Test
    fun `a short row is dropped and the rest survive`() {
        val parsed = SolrElementCatalog.parse(
            sequenceOf(
                "truncated\trow",
                "dataDir\t\tsingle\tconfig\t",
            ),
        )
        assertEquals(listOf("dataDir"), parsed.map { it.name })
    }

    /**
     * A kind a newer generator invented reads as an element rather than dropping the row.
     *
     * The row still says something exists and where it sits, which is most of what a consumer needs.
     * Element is the permissive fallback of the two: it reaches element completion, where an
     * unexpected name costs a suggestion, rather than being offered as an attribute inside a tag it
     * may not belong to.
     */
    @Test
    fun `an unknown kind reads as an element rather than dropping the row`() {
        val parsed = SolrElementCatalog.parse(sequenceOf("newThing\t\tsomethingNew\tconfig\t"))
        assertEquals(1, parsed.size)
        assertFalse(parsed.single().isAttribute)
    }

    /**
     * The kind column read both ways, which no other parser fixture does.
     *
     * Every other row here is an element, so the word that makes one an attribute was only ever
     * exercised through the shipped resource — where a parser that ignored the column entirely would
     * still have looked right for the elements and wrong only for the 32 attributes.
     */
    @Test
    fun `the kind column decides element against attribute`() {
        val parsed = SolrElementCatalog.parse(
            sequenceOf(
                "maxDocs\tupdateHandler/autoCommit\tattribute\teditable\t",
                "dataDir\t\telement\tconfig\t",
            ),
        )
        assertEquals(listOf(true, false), parsed.map { it.isAttribute })
    }

    /**
     * A row stopping before the discontinuation column is still an entry.
     *
     * The generator always writes five, so this is about a resource written by some other version —
     * the case the parser exists to survive rather than the case it usually sees.
     */
    @Test
    fun `a row without a discontinuation column is still current`() {
        val parsed = SolrElementCatalog.parse(sequenceOf("dataDir\t\telement\tconfig"))
        assertEquals(1, parsed.size)
        assertEquals("", parsed.single().discontinued)
        assertTrue(parsed.single().isCurrent)
    }

    @Test
    fun `a row with no name is not an element`() {
        assertTrue(SolrElementCatalog.parse(sequenceOf("\t\tsingle\tconfig\t")).isEmpty())
    }
}
