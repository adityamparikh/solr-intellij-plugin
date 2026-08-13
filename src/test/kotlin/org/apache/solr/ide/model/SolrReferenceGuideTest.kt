package org.apache.solr.ide.model

import org.apache.solr.ide.model.vocabulary.SolrClassCatalog
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
        assertTrue(SolrReferenceGuide.fieldPropertiesPage(selection).contains("/guide/solr/10_"))

        assertTrue(
            SolrReferenceGuide.fieldTypesPage(SolrVersionSelection.fromLuceneMatchVersion("9.12.0"))
                .contains("/guide/solr/9_"),
        )
    }

    /**
     * The link names the release the catalog was generated from, not the major with a zero after it.
     *
     * **This is the assertion the previous shape could not make.** The segment used to be built as
     * `${'$'}{major}_0`, so a Solr 9 configset linked into the Solr **9.0** guide while every fact
     * rendered beside the link came from 9.10.1. Nothing failed: `9_0` is published and returns 200
     * for every page this plugin builds, so the links were live and about a Solr nobody runs — which
     * no test could see, because a test that constructs the expected URL the same way the code does
     * agrees with itself.
     *
     * **Not pinned to `9_10`, deliberately.** The supported line moves, and a literal here would fail
     * on the release that changes it rather than on the defect it guards — the same reason
     * [SolrClassCatalog]'s criteria name classes instead of counting them. What is asserted is the
     * invariant that outlives any line: the segment the link carries and the segment the catalog
     * declares are the same string, and it names a minor rather than a bare major.
     */
    @Test
    fun `the guide line is the line the catalog answered from`() {
        for (line in SolrClassCatalog.SUPPORTED_LINES) {
            val declared = SolrClassCatalog.guideSegmentFor(line)
            assertNotNull("line $line ships a catalog but declares no release", declared)
            assertTrue(
                "line $line declares $declared, which is not a major_minor segment",
                Regex("""^\d+_\d+$""").matches(declared!!),
            )
            assertEquals("line $line's segment lost its major", "$line", declared.substringBefore('_'))

            val selection = SolrVersionSelection.fromLuceneMatchVersion("$line.0.0")
            assertEquals(
                "the link and the catalog disagree about which Solr line answered",
                declared,
                selection.guidePathSegment,
            )
        }
    }

    /**
     * A major with no shipped catalog reaches `latest` rather than a constructed segment.
     *
     * Solr 11 has no guide at `11_0` until it is released, and the plugin can carry a configset from
     * a line it was not built for. Assembling a segment for it would be the same defect as `9_0` in
     * the other direction — a plausible URL, this time to nothing at all.
     */
    @Test
    fun `a major this build ships no catalog for falls back to latest`() {
        val future = SolrVersionSelection.fromLuceneMatchVersion("99.0.0")
        assertEquals(SolrVersionSource.DEFAULT, future.source)
        assertEquals("latest", future.guidePathSegment)
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
        // `charfilters`, and the name it replaced is the reason this comment is long. The guide
        // served `charfilterfactories.html` on 9.0 and renamed the page by 9.7; measured against the
        // published guide, `charfilters.html` answers on 9_7, 9_8, 9_10, 9_11 and `latest`, while
        // `charfilterfactories.html` now answers only on 9_0 and `latest`. The old name therefore
        // stayed correct for exactly as long as every Solr 9 configset was being sent to the 9.0
        // guide — one defect holding another one up. Neither `char-filter-factories.html` nor
        // `character-filter-factories.html` exists on any line; the name cannot be derived from its
        // neighbours and is checked rather than guessed.
        assertTrue(
            SolrReferenceGuide.analyzerComponentPage("solr.HTMLStripCharFilterFactory", version)!!
                .endsWith("charfilters.html"),
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
