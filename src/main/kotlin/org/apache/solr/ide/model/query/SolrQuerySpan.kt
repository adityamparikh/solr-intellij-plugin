package org.apache.solr.ide.model.query

/**
 * What a query expression is made of, at the resolution a highlighter needs.
 *
 * **Offsets into the query rather than the text that contains it.** A query is read out of a string
 * literal, an XML attribute or an `.http` file, each of which puts it at a different offset behind a
 * different amount of quoting — so the caller that knows where the query starts is the one that
 * shifts these, and this stays a fact about the query alone.
 *
 * @property start where it begins in the query, inclusive
 * @property end where it ends, exclusive
 * @property kind what it is
 */
data class SolrQuerySpan(val start: Int, val end: Int, val kind: SolrQuerySpanKind)

/**
 * The parts of a query worth telling apart without parsing one.
 *
 * Deliberately short. Every kind added is a claim the scan has to be right about, and a wrong claim
 * shows as colour on text that means something else — which is worse than no colour at all.
 */
enum class SolrQuerySpanKind {

    /** A name before a colon, which is the field a clause searches. */
    FIELD,

    /** A boolean operator joining or negating clauses. */
    OPERATOR,
}
