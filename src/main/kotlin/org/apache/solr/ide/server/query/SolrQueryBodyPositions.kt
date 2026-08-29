package org.apache.solr.ide.server.query

/**
 * Which positions in a Solr JSON query body name a field.
 *
 * **A pure function over a JSON path**, so the rules can be stated and tested without an editor,
 * a caret, or an injected fragment. What computes the path from PSI is glue; this is the part with
 * an opinion in it.
 *
 * The paths are Solr's [JSON Request
 * API](https://solr.apache.org/guide/solr/latest/query-guide/json-request-api.html), which is the
 * shape a body-carrying query takes. Query *syntax* — the `field:value` inside `query` and
 * `filter` — is deliberately not handled here: completing inside it means parsing Solr's query
 * parser's language, which is its own step, and guessing at it would offer field names in the
 * middle of a phrase or a function call.
 */
object SolrQueryBodyPositions {

    /**
     * Whether [path] names a field.
     *
     * The path is the JSON keys from the document root to the caret, with array elements
     * contributing nothing — `fields` and `fields[2]` are the same position, because a field list
     * may be written either as one string or as an array of them and Solr reads both.
     *
     * @param path the JSON keys from the root to the caret, outermost first
     * @return true where a field name belongs there
     */
    fun namesAField(path: List<String>): Boolean = when {
        path.isEmpty() -> false
        // `"fields": "id,title"` and `"fields": ["id", "title"]` are the same request.
        path == listOf(FIELDS) -> true
        // `"sort": "price_f asc"` — the field is the leading token, and offering one where the
        // direction goes is a small wrong rather than a large one, since the user is mid-word.
        path == listOf(SORT) -> true
        // `"facet": { "prices": { "field": "price_f" } }` — the inner `field`, at any facet name.
        path.size == 3 && path.first() == FACET && path.last() == FIELD -> true
        else -> false
    }

    /**
     * Whether a body at [path] is one this plugin should offer anything in at all.
     *
     * Distinct from [namesAField] so that "not a Solr query body" and "a Solr query body, but not
     * at a field" are answered separately — the first means stay out of another plugin's editor,
     * and the second means stay quiet in our own.
     *
     * @param path the JSON keys from the root to the caret, outermost first
     * @return true where the path sits inside a recognisable Solr query body
     */
    fun isQueryBody(path: List<String>): Boolean = path.firstOrNull() in TOP_LEVEL_KEYS

    /** The keys Solr's JSON Request API reads at the top level of a query. */
    private val TOP_LEVEL_KEYS = setOf(
        "query", "filter", FIELDS, SORT, "offset", "limit", FACET, "params", "queries",
    )

    private const val FIELDS = "fields"
    private const val SORT = "sort"
    private const val FACET = "facet"
    private const val FIELD = "field"
}
