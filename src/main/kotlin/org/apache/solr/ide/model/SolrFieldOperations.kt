package org.apache.solr.ide.model

/**
 * One thing a query can ask a field to do.
 *
 * Named for the query-side operation rather than for the property that enables it, because the
 * property is not the interesting part: every rule below is a *disjunction* over two or three
 * properties, and Solr's structures overlap. Searching wants an inverted index or, failing that, doc
 * values it can scan; faceting wants doc values or, failing those, an index it may un-invert. A type
 * named `IndexedOnly` would have to be renamed the first time one of those rules moved.
 *
 * **The operations a field supports, not the parameters that request them.** `qf` and `pf` both ask
 * for [SEARCH]; `fq` asks for [FILTER]; `facet.field`, `facet.pivot` and the JSON facet API all ask
 * for [FACET]. Mapping a parameter or an API call onto an operation belongs to the caller, and there
 * are three kinds of caller: the configuration surface reading `solrconfig.xml`, the code surface
 * reading a SolrJ call, and the query console completing a parameter a reader is typing.
 */
enum class SolrFieldOperation {

    /**
     * Matching and scoring against the field's terms, as `qf` and the phrase fields do.
     *
     * Wants an inverted index. Doc values will serve at a cost, which is why the rule is a
     * disjunction rather than a reading of `indexed`.
     */
    SEARCH,

    /** Restricting the result set without affecting the score, as `fq` does. */
    FILTER,

    /** Counting values, as `facet.field` and the JSON facet API do. */
    FACET,

    /** Ordering results, as `sort` does. */
    SORT,
}

/**
 * Whether a field can serve a query operation, and why it cannot when it cannot.
 *
 * **The single place these rules live**, because the configuration files are not their only reader.
 * A `facet.field` in `solrconfig.xml`, an `addFacetField` in SolrJ, and a facet parameter typed into
 * a query console are the same question asked from three surfaces, and the specification's promise is
 * one shared model of "what fields exist and what they can do". A rule kept inside an inspection
 * answers only the first, and three implementations of *is this field facetable* is three chances to
 * disagree where a reader expects agreement.
 *
 * **Every rule here is a disjunction, which no other property check in this plugin is.** Elsewhere a
 * property is resolved and compared — `indexed.value != "false"` — and that shape cannot express
 * "indexed *or* doc values". The facts are already present: [SolrFieldProperties.resolve] answers
 * `indexed`, `docValues`, `multiValued` and `uninvertible` through Solr's own three tiers. Only the
 * combining was missing.
 *
 * **Silence is the answer whenever a property is undetermined.** A field type whose class the catalog
 * has never seen leaves [SolrPropertyOrigin.UNDETERMINED] and a null value, and a rule that treated
 * null as false would invent a default for a custom type — the standing failure mode of asserting
 * something about what has not been read.
 */
object SolrFieldOperations {

    /**
     * Whether [field] can serve [operation], or null when the schema does not say.
     *
     * Null is not "no". It means at least one property this rule needs is undetermined — a custom
     * field type, most often — and the caller must stay silent rather than guess. Callers that offer
     * a completion list may include a null; callers that report a problem may not.
     *
     * @param operation the operation a query would ask of the field
     * @param field the field, or the dynamic field a name resolved to
     * @param fieldType the type backing it, or null when the schema declares none
     * @param schemaVersion the schema's declared version, which decides several defaults
     * @param typeTraits the type's traits, where the catalog knows them
     * @return true when the field can serve it, false when it cannot, null when the schema is silent
     */
    fun supports(
        operation: SolrFieldOperation,
        field: SolrField,
        fieldType: SolrFieldType?,
        schemaVersion: SolrSchemaVersion,
        typeTraits: Set<SolrTypeTrait>? = null,
    ): Boolean? {
        fun resolved(name: String): Boolean? {
            val property = SolrFieldProperties.byName(name) ?: return null
            return SolrFieldProperties
                .resolve(property, field, fieldType, schemaVersion, typeTraits)
                .value
                ?.let { it == "true" }
        }

        val indexed = resolved("indexed")
        val docValues = resolved("docValues")

        return when (operation) {
            // Solr turns an exact match on a doc-values-only field into a single-value range query
            // over the doc values rather than refusing it, so doc values alone are enough to make a
            // field searchable. What they cannot do is rank it: that query is constant-scoring, so a
            // boost multiplies a constant. Searchable and boostable, and not ranked by term
            // statistics — a distinction too fine for a warning, which is why this returns true.
            SolrFieldOperation.SEARCH, SolrFieldOperation.FILTER -> either(indexed, docValues)

            // Doc values are read directly. An indexed field without them can still be faceted or
            // sorted by un-inverting it into memory, which is what `uninvertible` governs — and it
            // defaults false from schema version 1.7, so an indexed-only field in a modern schema is
            // genuinely unfacetable.
            SolrFieldOperation.FACET, SolrFieldOperation.SORT ->
                either(docValues, both(indexed, resolved("uninvertible")))
        }
    }

    /**
     * Three-valued `or`: true wins over null, and null wins over false.
     *
     * A known-true side settles the disjunction whatever the other side is, so an undetermined
     * property next to a satisfied one is not a reason for silence. With nothing true and something
     * unknown the answer is unknown, which is what stops a custom field type from being reported.
     */
    private fun either(a: Boolean?, b: Boolean?): Boolean? = when {
        a == true || b == true -> true
        a == null || b == null -> null
        else -> false
    }

    /** Three-valued `and`: false wins over null, and null wins over true. */
    private fun both(a: Boolean?, b: Boolean?): Boolean? = when {
        a == false || b == false -> false
        a == null || b == null -> null
        else -> true
    }
}
