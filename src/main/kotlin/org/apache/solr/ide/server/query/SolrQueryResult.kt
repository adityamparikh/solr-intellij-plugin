package org.apache.solr.ide.server.query

/**
 * A query's answer, in the shape a table needs.
 *
 * Distinct from everything in `server.reading`: those readers turn a response into facts about a
 * *configuration*, which outlive the request. This is one answer to one query, and exists only to be
 * shown.
 *
 * @property status what Solr put in `responseHeader.status`, or null where it said none
 * @property queryTimeMillis what Solr spent, as it reported it
 * @property numFound how many documents matched, which is not how many came back
 * @property start the offset the returned window begins at
 * @property columns every field name across the returned documents, in first-seen order, with
 *   Solr's internal fields dropped — see [SolrQueryResultReader]
 * @property hiddenColumns the internal fields that were dropped, so their absence can be stated
 *   rather than left to be noticed
 * @property rows one map per document, keyed by column, already rendered to text
 * @property explanations document id to Solr's own scoring explanation, which arrives already
 *   indented and is passed through rather than reformatted
 * @property parsedQuery how Solr understood the query, where `debugQuery` asked
 */
data class SolrQueryResult(
    val status: Int? = null,
    val queryTimeMillis: Int? = null,
    val numFound: Long? = null,
    val start: Long? = null,
    val columns: List<String> = emptyList(),
    val hiddenColumns: List<String> = emptyList(),
    val rows: List<Map<String, String>> = emptyList(),
    val explanations: Map<String, String> = emptyMap(),
    val parsedQuery: String? = null,
)
