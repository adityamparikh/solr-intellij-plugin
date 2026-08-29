package org.apache.solr.ide.server.query

import org.apache.solr.ide.server.reading.SolrJsonDocuments
import tools.jackson.databind.JsonNode

/**
 * Reads a query response into the shape a table needs.
 *
 * **What counts as a Solr query response is decided by the body, never by the URL.** The presentation
 * this feeds is offered for every response the HTTP Client shows, including everyone else's, so the
 * question "is this ours" has to be answered from what arrived. A URL rule would be wrong twice over:
 * Solr behind a reverse proxy is addressed at any path at all, and a non-Solr service is free to
 * expose one called `/select`.
 *
 * The signal is `responseHeader.status` alongside an array at `response.docs` — the shape only a
 * query answers with. A schema or Luke response carries the header and no documents, and is left
 * alone deliberately: it is already readable JSON, and a table of nothing would be this plugin
 * taking over a view it has nothing to add to.
 */
object SolrQueryResultReader {

    /**
     * The result in [body], or null where it is not a Solr query response.
     *
     * Null rather than an empty result, because "not ours" and "ours and empty" are answered
     * differently: the first prints nothing at all, and the second says no documents matched.
     *
     * @param body a response body
     * @return what to show, or null to leave the response alone
     */
    fun read(body: String): SolrQueryResult? = SolrJsonDocuments.treeOf(body)?.let { read(it) }

    /**
     * The result in an already-parsed [response], or null where it is not a query response.
     *
     * @param response a parsed response body
     * @return what to show, or null to leave the response alone
     */
    fun read(response: JsonNode): SolrQueryResult? {
        val header = response.path(RESPONSE_HEADER)
        val documents = response.path(RESPONSE).path(DOCS)
        if (!header.path(STATUS).isNumber || !documents.isArray) return null

        val docs = documents.toList()
        val allColumns = docs.flatMap { doc -> doc.propertyNames().toList() }.distinct()
        val (hidden, columns) = allColumns.partition { it.isInternal() }

        return SolrQueryResult(
            status = header.intOrNull(STATUS),
            queryTimeMillis = header.intOrNull(QUERY_TIME),
            numFound = response.path(RESPONSE).longOrNull(NUM_FOUND),
            start = response.path(RESPONSE).longOrNull(START),
            columns = columns,
            hiddenColumns = hidden,
            rows = docs.map { doc -> columns.associateWith { cellOf(doc.path(it)) } },
            explanations = response.path(DEBUG).path(EXPLAIN).properties()
                .filter { (_, value) -> value.isString }
                .associate { (id, value) -> id to value.asString("") },
            parsedQuery = response.path(DEBUG).path(PARSED_QUERY).takeIf { it.isString }?.asString(""),
        )
    }

    /**
     * Whether a field is one of Solr's own rather than one the user indexed.
     *
     * `_version_` and `_root_` come back on every document and tell a reader nothing they asked for,
     * while `_version_` alone is nineteen digits wide and would decide the shape of every table it
     * appeared in. They are dropped from the table and named in [SolrQueryResult.hiddenColumns], so
     * their absence is stated rather than left to be noticed — and the response's full JSON is
     * printed underneath regardless, so nothing is actually withheld.
     *
     * Keyed on Solr's own convention for internal fields rather than on a list of the two seen so
     * far, which would go stale the first time Solr added a third.
     */
    private fun String.isInternal(): Boolean = length > 2 && startsWith('_') && endsWith('_')

    /**
     * One cell's text.
     *
     * **An array is joined rather than printed as one**, because a single value routinely arrives as
     * one: `text_general` is multiValued in Solr's own `_default` configset, so `"title": ["Dune"]`
     * is what the most ordinary query there is returns. A renderer assuming scalars would print
     * `["Dune"]` in a column of otherwise clean values.
     */
    private fun cellOf(node: JsonNode): String = when {
        node.isMissingNode || node.isNull -> ""
        node.isArray -> node.joinToString(SEPARATOR) { cellOf(it) }
        node.isString -> node.asString("")
        else -> node.toString()
    }

    private fun JsonNode.intOrNull(field: String): Int? = path(field).takeIf { it.isNumber }?.asInt()

    private fun JsonNode.longOrNull(field: String): Long? = path(field).takeIf { it.isNumber }?.asLong()

    private const val SEPARATOR = ", "
    private const val RESPONSE_HEADER = "responseHeader"
    private const val RESPONSE = "response"
    private const val DOCS = "docs"
    private const val STATUS = "status"
    private const val QUERY_TIME = "QTime"
    private const val NUM_FOUND = "numFound"
    private const val START = "start"
    private const val DEBUG = "debug"
    private const val EXPLAIN = "explain"
    private const val PARSED_QUERY = "parsedquery"
}
