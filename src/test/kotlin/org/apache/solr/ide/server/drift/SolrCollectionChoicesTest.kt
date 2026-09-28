package org.apache.solr.ide.server.drift

import org.apache.solr.ide.server.reading.SolrCollection
import org.apache.solr.ide.server.reading.SolrCore
import org.apache.solr.ide.server.reading.SolrServerMode
import org.apache.solr.ide.server.reading.SolrTopology
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the drift view's collection chooser offers for a topology.
 *
 * Plain JUnit 4: a topology in, a list out.
 */
class SolrCollectionChoicesTest {

    @Test
    fun `a cloud offers its collections, sorted, with the configset each was built from`() {
        val topology = SolrTopology(
            SolrServerMode.SOLR_CLOUD,
            collections = listOf(
                SolrCollection("shows", "_default", null, emptyList()),
                SolrCollection("books", null, null, emptyList()),
            ),
        )

        assertEquals(
            listOf(SolrCollectionChoice("books", null), SolrCollectionChoice("shows", "_default")),
            collectionChoicesIn(topology),
        )
    }

    /** A standalone server's cores are what its schema endpoint answers for, so they are offered. */
    @Test
    fun `a standalone server offers its cores`() {
        val topology = SolrTopology(SolrServerMode.STANDALONE, cores = listOf(SolrCore("techproducts", "sample")))

        assertEquals(listOf(SolrCollectionChoice("techproducts", "sample")), collectionChoicesIn(topology))
    }

    /** A server that would not say what it is offers nothing rather than guessing. */
    @Test
    fun `a server of unknown mode offers nothing`() {
        assertTrue(collectionChoicesIn(SolrTopology(SolrServerMode.UNKNOWN)).isEmpty())
    }
}
