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
     * Arity is the fact no other source could supply, so it is the one most worth asserting against
     * the shipped file rather than a fixture.
     */
    @Test
    fun `arity comes through from the generator`() {
        assertEquals(SolrElementArity.REQUIRED, SolrElementCatalog.element("luceneMatchVersion", "", ten)!!.arity)
        assertEquals(SolrElementArity.REPEATED, SolrElementCatalog.element("lib", "", ten)!!.arity)
        assertEquals(SolrElementArity.SINGLE, SolrElementCatalog.element("dataDir", "", ten)!!.arity)
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
                "dataDir\t\tsingle\tconfig\t\t",
            ),
        )
        assertEquals(listOf("dataDir"), parsed.map { it.name })
    }

    /**
     * An arity a newer generator invented reads as the ordinary one rather than dropping the element.
     *
     * The row still says an element exists and where it sits, which is most of what a consumer needs;
     * refusing it over one unrecognised column would lose the element entirely.
     */
    @Test
    fun `an unknown arity falls back rather than dropping the element`() {
        val parsed = SolrElementCatalog.parse(sequenceOf("newThing\t\tsomethingNew\tconfig\t\t"))
        assertEquals(1, parsed.size)
        assertEquals(SolrElementArity.SINGLE, parsed.single().arity)
    }

    @Test
    fun `a row with no name is not an element`() {
        assertTrue(SolrElementCatalog.parse(sequenceOf("\t\tsingle\tconfig\t\t")).isEmpty())
    }
}
