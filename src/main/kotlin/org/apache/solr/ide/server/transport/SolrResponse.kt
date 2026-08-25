package org.apache.solr.ide.server.transport

import tools.jackson.databind.JsonNode

/**
 * What came back from a request to Solr, classified so a caller cannot mistake one outcome for
 * another.
 *
 * **Five outcomes rather than a value and an exception**, because the differences between them are
 * exactly what the user needs told. Solr saying "undefined field categry" is the user's problem to
 * fix and must reach them in Solr's own words; a connection refused is the plugin's to describe,
 * since Solr said nothing at all; a body that parsed but was not the expected shape is neither, and
 * pretending it is either would be the plugin inventing an explanation.
 *
 * **[Partial] is the outcome that does not look like one.** A Solr response can arrive with HTTP 200
 * and `responseHeader.status` of zero and still be incomplete, saying so only through a
 * `partialResults` flag. Folded into [Success], a drift comparison built on such a response would
 * report fields as missing from a server that merely stopped early — inventing disagreement out of a
 * truncation.
 */
sealed interface SolrResponse<out T> {

    /**
     * Solr answered, completely.
     *
     * @property value what it answered with
     */
    data class Success<T>(val value: T) : SolrResponse<T>

    /**
     * Solr answered, incompletely, and said so.
     *
     * @property value what did arrive, which is real but not all of it
     * @property detail Solr's own account of what was cut short, where it gave one
     */
    data class Partial<T>(val value: T, val detail: String?) : SolrResponse<T>

    /**
     * Solr refused, and its refusal is reported rather than rewritten.
     *
     * @property code the status Solr answered with
     * @property message Solr's own message, or null where there is none to quote — a mistyped
     *   collection answers with a servlet error page carrying no Solr message at all, and inventing
     *   one would put words in Solr's mouth
     */
    data class SolrError(val code: Int, val message: String?) : SolrResponse<Nothing>

    /**
     * The request did not reach Solr, or its answer did not arrive.
     *
     * Described in terms of what happened rather than in Solr's vocabulary, because Solr never spoke.
     *
     * @property description what went wrong, for showing to a user
     */
    data class TransportFailure(val description: String) : SolrResponse<Nothing>

    /**
     * Something arrived and could not be understood.
     *
     * The outcome for a body that is not JSON where JSON was promised, or is JSON in a shape this
     * plugin does not know. Reported as unrecognized rather than thrown, per the specification's rule
     * that an unrecognized server version is reported rather than refused.
     *
     * @property description what could not be read
     */
    data class Unrecognized(val description: String) : SolrResponse<Nothing>
}

/** The value where one arrived, complete or not, and null for every failing outcome. */
val SolrResponse<JsonNode>.valueOrNull: JsonNode?
    get() = when (this) {
        is SolrResponse.Success -> value
        is SolrResponse.Partial -> value
        else -> null
    }
