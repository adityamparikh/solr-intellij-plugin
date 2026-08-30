package org.apache.solr.ide.server.drift

import org.apache.solr.ide.configset.schema.parsing.SolrSchemaParser
import org.apache.solr.ide.model.SolrAgreement
import org.apache.solr.ide.server.reading.SolrServerSchemaReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Solr's own shipped configset, compared against a schema a real Solr served back.
 *
 * **This is the tier that found the defect the unit tests could not.** Those exercise the spellings
 * someone thought to write — `lowercase` against `solr.LowerCaseFilterFactory` — and pass on a
 * resolution that is exactly case-sensitive. Solr's `_default` configset writes `name="CJKWidth"`
 * and a server reads the same schema back as `cjkWidth`, so a comparison that matched case reported
 * `text_cjk` as drifted between a configset and a collection built from it. Nobody would have
 * written that test; the fixtures had it all along.
 *
 * The two fixtures come from different configsets, so genuine differences are expected. What is
 * asserted is that the differences reported are the genuine ones.
 */
class SolrDriftRealFixtureTest {

    private fun resource(path: String) =
        checkNotNull(javaClass.getResourceAsStream(path)) { "missing fixture $path" }
            .bufferedReader().readText()

    private val drift = SolrDrift.between(
        SolrSchemaParser.parse(resource("/shipped-configsets/10/_default/managed-schema.xml")),
        SolrServerSchemaReader.read(resource("/server-responses/schema-10.json")),
    )

    private fun disagreeing() = drift.entries
        .filter { it.agreement == SolrAgreement.DISAGREEING }
        .map { it.name }
        .sorted()

    /**
     * Only genuinely different declarations are reported as differing.
     *
     * `text_general` differs because the configset declares `multiValued=true` and the served
     * schema does not; `text_tr` because the server's analyzer carries an `apostrophe` filter the
     * configset has no counterpart for. Both are real. Anything else appearing here is this
     * comparison inventing a difference, which is the failure the whole resolution exists to
     * prevent.
     */
    @Test
    fun `only real differences are reported`() {
        assertEquals(listOf("text_general", "text_tr"), disagreeing())
    }

    /**
     * The field type whose spelling differs between the two sources agrees.
     *
     * `CJKWidth` in the file, `cjkWidth` from the server. Named specifically because it is the one
     * the resolution was written for, and a change that broke it would otherwise show up only as a
     * count going up by one.
     */
    @Test
    fun `a type whose components are spelled differently on each side agrees`() {
        assertTrue(
            "text_cjk must not be reported as drifted: ${disagreeing()}",
            "text_cjk" !in disagreeing(),
        )
    }

    /** The comparison ran over real data, rather than agreeing about nothing. */
    @Test
    fun `the comparison covered the whole schema`() {
        assertTrue("expected many agreeing declarations, got ${drift.agreeingCount}", drift.agreeingCount > 50)
    }
}
