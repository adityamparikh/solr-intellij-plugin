package org.apache.solr.ide.model

/**
 * Everything one source knows about a configset, before merging.
 *
 * Deliberately a flat bag of lists rather than a model: this is what a *parser* produces, and the
 * same shape serves both sources. The configset on disk and a live collection describe the same
 * things, so giving them one representation is what lets [SolrFieldModel.of] merge them without
 * either half being privileged.
 *
 * @property fields declared fields, in declaration order
 * @property dynamicFields dynamic-field declarations, in declaration order
 * @property fieldTypes field types, in declaration order
 * @property copyFields copy-field directives, in declaration order
 * @property uniqueKey the declared unique key, or null
 * @property fieldReferences field names referenced from `solrconfig.xml`; always empty for a server,
 *   which reports its configuration rather than the file that produced it
 * @property luceneMatchVersion the `<luceneMatchVersion>` the configset declares, which is what says
 *   which Solr line it targets — a *Lucene* version, not a Solr one
 */
data class SolrConfigsetFacts(
    val fields: List<SolrField> = emptyList(),
    val dynamicFields: List<SolrDynamicField> = emptyList(),
    val fieldTypes: List<SolrFieldType> = emptyList(),
    val copyFields: List<SolrCopyField> = emptyList(),
    val uniqueKey: String? = null,
    val fieldReferences: List<SolrFieldReference> = emptyList(),
    val luceneMatchVersion: String? = null,
) {
    /** True when no source declared anything at all. */
    val isEmpty: Boolean
        get() = fields.isEmpty() && dynamicFields.isEmpty() && fieldTypes.isEmpty() &&
            copyFields.isEmpty() && uniqueKey == null && fieldReferences.isEmpty() &&
            luceneMatchVersion == null

    /**
     * Combines two sets of facts from the same source.
     *
     * The repository half arrives in two pieces — the schema and `solrconfig.xml` are separate
     * files — and this joins them without either overwriting the other.
     *
     * @param other facts to add
     * @return the union
     */
    operator fun plus(other: SolrConfigsetFacts): SolrConfigsetFacts = SolrConfigsetFacts(
        fields = fields + other.fields,
        dynamicFields = dynamicFields + other.dynamicFields,
        fieldTypes = fieldTypes + other.fieldTypes,
        copyFields = copyFields + other.copyFields,
        uniqueKey = uniqueKey ?: other.uniqueKey,
        fieldReferences = fieldReferences + other.fieldReferences,
        luceneMatchVersion = luceneMatchVersion ?: other.luceneMatchVersion,
    )
}
