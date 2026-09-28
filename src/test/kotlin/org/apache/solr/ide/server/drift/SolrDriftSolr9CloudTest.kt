package org.apache.solr.ide.server.drift

import org.apache.solr.ide.model.SolrAgreement
import org.apache.solr.ide.model.SolrConfigsetFacts
import org.apache.solr.ide.model.SolrVersionSource
import org.apache.solr.ide.model.schema.SolrField
import org.apache.solr.ide.server.reading.SolrJsonDocuments
import org.apache.solr.ide.server.reading.SolrServerRead
import org.apache.solr.ide.server.reading.SolrServerSchemaReader
import org.apache.solr.ide.server.transport.SolrResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The drift view's reading path, fed what a real SolrCloud 9.10.1 returned.
 *
 * The two fixtures were captured read-only from a user's own cluster while investigating a report
 * that the Drift tab did not seem to work: `GET /solr/shows/schema` for a collection built from
 * `_default`, and `GET /solr/admin/info/system`. The schema reader and the comparison had only ever
 * been fed Solr 10 responses, so the question worth pinning is whether a 9.x SolrCloud answer
 * survives the same path intact — every declaration read, nothing thrown, the version carried
 * through. It does, and this keeps it so.
 */
class SolrDriftSolr9CloudTest {

    private fun resource(path: String) =
        checkNotNull(javaClass.getResourceAsStream(path)) { "missing fixture $path" }
            .bufferedReader().readText()

    private val served = SolrServerSchemaReader.read(resource("/server-responses/schema-9.json"))

    private val version = SolrServerSchemaReader.solrVersionIn(
        checkNotNull(SolrJsonDocuments.treeOf(resource("/server-responses/system-info-9.json"))),
    )

    /** Every declaration the server listed is read, rather than a prefix or none. */
    @Test
    fun `a Solr 9 schema response is read in full`() {
        assertEquals(21, served.fields.size)
        assertEquals(68, served.fieldTypes.size)
        assertEquals(69, served.dynamicFields.size)
        assertEquals("id", served.uniqueKey)
    }

    /** The version is `solr-spec-version`, not the Lucene number beside it. */
    @Test
    fun `the Solr 9 version is read from the right key`() {
        assertEquals("9.10.1", version)
    }

    /**
     * A collection compared with its own served schema agrees across every declaration.
     *
     * The strongest check a single fixture allows: any spelling or rendering asymmetry between how
     * the reader fills a fact and how the comparison renders it would show up here as drift that
     * cannot exist.
     */
    @Test
    fun `a served schema agrees with itself`() {
        val drift = SolrDrift.between(served, served, version)

        assertTrue(drift.entries.toString(), drift.isClean)
        assertEquals(21 + 68 + 69 + 1, drift.agreeingCount)
    }

    /**
     * An unrelated configset against this collection is compared, and reported as mostly server-only.
     *
     * The shape of the report the tester saw: a small configset against a collection built from
     * `_default` lists that configset's own declarations as not deployed and nearly everything else
     * as only on the server. That is correct, and the view names the Solr it resolved against.
     */
    @Test
    fun `an unrelated configset reports the server's declarations as only on the server`() {
        val products = SolrConfigsetFacts(fields = listOf(SolrField(name = "sku", type = "string")), uniqueKey = "id")

        val view = driftViewFor("products", "shows", products, SolrResponse.Success(SolrServerRead(served, version)))

        assertTrue(view.toString(), view is SolrDriftView.Compared)
        val drift = (view as SolrDriftView.Compared).drift
        assertEquals(1, drift.countsByAgreement[SolrAgreement.REPOSITORY_ONLY])
        assertEquals(21 + 68 + 69, drift.countsByAgreement[SolrAgreement.SERVER_ONLY])
        assertEquals(SolrVersionSource.SERVER, drift.solrVersion.source)
        assertEquals("9_10", drift.solrVersion.guidePathSegment)
    }
}
