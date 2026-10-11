package org.apache.solr.ide.server.query.screen

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.apache.solr.ide.server.query.SolrRequestTemplates
import org.apache.solr.ide.server.transport.SolrHttpTransport

/**
 * One request the query console sends: where to, and with what.
 *
 * **Sent as a form POST, shown as a GET.** Several `fq`, a `json.facet` or a large `bq` pass Jetty's
 * 8 KB request-line limit as a GET, and every Solr search handler reads a form body as it reads a
 * query string; but a GET URL is what a person reads and copies, so that is what the console shows.
 *
 * @property collection the collection or core the request is addressed to
 * @property handler the request handler's path, beginning with a slash
 * @property parameters what the form produced, in order
 */
data class SolrQueryScreenRequest(
    val collection: String,
    val handler: String,
    val parameters: List<Pair<String, String>>,
) {

    /** The path under the connection's root: the collection, then the handler. */
    val path: String get() = "/" + pathSegment(collection) + handler

    /**
     * What is actually sent: the form's parameters, then `echoParams=all`.
     *
     * **`echoParams=all` so the answer says what the server ran with**, including what a paramset
     * or the handler's own defaults added — which is often the answer to why a query behaved as it
     * did. Last, so an `echoParams` the user entered as a raw parameter is the one Solr reads.
     */
    val sentParameters: List<Pair<String, String>> get() = parameters + (ECHO_PARAMS to ALL)

    /**
     * The request as a GET URL, for reading and copying.
     *
     * Without `echoParams`, which the user did not ask for, and without any `user:password@` the
     * connection's address carries, since the URL is shown on screen.
     *
     * @param baseUrl the connection's server root
     * @return the URL
     */
    fun shownUrl(baseUrl: String): String {
        val query = if (parameters.isEmpty()) "" else "?" + SolrHttpTransport.formEncoded(parameters)
        return USER_INFO.replace(baseUrl, "$1").trimEnd('/') + path + query
    }

    /**
     * The request as an HTTP Client request, for saving in the repository.
     *
     * **Written as the committed templates write theirs**, with `{{solrUrl}}` and `{{collection}}`
     * rather than a host, so a colleague's environment file supplies their own server; and never an
     * `Authorization` header or a password. **A form POST, with each value already encoded**, because
     * the HTTP Client cannot tell that an `&` in a URL belongs to a value — which is the problem the
     * console exists to solve, and copying a query out of it must not bring the problem back.
     *
     * @return the text of an `.http` file
     */
    fun httpRequestText(): String = buildString {
        append("### Query $collection, from the Solr query console\n")
        append("# Define {{${SolrRequestTemplates.URL_VARIABLE}}} and {{${SolrRequestTemplates.COLLECTION_VARIABLE}}} ")
        append("in http-client.env.json; this request was written for $collection.\n")
        append("POST {{${SolrRequestTemplates.URL_VARIABLE}}}/{{${SolrRequestTemplates.COLLECTION_VARIABLE}}}$handler\n")
        append("Content-Type: application/x-www-form-urlencoded\n")
        append("\n")
        append(SolrHttpTransport.formEncoded(parameters))
        append("\n")
    }

    /** What a form could be turned into: a request, or the reason it cannot be sent. */
    sealed interface Built {

        /**
         * The form makes a request.
         *
         * @property request the request
         */
        data class Ready(val request: SolrQueryScreenRequest) : Built

        /**
         * The form cannot be sent as it stands.
         *
         * @property reason what is wrong
         * @property detail the entry that is wrong, as entered
         */
        data class Refused(val reason: Reason, val detail: String) : Built
    }

    /** Why a form cannot be sent. */
    enum class Reason {

        /**
         * The handler is not a path. The Admin UI's other form, `/select` with a `qt` parameter, is
         * ignored unless the server sets `handleSelect="true"`, which is off by default — so sending
         * it would silently run `/select` instead of the handler named.
         */
        HANDLER_NOT_A_PATH,

        /** A raw parameter line has no `name=`. */
        RAW_WITHOUT_NAME,
    }

    /** Turning a form into a request. */
    companion object {

        /**
         * The request [state] makes for [collection], or why it makes none.
         *
         * @param state what the form holds
         * @param collection the collection the tab is for
         * @return the request, or the refusal
         */
        fun of(state: SolrQueryScreenState, collection: String): Built {
            val handler = state.handler
            if (!handler.startsWith('/') || handler.any { it.isWhitespace() || it == '?' || it == '#' }) {
                return Built.Refused(Reason.HANDLER_NOT_A_PATH, handler)
            }
            state.rawProblems().firstOrNull()?.let { return Built.Refused(Reason.RAW_WITHOUT_NAME, it) }
            return Built.Ready(SolrQueryScreenRequest(collection, handler, state.parameters()))
        }

        private const val ECHO_PARAMS = "echoParams"
        private const val ALL = "all"

        /** A scheme followed by credentials, as `http://user:pass@`. */
        private val USER_INFO = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*://)[^/@]*@")

        // A path segment, so a space is %20 rather than the form encoding's `+`.
        private fun pathSegment(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
    }
}
