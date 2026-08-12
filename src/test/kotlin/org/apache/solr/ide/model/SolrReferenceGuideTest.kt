package org.apache.solr.ide.model

import org.apache.solr.ide.model.vocabulary.SolrClassKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reference Guide link construction.
 *
 * A dead link is worse than no link — a user who follows one into a 404 stops trusting the next —
 * so these pin the URL shapes that were verified against the live guide, and pin that nothing is
 * invented for inputs the plugin cannot place.
 */
class SolrReferenceGuideTest {

    @Test
    fun `with no declared version the latest guide is used`() {
        val url = SolrReferenceGuide.fieldTypesPage(SolrVersionSelection.DEFAULT)
        assertEquals(
            "https://solr.apache.org/guide/solr/latest/indexing-guide/field-types-included-with-solr.html",
            url,
        )
    }

    /**
     * `luceneMatchVersion` names a *Lucene* version, not a Solr one, but the majors are aligned
     * closely enough to pick a guide line — Solr 10 pairs with Lucene 10, Solr 9 with Lucene 9.
     */
    @Test
    fun `a declared version selects that line's guide`() {
        val selection = SolrVersionSelection.fromLuceneMatchVersion("10.0.0")
        assertEquals(SolrVersionSource.CONFIGSET, selection.source)
        assertTrue(SolrReferenceGuide.fieldPropertiesPage(selection).contains("/guide/solr/10_0/"))

        assertTrue(
            SolrReferenceGuide.fieldTypesPage(SolrVersionSelection.fromLuceneMatchVersion("9.12.0"))
                .contains("/guide/solr/9_0/"),
        )
    }

    /**
     * Lines the plugin does not support get the default rather than a link into documentation for a
     * Solr this plugin declines to support — and whose guide lives at a different path shape anyway.
     */
    @Test
    fun `an unsupported or unparseable version falls back to the default`() {
        for (declared in listOf("8.11.0", "7.7.0", "not-a-version", "")) {
            assertEquals(
                "expected $declared to fall back",
                SolrVersionSource.DEFAULT,
                SolrVersionSelection.fromLuceneMatchVersion(declared).source,
            )
        }
    }

    @Test
    fun `analyzer components link to the page for their kind`() {
        val version = SolrVersionSelection.DEFAULT
        assertTrue(
            SolrReferenceGuide.analyzerComponentPage("solr.StandardTokenizerFactory", version)!!.endsWith("tokenizers.html"),
        )
        assertTrue(
            SolrReferenceGuide.analyzerComponentPage("solr.LowerCaseFilterFactory", version)!!.endsWith("filters.html"),
        )
        // `charfilterfactories`, unhyphenated, is the odd one out among the three and is what the
        // guide actually serves — `char-filter-factories.html` is a 404. The name is inherited from
        // the pre-Antora wiki page, so it does not follow its neighbours and cannot be derived.
        assertTrue(
            SolrReferenceGuide.analyzerComponentPage("solr.HTMLStripCharFilterFactory", version)!!
                .endsWith("charfilterfactories.html"),
        )
    }

    /** Something that is not a recognised kind of component gets no link rather than a guessed one. */
    @Test
    fun `an unrecognised class yields no link`() {
        assertNull(SolrReferenceGuide.analyzerComponentPage("com.example.Whatever", SolrVersionSelection.DEFAULT))
        assertNull(SolrReferenceGuide.analyzerComponentPage("solr.StrField", SolrVersionSelection.DEFAULT))
    }

    /** The source is shown beside the link so a user can tell a fact from a fallback. */
    @Test
    fun `the selection describes where it came from`() {
        assertTrue(SolrVersionSelection.DEFAULT.describeSource().contains("latest"))
        assertTrue(
            SolrVersionSelection.fromLuceneMatchVersion("10.0.0").describeSource().contains("configset"),
        )
        assertTrue(
            SolrVersionSelection("10_0", SolrVersionSource.SERVER).describeSource().contains("server"),
        )
    }

    /**
     * Every kind reaches a page or explicitly reaches none, and the exhaustive `when` behind that is
     * the only thing making a new kind decide.
     *
     * **This is a table rather than a spot check because the failure it guards is per kind.** The
     * pages were verified by hand against the live guide on both supported lines — each one exists,
     * and each one was checked to actually describe the element it is reached from — but nothing in
     * the build can re-verify that, so what a test can still hold is the shape and the completeness:
     * no kind silently falls through, and no kind returns a URL that is not a guide page.
     */
    @Test
    fun `every class kind reaches a guide page or none`() {
        val version = SolrVersionSelection.DEFAULT
        // The two the record declines: `indexReaderFactory` is absent from the index-location page
        // that documents its neighbour, and no page could be found for `statsCache`. Both must stay
        // null rather than pointing at something adjacent and wrong.
        val withoutAPage = setOf(SolrClassKind.INDEX_READER_FACTORY, SolrClassKind.STATS_CACHE)

        for (kind in SolrClassKind.entries) {
            // A class name that places itself, since the analysis kinds choose their page from it.
            val className = when (kind) {
                SolrClassKind.TOKENIZER -> "solr.StandardTokenizerFactory"
                SolrClassKind.TOKEN_FILTER -> "solr.LowerCaseFilterFactory"
                SolrClassKind.CHAR_FILTER -> "solr.MappingCharFilterFactory"
                else -> "solr.Whatever"
            }
            val url = SolrReferenceGuide.classPage(kind, className, version)
            if (kind in withoutAPage) {
                assertNull("$kind must not link anywhere", url)
                continue
            }
            assertNotNull("$kind reaches no page", url)
            assertTrue("$kind is not a guide URL: $url", url!!.startsWith("https://solr.apache.org/guide/solr/"))
            assertTrue("$kind is not a page: $url", url.endsWith(".html"))
        }
    }

    /**
     * A kind's page follows the configset's declared line, not the latest.
     *
     * The version is threaded through every branch of the mapping, and a branch that hard-coded a
     * base would send a reader of a Solr 9 configset to Solr 10's documentation — which is the way
     * this feature would be wrong while still looking right.
     */
    @Test
    fun `a class page follows the declared line`() {
        val nine = SolrVersionSelection.fromLuceneMatchVersion("9.12.0")
        for (kind in SolrClassKind.entries) {
            val url = SolrReferenceGuide.classPage(kind, "solr.Whatever", nine) ?: continue
            assertTrue("$kind ignored the declared line: $url", url.contains("/9_"))
        }
    }

    /** Every link the plugin can produce must be an absolute https URL into the guide. */
    @Test
    fun `all links are absolute and point at the reference guide`() {
        val version = SolrVersionSelection.DEFAULT
        val urls = listOfNotNull(
            SolrReferenceGuide.fieldTypesPage(version),
            SolrReferenceGuide.fieldPropertiesPage(version),
            SolrReferenceGuide.analyzerComponentPage("solr.LowerCaseFilterFactory", version),
        )
        assertTrue(urls.all { it.startsWith("https://solr.apache.org/guide/solr/") })
        assertTrue(urls.all { it.endsWith(".html") })
    }
}
