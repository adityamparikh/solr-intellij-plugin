package org.apache.solr.ide.code.solrj

import org.apache.solr.ide.model.query.SolrParameters

/**
 * What kind of text a `SolrQuery` argument holds.
 *
 * Three shapes, and reading one as another is how a recognizer reports a field nobody wrote:
 * `addFilterQuery("categry:books")` read as a field list yields a field called `categry:books`.
 */
enum class SolrJArgumentShape {

    /** A Solr query, where a name appears only before a colon — `q` and `fq`. */
    QUERY_EXPRESSION,

    /** One or more names separated by commas or whitespace, as `fl` is written. */
    FIELD_LIST,

    /** The whole argument is one field name, punctuation and all. */
    FIELD_NAME,
}

/**
 * A `SolrQuery` method that names fields, and how to read its arguments.
 *
 * @property parameter the Solr request parameter this method writes, which is what decides how the
 *   value is parsed and what the field is being asked to do
 * @property shape what kind of text the argument holds
 * @property readsOnlyFirstArgument true where the field is argument zero and the rest are something
 *   else — `setSort(field, ORDER)` being the case that exists
 */
data class SolrJQueryMethod(
    val parameter: String,
    val shape: SolrJArgumentShape,
    val readsOnlyFirstArgument: Boolean = false,
)

/**
 * The `SolrQuery` builder calls that carry field names.
 *
 * A recognizer consults this before reading anything: a call is worth looking inside only when its
 * method is here, and what to do with the string depends on which method it was. Everything absent
 * is absent deliberately — `setRows` takes a count and `setHighlight` a boolean, and nothing about a
 * field-shaped method name makes its argument a field.
 *
 * **Names rather than resolved signatures.** The recognizer confirms the receiver's type separately;
 * this answers only what a method called on that type means. That keeps the map a plain function
 * from a string, testable with no fixture, and keeps the type check in the one place that has a
 * `UCallExpression` to ask.
 */
object SolrJQueryMethods {

    /**
     * The mapping for [methodName], or null where the method does not name fields.
     *
     * @param methodName the called method's simple name
     * @return what the method writes and how to read it, or null to read nothing
     */
    fun forMethod(methodName: String): SolrJQueryMethod? = METHODS[methodName]

    /**
     * Whether [qualifiedName] is SolrJ's `SolrQuery`, under either package it has shipped in.
     *
     * @param qualifiedName the receiver type's fully qualified name
     * @return true if this is the SolrJ class rather than something else of the same simple name
     */
    fun isSolrQueryClass(qualifiedName: String): Boolean = qualifiedName in SOLR_QUERY_CLASSES

    /**
     * Every Solr parameter this map can produce.
     *
     * Exposed so a test can hold the code track and the grammar to the same vocabulary — a parameter
     * produced here and unknown there resolves to no operation, which reads as "this field is fine".
     *
     * @return the parameter names, once each
     */
    fun allParameters(): Set<String> = METHODS.values.mapTo(mutableSetOf()) { it.parameter }

    /**
     * Every package `SolrQuery` has shipped in across the supported lines.
     *
     * **Two entries because the class moved, and a recognizer holding one is silent on the other.**
     * Solr 9 has `org.apache.solr.client.solrj.SolrQuery`; Solr 10 moved it to
     * `...solrj.request.SolrQuery` and left no deprecated shim at the old location. Matched in full
     * rather than by simple name, because a project's own `SolrQuery` is not this one and reading it
     * would produce warnings on code that has nothing to do with Solr.
     *
     * A new line moving it again is a maintenance trigger for this set. The failure that would cause
     * is silence, which is why it is worth a test naming both spellings rather than a comment.
     */
    private val SOLR_QUERY_CLASSES = setOf(
        "org.apache.solr.client.solrj.SolrQuery",
        "org.apache.solr.client.solrj.request.SolrQuery",
    )

    /**
     * The methods that name fields, read from SolrJ's own source rather than recalled.
     *
     * The parameter each writes is what decides how its value is parsed, so the shape travels with
     * it. `fl` appears twice under different shapes deliberately: `setFields` joins its arguments
     * into a comma-separated list and each may itself hold several names, while `addField` adds
     * exactly one and must not be split.
     */
    private val METHODS = mapOf(
        // The query itself, and filters. Both hold Solr query syntax, where a field name appears
        // only as the part before a colon.
        "setQuery" to SolrJQueryMethod(SolrParameters.QUERY, SolrJArgumentShape.QUERY_EXPRESSION),
        "addFilterQuery" to SolrJQueryMethod(SolrParameters.FILTER_QUERY, SolrJArgumentShape.QUERY_EXPRESSION),
        "setFilterQueries" to SolrJQueryMethod(SolrParameters.FILTER_QUERY, SolrJArgumentShape.QUERY_EXPRESSION),

        // The field list. `setFields` writes the whole `fl`; `addField` appends one name to it.
        "setFields" to SolrJQueryMethod(SolrParameters.FIELD_LIST, SolrJArgumentShape.FIELD_LIST),
        "addField" to SolrJQueryMethod(SolrParameters.FIELD_LIST, SolrJArgumentShape.FIELD_NAME),

        // Faceting. Each argument is one field, so a comma inside one is part of the name.
        "addFacetField" to SolrJQueryMethod(SolrParameters.FACET_FIELD, SolrJArgumentShape.FIELD_NAME),
        "addFacetPivotField" to SolrJQueryMethod(SolrParameters.FACET_PIVOT, SolrJArgumentShape.FIELD_NAME),

        // Highlighting, terms and more-like-this, each under its own parameter so the operation the
        // field is asked to support is the right one.
        "addHighlightField" to SolrJQueryMethod(SolrParameters.HIGHLIGHT_FIELDS, SolrJArgumentShape.FIELD_NAME),
        "addTermsField" to SolrJQueryMethod(SolrParameters.TERMS_FIELDS, SolrJArgumentShape.FIELD_NAME),
        "setMoreLikeThisFields" to SolrJQueryMethod(SolrParameters.MORE_LIKE_THIS_FIELDS, SolrJArgumentShape.FIELD_NAME),

        // Sorting, the one family whose field is a separate argument rather than part of a clause:
        // `setSort(String field, ORDER order)`. Reading every argument would read the enum as a name.
        "setSort" to SolrJQueryMethod(SolrParameters.SORT, SolrJArgumentShape.FIELD_NAME, readsOnlyFirstArgument = true),
        "addSort" to SolrJQueryMethod(SolrParameters.SORT, SolrJArgumentShape.FIELD_NAME, readsOnlyFirstArgument = true),
    )
}
