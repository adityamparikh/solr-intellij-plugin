package org.apache.solr.ide.server.transport

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

    /**
     * The result of [next], where this outcome succeeded, and this outcome otherwise.
     *
     * **For sequencing two writes**, where the second is worth attempting only if the first
     * arrived: uploading a configset and then reloading the collection that uses it. A failure
     * short-circuits carrying its own message, so the caller reports the step that actually failed
     * rather than the last one it happened to run.
     *
     * A partial answer counts as arrived, matching [map]: what came back is real, and a caller that
     * treated it as a failure would refuse to continue over an answer it already has.
     *
     * @param next what to do when this outcome carries a value
     * @return the next outcome, or this failure unchanged
     */
    suspend fun <R> andThen(next: suspend () -> SolrResponse<R>): SolrResponse<R> = when (this) {
        is Success, is Partial -> next()
        is SolrError -> this
        is TransportFailure -> this
        is Unrecognized -> this
    }

    /**
     * The same outcome carrying [transform] of its value, where it has one.
     *
     * **Added with its first caller rather than before it.** Converting a parsed body into facts is
     * what every consumer of this type does, and without this each would write its own five-branch
     * `when` — or reach for the value and discard the classification, which is the one thing the five
     * cases exist to prevent.
     *
     * A failure passes through untouched: there is nothing to transform, and its message is the part
     * a caller must not lose.
     *
     * @param transform what to do with a value that arrived
     * @return the same case, over the transformed value
     */
    fun <R> map(transform: (T) -> R): SolrResponse<R> = when (this) {
        is Success -> Success(transform(value))
        is Partial -> Partial(transform(value), detail)
        is SolrError -> this
        is TransportFailure -> this
        is Unrecognized -> this
    }
}
