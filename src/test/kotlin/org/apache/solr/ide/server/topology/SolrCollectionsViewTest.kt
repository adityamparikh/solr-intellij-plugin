package org.apache.solr.ide.server.topology

import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase
import org.apache.solr.ide.server.reading.SolrCore
import org.apache.solr.ide.server.reading.SolrServerMode
import org.apache.solr.ide.server.reading.SolrTopology
import org.apache.solr.ide.server.transport.SolrResponse

/**
 * What the tool window says about what came back.
 *
 * Extends the platform test case only because the messages come from the bundle; nothing here needs
 * a project, a file or a caret.
 */
class SolrCollectionsViewTest : SolrConfigsetTestCase() {

    private val standalone = SolrTopology(SolrServerMode.STANDALONE, cores = listOf(SolrCore("books", "_default")))

    private fun failureMessageFor(response: SolrResponse<SolrTopology>): String {
        val view = viewFor(response)
        assertTrue(view.toString(), view is SolrCollectionsView.Failed)
        return (view as SolrCollectionsView.Failed).message
    }

    // --- what arrived --------------------------------------------------------------------------

    fun testACompleteAnswerLoadsWithNoWarning() {
        val view = viewFor(SolrResponse.Success(standalone))

        assertTrue(view.toString(), view is SolrCollectionsView.Loaded)
        val loaded = view as SolrCollectionsView.Loaded
        assertEquals(listOf("Cores"), loaded.roots.map { it.label })
        assertNull("a complete answer has nothing to warn about", loaded.warning)
    }

    /**
     * A partial answer is loaded and labelled, not thrown away.
     *
     * What arrived is real — it is simply not all of it. Failing here would trade a usable view for
     * a complete objection, and showing it unlabelled would let a user read a truncation as the
     * whole truth.
     */
    fun testAPartialAnswerIsShownAndSaidToBePartial() {
        val view = viewFor(SolrResponse.Partial(standalone, "time allowed exceeded"))

        assertTrue(view.toString(), view is SolrCollectionsView.Loaded)
        val loaded = view as SolrCollectionsView.Loaded
        assertEquals(listOf("Cores"), loaded.roots.map { it.label })
        assertNotNull("a partial answer must say so", loaded.warning)
        assertTrue(loaded.warning!!, loaded.warning!!.contains("time allowed exceeded"))
    }

    /** A partial answer Solr gave no detail for still says it was partial. */
    fun testAPartialAnswerWithNoDetailStillWarns() {
        val loaded = viewFor(SolrResponse.Partial(standalone, null)) as SolrCollectionsView.Loaded

        assertNotNull(loaded.warning)
    }

    // --- what did not ---------------------------------------------------------------------------

    /** Solr's own words, not the plugin's paraphrase of them. */
    fun testASolrErrorIsQuotedVerbatim() {
        val message = failureMessageFor(SolrResponse.SolrError(400, "undefined field categry"))

        assertTrue(message, message.contains("undefined field categry"))
        assertTrue(message, message.contains("400"))
    }

    /**
     * A refusal Solr attached no message to reports the code and invents nothing.
     *
     * The shape a mistyped collection produces: the servlet container's HTML 404 page, which carries
     * no Solr error document at all. A plugin that filled in a plausible message here would be
     * putting words in Solr's mouth precisely where it knows least.
     */
    fun testASolrErrorWithNoMessageReportsTheCodeAlone() {
        val message = failureMessageFor(SolrResponse.SolrError(404, null))

        assertTrue(message, message.contains("404"))
        assertFalse("nothing may be invented", message.contains("null"))
    }

    /** Solr never spoke, so the failure is described in terms of what happened instead. */
    fun testATransportFailureIsDescribedAsWhatHappened() {
        val message = failureMessageFor(SolrResponse.TransportFailure("Connection refused"))

        assertTrue(message, message.contains("Connection refused"))
    }

    fun testAnUnrecognizedAnswerSaysWhatCouldNotBeRead() {
        val message = failureMessageFor(SolrResponse.Unrecognized("expected a JSON object"))

        assertTrue(message, message.contains("expected a JSON object"))
    }

    /**
     * A server that would not say which mode it is in loads an empty tree rather than failing.
     *
     * Nothing went wrong — there is simply nothing that can be said about what it holds, and calling
     * that a failure would report a healthy server as broken.
     */
    fun testAnUnknownServerLoadsEmptyRatherThanFailing() {
        val view = viewFor(SolrResponse.Success(SolrTopology(SolrServerMode.UNKNOWN)))

        assertTrue(view.toString(), view is SolrCollectionsView.Loaded)
        assertTrue((view as SolrCollectionsView.Loaded).roots.isEmpty())
    }
}
