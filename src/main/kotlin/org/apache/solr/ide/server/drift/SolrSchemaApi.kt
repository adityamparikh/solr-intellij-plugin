package org.apache.solr.ide.server.drift

import org.apache.solr.ide.model.SolrAgreement
import org.apache.solr.ide.model.schema.SolrCopyField
import org.apache.solr.ide.model.schema.SolrDynamicField
import org.apache.solr.ide.model.schema.SolrField
import org.apache.solr.ide.model.schema.SolrFieldType
import tools.jackson.databind.json.JsonMapper

/**
 * The Schema API request a drift row maps onto, and whether this plugin will send it.
 *
 * **Every row carries one; only some carry a button.** A disabled control with a tooltip is a weak
 * answer to "why can this not be applied"; showing the request that *would* be needed, in Solr's own
 * vocabulary, next to the reason it is not offered, turns the asymmetry from an omission into
 * information — and leaves the user able to run it themselves against a collection they are prepared
 * to reindex, which is their decision and not this plugin's to prevent.
 *
 * @property command the Schema API command name
 * @property body the command's arguments, kept structured rather than re-parsed out of [payload] —
 *   several changes are sent as one request, and rebuilding that by cutting up formatted text would
 *   be a parser written against this class's own output
 * @property payload the whole request as it would be sent alone, formatted to be read first
 * @property applicable whether this plugin will send it
 * @property declined why it is not offered, where it is not — never null when [applicable] is false
 */
data class SolrSchemaApiChange(
    val command: String,
    val body: Map<String, Any?>,
    val payload: String,
    val applicable: Boolean,
    val declined: String? = null,
)

/**
 * Turning a difference into the Schema API request that would close it.
 *
 * **Only additive commands are offered, and the reason is Solr's behaviour rather than caution.**
 * `add-field`, `add-field-type`, `add-dynamic-field` and `add-copy-field` are safe in the sense that
 * matters: a document already indexed simply lacks the new thing, and nothing already written
 * becomes wrong.
 *
 * **A change to an existing field is never offered**, and that rule protects the user from Solr
 * rather than from this plugin. Verified against Solr 10.0.0: `replace-field` changing a field from
 * `string` to `pint` returns `responseHeader.status` 0 with no error, on a field whose indexed
 * documents hold `"abc"`. Afterwards a query for the value that *is* there matches nothing, a query
 * for the old text answers HTTP 400 `Invalid Number`, and merely asking for the field in `fl` fails
 * the whole request with **HTTP 500** — for every document, including the ones that never had the
 * field. Only a reindex makes the schema true again, and this plugin cannot do that and must not
 * imply it can.
 */
object SolrSchemaApi {

    /** Why a change to an existing declaration is shown and not offered. */
    const val REINDEX_REQUIRED: String =
        "Solr accepts this and reports success, while every document already indexed keeps the " +
            "encoding it was written with — after which queries on the field return wrong results " +
            "or fail outright. Only a reindex makes it true, which this plugin cannot do for you."

    /** Why removing something from a server is shown and not offered. */
    const val REMOVAL_NOT_OFFERED: String =
        "Removing a declaration from a live collection is not additive: documents already indexed " +
            "under it stay in the index. Add it to the configset instead, or run this yourself."

    /**
     * The request that would close the difference [agreement] describes, or null where none exists.
     *
     * @param agreement how the two sources relate
     * @param repository the configset's version, where it has one
     * @param server the server's version, where it has one
     * @return the request to show, or null where the Schema API has nothing to say about it
     */
    fun changeFor(agreement: SolrAgreement, repository: Any?, server: Any?): SolrSchemaApiChange? = when (agreement) {
        SolrAgreement.REPOSITORY_ONLY -> repository?.let { additiveFor(it) }
        // Shown so the user can read what would be needed, and read why it is not offered beside it.
        SolrAgreement.DISAGREEING -> repository?.let { replacementFor(it) }
        SolrAgreement.SERVER_ONLY -> server?.let { removalFor(it) }
        // Nothing to close.
        SolrAgreement.AGREEING -> null
    }

    private fun additiveFor(declaration: Any): SolrSchemaApiChange? = when (declaration) {
        is SolrField -> change("add-field", fieldBody(declaration), applicable = true)
        is SolrDynamicField ->
            change("add-dynamic-field", fieldBody(declaration.field, name = declaration.pattern), applicable = true)
        // Additive by the same test the others pass — a type nothing uses cannot invalidate a
        // document. It is also required for the others to work: `add-field` naming a type the server
        // does not have is refused with "Field type 'x' not found", verified, so a run that offered
        // the field and not its type would fail on a difference it had just claimed it could close.
        is SolrFieldType -> change("add-field-type", typeBody(declaration), applicable = true)
        is SolrCopyField -> change("add-copy-field", copyBody(declaration), applicable = true)
        else -> null
    }

    private fun replacementFor(declaration: Any): SolrSchemaApiChange? = when (declaration) {
        is SolrField -> change("replace-field", fieldBody(declaration), applicable = false, REINDEX_REQUIRED)
        is SolrDynamicField -> change(
            "replace-dynamic-field",
            fieldBody(declaration.field, name = declaration.pattern),
            applicable = false,
            REINDEX_REQUIRED,
        )
        is SolrFieldType -> change("replace-field-type", typeBody(declaration), applicable = false, REINDEX_REQUIRED)
        // A copy rule has no `replace`; Solr's own vocabulary is delete then add, and neither half
        // is additive on its own.
        is SolrCopyField -> null
        else -> null
    }

    private fun removalFor(declaration: Any): SolrSchemaApiChange? = when (declaration) {
        is SolrField -> change("delete-field", mapOf("name" to declaration.name), false, REMOVAL_NOT_OFFERED)
        is SolrDynamicField ->
            change("delete-dynamic-field", mapOf("name" to declaration.pattern), false, REMOVAL_NOT_OFFERED)
        is SolrFieldType ->
            change("delete-field-type", mapOf("name" to declaration.name), false, REMOVAL_NOT_OFFERED)
        is SolrCopyField -> change("delete-copy-field", copyBody(declaration), false, REMOVAL_NOT_OFFERED)
        else -> null
    }

    /**
     * Several changes as one request body.
     *
     * **Sent together rather than one at a time, and the order is the point.** Solr applies the
     * commands in a single request in the order given — verified — so a new field type and a field
     * using it succeed together where the field alone would be refused. Sending them separately
     * would also mean a run that failed halfway leaves the server in a state neither source
     * describes.
     *
     * @param changes the changes to send, in the order they should be applied
     * @return the request body, or null where nothing is applicable
     */
    fun requestFor(changes: List<SolrSchemaApiChange>): String? {
        val applicable = changes.filter { it.applicable }
        if (applicable.isEmpty()) return null
        // Types first, so a field naming a type introduced in the same request finds it.
        val ordered = applicable.sortedBy { orderOf(it.command) }
        // Each command's arguments go in an array, always — Solr reads one that way as readily as
        // several, and a single object would mean two shapes to build and two to get right.
        val document = LinkedHashMap<String, Any?>()
        ordered.forEach { change ->
            @Suppress("UNCHECKED_CAST")
            val existing = document.getOrPut(change.command) { mutableListOf<Map<String, Any?>>() }
                as MutableList<Map<String, Any?>>
            existing += change.body
        }
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(document)
    }

    private fun orderOf(command: String): Int =
        ORDER.indexOf(command).takeIf { it >= 0 } ?: ORDER.size

    private fun change(
        command: String,
        body: Map<String, Any?>,
        applicable: Boolean,
        declined: String? = null,
    ) = SolrSchemaApiChange(
        command = command,
        body = body,
        payload = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(mapOf(command to body)),
        applicable = applicable,
        declined = declined,
    )

    private fun fieldBody(field: SolrField, name: String = field.name): Map<String, Any?> = buildMap {
        put("name", name)
        put("type", field.type)
        field.indexed?.let { put("indexed", it) }
        field.stored?.let { put("stored", it) }
        field.docValues?.let { put("docValues", it) }
        field.multiValued?.let { put("multiValued", it) }
        field.required?.let { put("required", it) }
        field.defaultValue?.let { put("default", it) }
    }

    private fun typeBody(type: SolrFieldType): Map<String, Any?> = buildMap {
        put("name", type.name)
        put("class", type.className)
        // Sorted so the same type always renders the same way; a map's order is not a fact about
        // the schema, and a payload a user is asked to read should not shuffle between viewings.
        type.attributes.toSortedMap().forEach { (key, value) -> put(key, value) }
    }

    private fun copyBody(copy: SolrCopyField): Map<String, Any?> = buildMap {
        put("source", copy.source)
        // Solr's own spelling in this command is `dest`, not `destination`.
        put("dest", copy.destination)
        copy.maxChars?.let { put("maxChars", it) }
    }

    private val ORDER = listOf("add-field-type", "add-field", "add-dynamic-field", "add-copy-field")

    private val MAPPER: JsonMapper = JsonMapper.builder().build()
}
