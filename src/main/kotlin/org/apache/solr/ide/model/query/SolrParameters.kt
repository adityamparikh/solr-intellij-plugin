package org.apache.solr.ide.model.query

/**
 * The Solr request parameters this plugin knows by name.
 *
 * **One list, because three tracks already name the same parameters and nothing made them agree.**
 * `solrconfig.xml` writes `qf` and `facet.field` inside a handler's defaults; a Java file writes them
 * through `SolrQuery.addFacetField`; the query console will write them directly. All three were
 * spelling the same strings independently, and `facet.field` appeared in all three packages.
 *
 * The cost of that was not duplication but disagreement. A parameter the code track produces and this
 * package has never heard of resolves to no operation at all — and "no opinion" is indistinguishable
 * from "this field is fine", so the plugin goes quiet on a whole family of parameters without
 * anything reporting a fault. Naming each parameter once is what makes the two sides share a
 * vocabulary rather than two overlapping guesses at one.
 *
 * In `model` because a parameter name is a fact about Solr rather than about any file format or any
 * client library, and because both other tracks already depend on this package.
 */
object SolrParameters {

    // --- the query itself ---------------------------------------------------------------------------

    /** The main query. Holds query syntax rather than a field list. */
    const val QUERY: String = "q"

    /** A filter query. Holds query syntax, and is cached separately by Solr. */
    const val FILTER_QUERY: String = "fq"

    /** The default field a bare term searches, where the query names none. */
    const val DEFAULT_FIELD: String = "df"

    // --- what comes back ----------------------------------------------------------------------------

    /** The field list a response returns. */
    const val FIELD_LIST: String = "fl"

    /** The field whose value identifies a document. */
    const val UNIQUE_KEY: String = "uniqueKey"

    // --- relevance ----------------------------------------------------------------------------------

    /** DisMax query fields, each optionally boosted. */
    const val QUERY_FIELDS: String = "qf"

    /** Phrase fields, and the two n-gram phrase variants beside them. */
    const val PHRASE_FIELDS: String = "pf"

    /** Bigram phrase fields. */
    const val PHRASE_FIELDS_BIGRAM: String = "pf2"

    /** Trigram phrase fields. */
    const val PHRASE_FIELDS_TRIGRAM: String = "pf3"

    /** An additive boost, written as a function query rather than a field list. */
    const val BOOST_FUNCTION: String = "bf"

    /** A multiplicative boost, likewise a function query. */
    const val BOOST: String = "boost"

    // --- ordering and grouping ----------------------------------------------------------------------

    /** Result ordering, as comma-separated `field direction` clauses. */
    const val SORT: String = "sort"

    /** The field documents are grouped by. */
    const val GROUP_FIELD: String = "group.field"

    /** Ordering within a group. */
    const val GROUP_SORT: String = "group.sort"

    // --- the component families ---------------------------------------------------------------------

    /** A field to facet on. */
    const val FACET_FIELD: String = "facet.field"

    /** A pivot, every name in which is faceted on. */
    const val FACET_PIVOT: String = "facet.pivot"

    /** A field to compute statistics over. */
    const val STATS_FIELD: String = "stats.field"

    /** A field to highlight matches in. */
    const val HIGHLIGHT_FIELDS: String = "hl.fl"

    /** A field the terms component enumerates. */
    const val TERMS_FIELDS: String = "terms.fl"

    /** The fields more-like-this compares documents on. */
    const val MORE_LIKE_THIS_FIELDS: String = "mlt.fl"

    /**
     * Every parameter named here.
     *
     * Read reflectively rather than restated as a list, because a hand-maintained second copy is the
     * divergence this object exists to end — one that omitted a constant would let exactly the gap
     * this was built to close reappear inside the thing closing it.
     *
     * @return each parameter name, once
     */
    fun all(): List<String> = javaClass.declaredFields
        .filter { it.type == String::class.java }
        .mapNotNull { it.isAccessible = true; it.get(this) as? String }
}
