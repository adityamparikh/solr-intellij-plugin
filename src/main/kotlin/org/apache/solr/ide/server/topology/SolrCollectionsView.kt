package org.apache.solr.ide.server.topology

import org.apache.solr.ide.SolrBundle
import org.apache.solr.ide.server.reading.SolrServerMode
import org.apache.solr.ide.server.reading.SolrTopology
import org.apache.solr.ide.server.transport.SolrResponse

/**
 * Everything the collections tool window can be showing.
 *
 * **A type rather than a set of flags on the panel**, because the states are exclusive and the
 * mistakes worth preventing are all combinations: a tree still on screen under a failure banner, a
 * spinner that never clears, an empty tree that means "no connection" and reads as "empty server".
 * Naming them makes each one a case the panel must handle rather than a condition it might forget.
 */
sealed interface SolrCollectionsView {

    /** No connection is selected, so there is nothing to ask. */
    data object NoConnection : SolrCollectionsView

    /** A request is in flight. */
    data object Loading : SolrCollectionsView

    /**
     * The server answered and its topology is on screen.
     *
     * @property roots the tree's top-level rows
     * @property warning what to say above them, or null where the answer was complete — the case a
     *   partial response produces, which is a success that must not be read as the whole truth
     */
    data class Loaded(val roots: List<SolrTopologyNode>, val warning: String? = null) : SolrCollectionsView

    /**
     * The server could not be read, and this is what to say about it.
     *
     * @property message the failure in Solr's own words where Solr spoke, and in the plugin's where
     *   it did not
     */
    data class Failed(val message: String) : SolrCollectionsView
}

/**
 * What to show for what came back.
 *
 * **Solr's own message is quoted rather than rewritten**, per the specification's promise. Where Solr
 * said nothing — a mistyped collection answers with the servlet container's HTML 404, which carries
 * no Solr message at all — the code is reported without one, because inventing a message would put
 * words in Solr's mouth and the words most worth inventing are the ones most likely to be wrong.
 *
 * **A partial answer is loaded, not failed.** What arrived is real; it is simply not all of it, and
 * discarding it would trade a usable view for a complete objection.
 *
 * @param response what the server reader returned
 * @return the state the tool window should be in
 */
fun viewFor(response: SolrResponse<SolrTopology>): SolrCollectionsView =
    when (val failure = failureMessageFor(response)) {
        null -> SolrCollectionsView.Loaded(
            roots = SolrTopologyNodes.rootsOf(valueIn(response) ?: SolrTopology(SolrServerMode.UNKNOWN)),
            warning = warningFor(response),
        )
        else -> SolrCollectionsView.Failed(failure)
    }

/**
 * Why [response] carries nothing usable, or null where it carries something.
 *
 * **Shared by every surface that reads a server, rather than each writing its own five-case
 * `when`.** The indexed-fields view needs exactly this mapping over a different value type, and a
 * second copy would be a second place for Solr's message to get paraphrased — which is the one thing
 * the specification says must not happen to it.
 *
 * A partial answer is not a failure: what arrived is real. See [warningFor].
 *
 * @param response any outcome from a request to Solr
 * @return the message to show, or null where the request succeeded
 */
fun failureMessageFor(response: SolrResponse<*>): String? = when (response) {
    is SolrResponse.Success, is SolrResponse.Partial -> null
    is SolrResponse.SolrError ->
        response.message?.let { SolrBundle.message("collections.solrError", response.code, it) }
            ?: SolrBundle.message("collections.solrErrorNoMessage", response.code)
    is SolrResponse.TransportFailure -> SolrBundle.message("collections.transportFailure", response.description)
    is SolrResponse.Unrecognized -> SolrBundle.message("collections.unrecognized", response.description)
}

/**
 * What to say beside an answer that arrived incomplete, or null where it did not.
 *
 * @param response any outcome from a request to Solr
 * @return the warning to show, or null where nothing was cut short
 */
fun warningFor(response: SolrResponse<*>): String? = (response as? SolrResponse.Partial)?.let {
    it.detail?.let { detail -> SolrBundle.message("collections.partial.detail", detail) }
        ?: SolrBundle.message("collections.partial")
}

/**
 * The value [response] carries, where it carries one.
 *
 * @param response any outcome from a request to Solr
 * @return the value from a complete or partial answer, or null from a failure
 */
fun <T> valueIn(response: SolrResponse<T>): T? = when (response) {
    is SolrResponse.Success -> response.value
    is SolrResponse.Partial -> response.value
    else -> null
}
