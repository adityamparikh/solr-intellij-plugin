package org.apache.solr.ide.server.reading

/**
 * The keys Solr's Schema API answers with.
 *
 * **Named because a misspelt key here fails silently, not because they repeat.** A JSON node's
 * `path` returns a missing node rather than raising, which is what lets the reader walk a response
 * without a guard at every step — and is also what makes `dynamicfields` for `dynamicFields` produce
 * a schema with no dynamic fields, from a server that reported plenty. Green tests, empty answer,
 * nothing to notice. That is this plugin's characteristic defect, and a constant is what turns it
 * into a compile error.
 *
 * **Deliberately not shared with `SolrSchemaTags`, which names the XML vocabulary.** The two look
 * alike and are not: a configset declares `<field>` and `<fieldType>` and `<copyField>`, while the
 * API answers with `fields`, `fieldTypes` and `copyFields`, and the XML's `<analyzer type="index">`
 * is the API's `indexAnalyzer`. A constant spanning both would have to hold two different strings.
 * The handful that genuinely coincide — `name`, `class`, `type` — are not worth a dependency from
 * `server` into `configset` to share four characters.
 */
internal object SolrSchemaJson {

    /** The object the whole schema hangs under, in a response that carries one. */
    const val SCHEMA: String = "schema"

    // --- the collections, all plural where the XML tags are singular --------------------------------

    /** The concrete field declarations. */
    const val FIELDS: String = "fields"

    /** The dynamic field patterns, each carrying its glob in [NAME]. */
    const val DYNAMIC_FIELDS: String = "dynamicFields"

    /** The field type declarations. */
    const val FIELD_TYPES: String = "fieldTypes"

    /** The copy rules, one entry per source-and-destination pair rather than one per source. */
    const val COPY_FIELDS: String = "copyFields"

    // --- what a declaration is called ---------------------------------------------------------------

    /** A declaration's name, and a dynamic field's pattern. */
    const val NAME: String = "name"

    /** The type a field names. */
    const val TYPE: String = "type"

    /** The implementing class a field type names, and one spelling of an analyzer component's. */
    const val CLASS: String = "class"

    /** The field a copy rule reads from. */
    const val SOURCE: String = "source"

    /** The field a copy rule writes to; always one name, never an array. */
    const val DESTINATION: String = "dest"

    /** How much of a copied value is kept. */
    const val MAX_CHARS: String = "maxChars"

    /** The field whose value identifies a document. */
    const val UNIQUE_KEY: String = "uniqueKey"

    /** The value a field takes when a document supplies none. */
    const val DEFAULT_VALUE: String = "default"

    // --- the analyzer chain -------------------------------------------------------------------------

    /**
     * The index-time chain.
     *
     * The API splits what the XML expresses as `<analyzer type="index">`, so these three keys have no
     * counterpart in the tag vocabulary at all.
     */
    const val INDEX_ANALYZER: String = "indexAnalyzer"

    /** The query-time chain. */
    const val QUERY_ANALYZER: String = "queryAnalyzer"

    /** A chain applying to both phases, which is what a type declares when it draws no distinction. */
    const val ANALYZER: String = "analyzer"

    /** The single tokenizer a chain uses. */
    const val TOKENIZER: String = "tokenizer"

    /** The token filters, in pipeline order. */
    const val FILTERS: String = "filters"

    /** The character filters, which run before the tokenizer. */
    const val CHAR_FILTERS: String = "charFilters"

    // --- the system-info response, which is a different endpoint ------------------------------------

    /** The object a system-info response reports versions under. */
    const val LUCENE: String = "lucene"

    /**
     * The running Solr version.
     *
     * Its neighbour `lucene-spec-version` is the *Lucene* version and a different number — Solr
     * 10.0.0 reports Lucene 10.3.2 — so the two are one word apart and both look like the answer.
     * Naming this one is the point of the constant.
     */
    const val SOLR_SPEC_VERSION: String = "solr-spec-version"
}
