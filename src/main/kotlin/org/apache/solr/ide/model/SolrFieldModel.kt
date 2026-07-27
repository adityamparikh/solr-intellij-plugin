package org.apache.solr.ide.model

/**
 * Where a fact in the model came from, and whether its two sources agree.
 *
 * The plugin reads the same field from two places that can disagree: the configset in the
 * repository, and the collection running on a server. Drift between them is a real and common
 * failure — a field added to the schema but never deployed, or added through the Schema API and
 * never committed — so the model records the disagreement rather than picking a winner.
 */
enum class SolrAgreement {

    /** Declared in the configset, absent from the server. Typically not yet deployed. */
    REPOSITORY_ONLY,

    /** Present on the server, absent from the configset. Typically added through the Schema API. */
    SERVER_ONLY,

    /** Present in both, and identical. */
    AGREEING,

    /** Present in both, and different. The case worth showing the user. */
    DISAGREEING,
}

/**
 * One fact, as known from each of the two sources.
 *
 * Both halves are nullable and at least one is always set — a fact known to neither source is not a
 * fact the model holds.
 *
 * @param T the kind of fact: a field, a field type, a copy-field directive
 * @property repository the configset's version, or null if the configset does not declare it
 * @property server the server's version, or null if the server does not have it
 */
data class SolrFact<T : Any>(val repository: T? = null, val server: T? = null) {

    init {
        require(repository != null || server != null) { "a fact must be known to at least one source" }
    }

    /** How the two sources relate. */
    val agreement: SolrAgreement
        get() = when {
            repository != null && server == null -> SolrAgreement.REPOSITORY_ONLY
            repository == null && server != null -> SolrAgreement.SERVER_ONLY
            repository == server -> SolrAgreement.AGREEING
            else -> SolrAgreement.DISAGREEING
        }

    /**
     * The version to use when only one can be shown.
     *
     * The repository wins, because the editor's job is to reason about the file in front of the
     * user. Where the two differ, that difference is surfaced through [agreement] rather than by
     * quietly substituting the server's answer for the one the user is editing.
     */
    val effective: T get() = repository ?: requireNotNull(server)
}

/**
 * Everything the plugin knows about one configset's fields, merged from the repository and,
 * eventually, from a server.
 *
 * **The server half is empty today.** The server reader has not landed, so every fact currently
 * reports [SolrAgreement.REPOSITORY_ONLY]. The seam exists now rather than later because retrofitting
 * a second source into a model shaped around one means revisiting everything built on it.
 *
 * @property fields declared fields, by name
 * @property dynamicFields dynamic-field declarations, by pattern
 * @property fieldTypes field types, by name
 * @property copyFields copy-field directives, keyed by source and destination together
 * @property uniqueKey the `uniqueKey` declaration, or null if the configset declares none
 * @property fieldReferences field names referenced from `solrconfig.xml`
 * @property luceneMatchVersion the version the configset declares it targets, or null
 */
class SolrFieldModel(
    val fields: Map<String, SolrFact<SolrField>> = emptyMap(),
    val dynamicFields: Map<String, SolrFact<SolrDynamicField>> = emptyMap(),
    val fieldTypes: Map<String, SolrFact<SolrFieldType>> = emptyMap(),
    val copyFields: List<SolrFact<SolrCopyField>> = emptyList(),
    val uniqueKey: SolrFact<String>? = null,
    val fieldReferences: List<SolrFieldReference> = emptyList(),
    val luceneMatchVersion: String? = null,
) {

    /**
     * The field type backing [field], or null if the type it names is not declared.
     *
     * A null return is a dangling reference, not an internal error: a schema may name a type that
     * does not exist, and saying so is one of the inspections this model is built for.
     *
     * @param field the field whose type is wanted
     * @return the type, or null if undeclared
     */
    fun typeOf(field: SolrField): SolrFieldType? = fieldTypes[field.type]?.effective

    /**
     * The declaration supplying [fieldName], whether declared outright or matched dynamically.
     *
     * Declared fields win over dynamic ones, and among competing dynamic patterns the longest
     * literal part wins — Solr's own rule, so that `text_body` takes `*_body` rather than `*`.
     *
     * @param fieldName a concrete field name, as a document or a query would use it
     * @return the field supplying it, or null if nothing does
     */
    fun resolve(fieldName: String): SolrField? {
        fields[fieldName]?.let { return it.effective }
        return dynamicFields.values
            .map { it.effective }
            .filter { it.matches(fieldName) }
            .maxByOrNull { it.specificity }
            ?.field
    }

    /**
     * Copy-field directives whose source names [fieldName], including through a dynamic pattern.
     *
     * @param fieldName the source field name
     * @return every directive copying from it
     */
    fun copyFieldsFrom(fieldName: String): List<SolrCopyField> =
        copyFields.map { it.effective }.filter { it.source == fieldName || globMatches(it.source, fieldName) }

    /** Facts whose two sources disagree — the drift a user needs to see. */
    val disagreements: List<SolrFact<*>>
        get() = (fields.values + dynamicFields.values + fieldTypes.values + copyFields)
            .filter { it.agreement == SolrAgreement.DISAGREEING }

    private fun globMatches(pattern: String, name: String): Boolean = when {
        pattern == "*" -> true
        pattern.startsWith("*") -> name.endsWith(pattern.substring(1))
        pattern.endsWith("*") -> name.startsWith(pattern.dropLast(1))
        else -> false
    }

    /** Construction from the two halves. */
    companion object {

        /**
         * Merges a repository half and a server half into one model.
         *
         * Either may be null. With only a repository the model is complete but every fact reports
         * [SolrAgreement.REPOSITORY_ONLY], which is the state the plugin is in until a connection
         * exists — and a perfectly useful state, since the editing features never needed a server.
         *
         * @param repository facts parsed from the configset on disk
         * @param server facts read from a live collection, or null when there is no connection
         * @return the merged model
         */
        fun of(repository: SolrConfigsetFacts?, server: SolrConfigsetFacts? = null): SolrFieldModel {
            val empty = SolrConfigsetFacts()
            val repo = repository ?: empty
            val srv = server ?: empty
            return SolrFieldModel(
                fields = merge(repo.fields.associateBy { it.name }, srv.fields.associateBy { it.name }),
                dynamicFields = merge(
                    repo.dynamicFields.associateBy { it.pattern },
                    srv.dynamicFields.associateBy { it.pattern },
                ),
                fieldTypes = merge(repo.fieldTypes.associateBy { it.name }, srv.fieldTypes.associateBy { it.name }),
                copyFields = merge(
                    repo.copyFields.associateBy { it.source to it.destination },
                    srv.copyFields.associateBy { it.source to it.destination },
                ).values.toList(),
                uniqueKey = if (repo.uniqueKey == null && srv.uniqueKey == null) {
                    null
                } else {
                    SolrFact(repo.uniqueKey, srv.uniqueKey)
                },
                fieldReferences = repo.fieldReferences,
                luceneMatchVersion = repo.luceneMatchVersion,
            )
        }

        private fun <K, T : Any> merge(repository: Map<K, T>, server: Map<K, T>): Map<K, SolrFact<T>> =
            (repository.keys + server.keys).associateWith { SolrFact(repository[it], server[it]) }
    }
}
