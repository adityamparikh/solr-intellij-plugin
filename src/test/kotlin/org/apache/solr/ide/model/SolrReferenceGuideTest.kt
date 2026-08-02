package org.apache.solr.ide.model

import org.junit.Assert.assertEquals
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
