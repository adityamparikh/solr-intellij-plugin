package org.apache.solr.ide.server.drift

import org.apache.solr.ide.model.SolrAgreement
import org.apache.solr.ide.model.SolrConfigsetFacts
import org.apache.solr.ide.model.SolrFact
import org.apache.solr.ide.model.SolrFieldModel
import org.apache.solr.ide.model.schema.SolrCopyField
import org.apache.solr.ide.model.schema.SolrField
import org.apache.solr.ide.model.schema.SolrFieldType

/** What kind of declaration a drift entry is about. */
enum class SolrDriftKind {

    /** A field declared by name. */
    FIELD,

    /** A dynamic pattern. */
    DYNAMIC_FIELD,

    /** A field type. */
    FIELD_TYPE,

    /** A copy-field directive. */
    COPY_FIELD,

    /** The schema's unique key. */
    UNIQUE_KEY,
}

/**
 * One declaration, and how the configset and the server disagree about it.
 *
 * **Both sides are rendered and kept**, never resolved to one. `SolrFact.effective` silently prefers
 * the repository — it exists for the inline surfaces that have one line to fill — so using it here
 * would hide the disagreement in the one view whose entire purpose is showing it.
 *
 * @property kind what sort of declaration this is
 * @property name the declaration's name, as both sources call it
 * @property agreement how the two sources relate
 * @property repository the configset's version rendered for display, or null where it declares none
 * @property server the server's version rendered for display, or null where it has none
 * @property change the Schema API request that would close this difference, or null where the API
 *   has nothing to say about it. Present on rows this plugin declines to apply as well as on the
 *   ones it will — reading what *would* be sent is the point, and a refusal that showed nothing
 *   would be the disabled button with a tooltip this design rejects
 */
data class SolrDriftEntry(
    val kind: SolrDriftKind,
    val name: String,
    val agreement: SolrAgreement,
    val repository: String? = null,
    val server: String? = null,
    val change: SolrSchemaApiChange? = null,
)

/**
 * What a configset and a collection do and do not agree about.
 *
 * **Produced only from two halves that both exist**, which is why [between] takes them as separate
 * arguments rather than taking a model. A `SolrFieldModel` built with no server half reports every
 * fact as `REPOSITORY_ONLY`, and that is indistinguishable from a server that genuinely has none of
 * them — so a comparison handed such a model would confidently report an entire schema as
 * undeployed on a project that has never been connected to anything. Requiring both halves makes
 * that mistake unspeakable rather than merely discouraged.
 *
 * @property entries every declaration the two sources disagree about, in a stable order
 * @property agreeingCount how many they agree about, which is not shown but is worth saying
 */
data class SolrDrift(
    val entries: List<SolrDriftEntry> = emptyList(),
    val agreeingCount: Int = 0,
) {
    /** Whether the configset and the collection say the same thing. */
    val isClean: Boolean get() = entries.isEmpty()

    /** How many entries fall in each category, for a summary that does not require counting rows. */
    val countsByAgreement: Map<SolrAgreement, Int>
        get() = entries.groupingBy { it.agreement }.eachCount()

    /** How this comparison is made. */
    companion object {

        /**
         * Compares [repository] against [server].
         *
         * The agreement itself comes from `SolrFact` exactly as the model already computes it — this
         * adds no new agreement state and no per-property diff. What it adds is a rendering of each
         * side and an order to show them in.
         *
         * @param repository the facts parsed from the configset on disk
         * @param server the facts read from a live collection
         * @return what the two disagree about
         */
        fun between(repository: SolrConfigsetFacts, server: SolrConfigsetFacts): SolrDrift {
            val model = SolrFieldModel.of(repository, server)
            val entries = buildList {
                addAll(model.fields.map { (name, fact) -> entryFor(SolrDriftKind.FIELD, name, fact, ::describeField) })
                addAll(
                    model.dynamicFields.map { (name, fact) ->
                        entryFor(SolrDriftKind.DYNAMIC_FIELD, name, fact) { describeField(it.field) }
                    },
                )
                addAll(model.fieldTypes.map { (name, fact) -> entryFor(SolrDriftKind.FIELD_TYPE, name, fact, ::describeType) })
                addAll(
                    model.copyFields.map { fact ->
                        val copy = fact.repository ?: fact.server
                        entryFor(SolrDriftKind.COPY_FIELD, "${copy?.source} → ${copy?.destination}", fact, ::describeCopy)
                    },
                )
                model.uniqueKey?.let { add(entryFor(SolrDriftKind.UNIQUE_KEY, "uniqueKey", it) { it }) }
            }
            return SolrDrift(
                // Agreeing declarations are counted and dropped: a schema of two hundred fields
                // where three drifted should show three rows, not two hundred with three worth
                // reading. The count is what says the comparison actually ran.
                entries = entries.filterNot { it.agreement == SolrAgreement.AGREEING }
                    .sortedWith(compareBy({ it.kind }, { it.name })),
                agreeingCount = entries.count { it.agreement == SolrAgreement.AGREEING },
            )
        }

        private fun <T : Any> entryFor(
            kind: SolrDriftKind,
            name: String,
            fact: SolrFact<T>,
            describe: (T) -> String,
        ) = SolrDriftEntry(
            kind = kind,
            name = name,
            // Read from the model rather than recomputed, so there is one definition of what
            // agreement means and this view cannot drift from the rest of the plugin about it.
            agreement = fact.agreement,
            repository = fact.repository?.let(describe),
            server = fact.server?.let(describe),
            change = SolrSchemaApi.changeFor(fact.agreement, fact.repository, fact.server),
        )

        private fun describeField(field: SolrField): String = buildList {
            add("type=${field.type}")
            field.indexed?.let { add("indexed=$it") }
            field.stored?.let { add("stored=$it") }
            field.docValues?.let { add("docValues=$it") }
            field.multiValued?.let { add("multiValued=$it") }
            field.required?.let { add("required=$it") }
            field.defaultValue?.let { add("default=$it") }
        }.joinToString(" ")

        private fun describeType(type: SolrFieldType): String = buildList {
            add("class=${type.className}")
            // Sorted, because a map's order is not a fact about the schema and two identical types
            // rendering differently would read as a difference where there is none.
            type.attributes.toSortedMap().forEach { (key, value) -> add("$key=$value") }
        }.joinToString(" ")

        private fun describeCopy(copy: SolrCopyField): String =
            copy.maxChars?.let { "maxChars=$it" } ?: "declared"
    }
}
