package org.apache.solr.ide.server.drift

import org.apache.solr.ide.model.SolrConfigsetFacts
import org.apache.solr.ide.model.SolrVersionSource
import org.apache.solr.ide.server.reading.SolrServerRead
import org.apache.solr.ide.server.reading.SolrServerSchemaReader
import org.apache.solr.ide.server.transport.SolrResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole path, from a real Solr response to what the view says about it.
 *
 * The captured system-info and schema responses are the ones Solr 10.0.0 actually returned. What
 * this pins is that the version survives every hop between them and the summary — reader, read,
 * comparison, model, view — because it was dropped at one of those hops for the entire server track
 * and every unit test on either side of the gap still passed.
 */
class SolrServerVersionEndToEndTest {

    private fun resource(path: String) =
        checkNotNull(javaClass.getResourceAsStream(path)) { "missing $path" }.bufferedReader().readText()

    @Test
    fun `a real server response reaches the view as the connected server`() {
        val systemInfo = org.apache.solr.ide.server.reading.SolrJsonDocuments.treeOf(
            resource("/server-responses/system-info-10.json"),
        )!!
        val version = SolrServerSchemaReader.solrVersionIn(systemInfo)
        assertEquals("10.0.0", version)

        val facts = SolrServerSchemaReader.read(resource("/server-responses/schema-10.json"))
        val view = driftViewFor(
            configset = "books",
            collection = "books_prod",
            repository = SolrConfigsetFacts(),
            response = SolrResponse.Success(SolrServerRead(facts, version)),
        )

        assertTrue(view.toString(), view is SolrDriftView.Compared)
        val drift = (view as SolrDriftView.Compared).drift
        assertEquals(SolrVersionSource.SERVER, drift.solrVersion.source)
        assertTrue(drift.solrVersion.describeSource(), drift.solrVersion.describeSource().contains("connected server"))
    }
}
