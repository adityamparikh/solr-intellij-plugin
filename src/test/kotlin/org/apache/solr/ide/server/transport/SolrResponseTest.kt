package org.apache.solr.ide.server.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

/**
 * Sequencing two writes.
 *
 * `andThen` exists so that uploading a configset and then reloading the collection reports the step
 * that actually failed, rather than the last one that happened to run.
 */
class SolrResponseTest {

    @Test
    fun `a success runs the next step`() = runBlocking {
        val result = SolrResponse.Success(1).andThen { SolrResponse.Success("next") }

        assertEquals(SolrResponse.Success("next"), result)
    }

    /** A partial answer counts as arrived: what came back is real, and refusing to continue over it
     * would decline on an answer already in hand. */
    @Test
    fun `a partial answer runs the next step`() = runBlocking {
        val result = SolrResponse.Partial(1, "cut short").andThen { SolrResponse.Success("next") }

        assertEquals(SolrResponse.Success("next"), result)
    }

    @Test
    fun `a solr error short-circuits and keeps its own message`() = runBlocking {
        var ran = false
        val result = SolrResponse.SolrError(400, "no such configset")
            .andThen { ran = true; SolrResponse.Success("next") }

        assertEquals(SolrResponse.SolrError(400, "no such configset"), result)
        assertTrue("the second step must not run", !ran)
    }

    @Test
    fun `a transport failure short-circuits`() = runBlocking {
        var ran = false
        val result = SolrResponse.TransportFailure("refused").andThen { ran = true; SolrResponse.Success(1) }

        assertEquals(SolrResponse.TransportFailure("refused"), result)
        assertTrue("the second step must not run", !ran)
    }

    @Test
    fun `an unrecognized answer short-circuits`() = runBlocking {
        val result = SolrResponse.Unrecognized("not json").andThen { SolrResponse.Success(1) }

        assertEquals(SolrResponse.Unrecognized("not json"), result)
    }
}
