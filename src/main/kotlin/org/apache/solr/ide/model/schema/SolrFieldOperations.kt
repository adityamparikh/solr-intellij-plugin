package org.apache.solr.ide.model.schema

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

    /**
     * Comparing one document's content against others, as `mlt.fl` does.
     *
     * **The one operation here that asks for content rather than for an index.** Lucene's
     * `MoreLikeThis.retrieveTerms` asks each field for a term vector and, where there is none, falls
     * back to the stored value and re-analyses it — so what it needs is a copy of what the document
     * said, by either route. A field can be thoroughly searchable and have nothing it can read: an
     * index holds terms, not values.
     */
    SIMILARITY,
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
        // Solr reads these with `Boolean.parseBoolean`, which ignores case — so `indexed="TRUE"` is a
        // field that works, and a case-sensitive comparison here resolved it to a definite *false* and
        // underlined a correct file. The plugin already agreed with Solr on the other side of this:
        // the invalid-value inspection accepts `TRUE` because `SolrValueType.BOOLEAN` matches
        // ignoring case, so one rule blessed the spelling that another misread.
        //
        // Anything that is neither spelling resolves to null rather than to false. Solr would read it
        // as false, but a value this table cannot recognise is one the schema has not clearly stated,
        // and reporting on it would be guessing — which is the whole reason null is not "no" above.
        // The invalid-value inspection is what tells the reader the value is wrong.
        fun resolved(property: SolrFieldProperty): Boolean? = SolrFieldProperties
            .resolve(property, field, fieldType, schemaVersion, typeTraits)
            .value
            ?.let {
                when {
                    it.equals("true", ignoreCase = true) -> true
                    it.equals("false", ignoreCase = true) -> false
                    else -> null
                }
            }

        val indexed = resolved(INDEXED)
        val docValues = resolved(DOC_VALUES)

        // What faceting and sorting both need: values readable per document, either straight from doc
        // values or by un-inverting the index. Named once because sorting is this plus one condition,
        // and writing the disjunction twice is how the two rules come to disagree about one field.
        val perDocument by lazy { either(docValues, both(indexed, resolved(UNINVERTIBLE))) }

        return when (operation) {
            // Solr turns an exact match on a doc-values-only field into a single-value range query
            // over the doc values rather than refusing it, so doc values alone are enough to make a
            // field searchable. What they cannot do is rank it: that query is constant-scoring, so a
            // boost multiplies a constant. Searchable and boostable, and not ranked by term
            // statistics — a distinction too fine for a warning, which is why this returns true.
            SolrFieldOperation.SEARCH, SolrFieldOperation.FILTER -> either(indexed, docValues)

            // Doc values are read directly. An indexed field without them can still be faceted by
            // un-inverting it into memory, which is what `uninvertible` governs — and it defaults
            // false from schema version 1.7, so an indexed-only field in a modern schema is genuinely
            // unfacetable.
            SolrFieldOperation.FACET -> perDocument

            // Sorting wants the same structure and one more thing: a single value per document. A
            // multiValued field has no defined order, so Solr rejects a plain sort on one and requires
            // a selector — `sort=field(prices,min) asc` — which is a different expression rather than a
            // bare field name, and not what a bare name in a `sort` is asking for.
            SolrFieldOperation.SORT -> both(perDocument, resolved(MULTI_VALUED)?.not())

            // Content, by either route Lucene will look: a term vector, or the stored value it
            // re-analyses when there is none. Deliberately says nothing about `indexed` — a field
            // with content and no index is a configuration this cannot call wrong from here, and the
            // operation it would then fail is `SEARCH`, which is reported separately.
            SolrFieldOperation.SIMILARITY -> either(resolved(TERM_VECTORS), resolved(STORED))
        }
    }

    /**
     * The properties these rules combine, looked up once rather than by name per call.
     *
     * `requireNotNull` because a name absent from the property table is a defect in this file rather
     * than a fact about a schema, and resolving it to null would turn that defect into permanent
     * silence — the one failure a table-driven rule can have and never show.
     */
    private val INDEXED = property("indexed")
    private val STORED = property("stored")
    private val TERM_VECTORS = property("termVectors")
    private val DOC_VALUES = property("docValues")
    private val UNINVERTIBLE = property("uninvertible")
    private val MULTI_VALUED = property("multiValued")

    private fun property(name: String): SolrFieldProperty =
        requireNotNull(SolrFieldProperties.byName(name)) { "the property table must carry '$name'" }

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
