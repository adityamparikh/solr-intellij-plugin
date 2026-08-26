package org.apache.solr.ide.model

import org.apache.solr.ide.model.schema.SolrField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The version a connected server reports, and what the model does with it.
 *
 * `SolrVersionSource.SERVER` has been documented as "the authority, when there is one" since before
 * anything could produce it: `SolrFieldModel.solrVersion` had two arms, a declared
 * `luceneMatchVersion` and a default, and no third. Every test here would have passed against that
 * code by asserting nothing, which is why the specification called this the one gap most likely to
 * ship silently wrong.
 */
class SolrServerVersionSelectionTest {

    private val facts = SolrConfigsetFacts(fields = listOf(SolrField("id", "string")))

    // --- the selection itself ----------------------------------------------------------------------

    /**
     * A server's version is a *Solr* version and needs no Lucene translation.
     *
     * `fromLuceneMatchVersion` exists because a configset only ever implies a line — Solr 10 pairs
     * with Lucene 10.3. A server states its own, so the two must not share a path.
     */
    @Test
    fun `a reported server version selects that line's guide`() {
        val selection = SolrVersionSelection.fromServerVersion("10.0.0")

        assertEquals(SolrVersionSource.SERVER, selection.source)
        assertEquals(SolrVersionSelection.fromLuceneMatchVersion("10.3.2").guidePathSegment, selection.guidePathSegment)
    }

    @Test
    fun `the other supported line resolves to its own guide`() {
        val ten = SolrVersionSelection.fromServerVersion("10.0.0")
        val nine = SolrVersionSelection.fromServerVersion("9.10.1")

        assertEquals(SolrVersionSource.SERVER, nine.source)
        assertNotEquals("the two lines must not resolve to one guide", ten.guidePathSegment, nine.guidePathSegment)
    }

    /**
     * A line this build ships no catalog for keeps `SERVER` and names the newest guide.
     *
     * The specification argues both alternatives are worse. Falling back to `DEFAULT` would discard
     * the fact that a server answered at all; constructing a segment would invent a guide URL for a
     * release that may not be published. This is the one place the server arm deliberately differs
     * from the configset arm, which does fall back to `DEFAULT`.
     */
    @Test
    fun `an unsupported line keeps the server as its source and names latest`() {
        val selection = SolrVersionSelection.fromServerVersion("14.0.0")

        assertEquals(SolrVersionSource.SERVER, selection.source)
        assertEquals("latest", selection.guidePathSegment)
    }

    /** The segment is never built from the version, which is a defect this plugin already shipped once. */
    @Test
    fun `the segment is a catalog lookup rather than the version itself`() {
        val selection = SolrVersionSelection.fromServerVersion("10.0.0")

        assertFalse(selection.guidePathSegment.contains("10.0.0"))
    }

    /** Text that is not a version is not guessed at. */
    @Test
    fun `an unreadable version still says a server answered`() {
        val selection = SolrVersionSelection.fromServerVersion("not-a-version")

        assertEquals(SolrVersionSource.SERVER, selection.source)
        assertEquals("latest", selection.guidePathSegment)
    }

    // --- the three-tier order ----------------------------------------------------------------------

    /** A server outranks a configset's own declaration, which is what "the authority" means. */
    @Test
    fun `a server version outranks the configset's declared version`() {
        val model = SolrFieldModel.of(
            facts.copy(luceneMatchVersion = "9.12.0"),
            facts,
            serverVersion = "10.0.0",
        )

        assertEquals(SolrVersionSource.SERVER, model.solrVersion.source)
    }

    @Test
    fun `a configset's declaration is used when no server answered`() {
        val model = SolrFieldModel.of(facts.copy(luceneMatchVersion = "9.12.0"), facts)

        assertEquals(SolrVersionSource.CONFIGSET, model.solrVersion.source)
    }

    /**
     * A model built with no server half resolves exactly as it did before this existed.
     *
     * The regression this whole change is gated on. `SolrFieldModel` is read by every documentation
     * and completion surface the editor track has shipped, and none of them has a server.
     */
    @Test
    fun `a model with no server half is unchanged`() {
        assertEquals(SolrVersionSource.DEFAULT, SolrFieldModel.of(facts, facts).solrVersion.source)
        assertEquals(
            SolrVersionSource.CONFIGSET,
            SolrFieldModel.of(facts.copy(luceneMatchVersion = "10.3.2"), facts).solrVersion.source,
        )
    }

    /** The reported version stays readable, since it is what a user looks at to know what they reached. */
    @Test
    fun `the reported version is kept verbatim for display`() {
        assertEquals("10.0.0", SolrFieldModel.of(facts, facts, serverVersion = "10.0.0").serverVersion)
        assertNull(SolrFieldModel.of(facts, facts).serverVersion)
    }
}
