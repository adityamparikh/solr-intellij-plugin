package org.apache.solr.ide.server.topology

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reading the health word SolrCloud reports into something the tree can draw.
 *
 * Plain JUnit 4: this is a lookup, and the colour it ends up as is the renderer's business.
 */
class SolrHealthTest {

    @Test
    fun `each word Solr reports is recognised`() {
        assertEquals(SolrHealth.GREEN, SolrHealth.of("GREEN"))
        assertEquals(SolrHealth.YELLOW, SolrHealth.of("YELLOW"))
        assertEquals(SolrHealth.ORANGE, SolrHealth.of("ORANGE"))
        assertEquals(SolrHealth.RED, SolrHealth.of("RED"))
    }

    /** Solr writes them upper-case today; a proxy or a later line writing `green` means the same thing. */
    @Test
    fun `case and surrounding space do not matter`() {
        assertEquals(SolrHealth.GREEN, SolrHealth.of("green"))
        assertEquals(SolrHealth.RED, SolrHealth.of(" Red "))
    }

    /**
     * A word this plugin does not know is not guessed at.
     *
     * Null is what tells the tree to show the word itself, which is the only honest thing to do with a
     * value a newer Solr invented: colouring it would be claiming to know how bad it is.
     */
    @Test
    fun `an unknown word is not recognised`() {
        assertNull(SolrHealth.of("PURPLE"))
        assertNull(SolrHealth.of(""))
        assertNull(SolrHealth.of(null))
    }
}
