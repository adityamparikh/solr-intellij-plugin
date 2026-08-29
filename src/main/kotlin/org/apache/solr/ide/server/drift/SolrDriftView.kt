package org.apache.solr.ide.server.drift

import org.apache.solr.ide.model.SolrConfigsetFacts
import org.apache.solr.ide.server.reading.SolrServerRead
import org.apache.solr.ide.server.topology.failureMessageFor
import org.apache.solr.ide.server.topology.valueIn
import org.apache.solr.ide.server.topology.warningFor
import org.apache.solr.ide.server.transport.SolrResponse

/**
 * Everything the drift view can be showing.
 *
 * **"Not compared" is a state, and keeping it distinct is the whole point.** A comparison that has
 * not run and a comparison that found nothing look identical if both render as an empty table, and
 * they mean opposite things — one is "nobody asked", the other is "the configset and the collection
 * agree". A view that conflated them would report a clean deployment to a user who never connected.
 */
sealed interface SolrDriftView {

    /** Nothing has been compared yet, because nobody has asked. */
    data object NotCompared : SolrDriftView

    /** A comparison is in flight. */
    data object Comparing : SolrDriftView

    /**
     * A comparison ran, and this is what it found.
     *
     * @property configset the configset compared, named so the answer can be attributed
     * @property collection the collection it was compared against
     * @property drift what the two disagree about, empty where they agree
     * @property warning what to say about an answer that arrived incomplete, or null
     */
    data class Compared(
        val configset: String,
        val collection: String,
        val drift: SolrDrift,
        val warning: String? = null,
    ) : SolrDriftView

    /**
     * The server could not be read, so nothing was compared.
     *
     * **Distinct from a comparison finding everything missing**, which is what a view that treated a
     * failed read as an empty server would show — an entire schema reported as undeployed because a
     * password was wrong.
     *
     * @property message the failure in Solr's own words where Solr spoke
     */
    data class Failed(val message: String) : SolrDriftView
}

/**
 * What to show for a configset compared against what a server answered.
 *
 * **A failure produces [SolrDriftView.Failed], never a comparison against nothing.** Treating an
 * unreadable server as an empty one would turn a wrong password into a report that every field in
 * the schema is undeployed — confidently, and in the view a user consults precisely when they are
 * unsure what is deployed.
 *
 * **A partial answer is compared and labelled.** What arrived is real, but a comparison built on it
 * would report fields as missing from a server that merely stopped early — so the warning travels
 * with the result rather than being left for the reader to infer.
 *
 * @param configset the configset's name, for attributing the answer
 * @param collection the collection asked about
 * @param repository the facts parsed from the configset on disk
 * @param response what the server reader returned
 * @return the state the drift view should be in
 */
fun driftViewFor(
    configset: String,
    collection: String,
    repository: SolrConfigsetFacts,
    response: SolrResponse<SolrServerRead>,
): SolrDriftView {
    failureMessageFor(response)?.let { return SolrDriftView.Failed(it) }
    val server = valueIn(response)?.facts ?: return SolrDriftView.Failed(NO_FACTS)
    return SolrDriftView.Compared(
        configset = configset,
        collection = collection,
        drift = SolrDrift.between(repository, server),
        warning = warningFor(response),
    )
}

// Unreachable through the reader, which returns a failure rather than a success carrying nothing —
// but stated rather than asserted, because the alternative to a message here is comparing against
// facts that do not exist, which is the one outcome this function is built to prevent.
private const val NO_FACTS = "The server answered without a schema."
