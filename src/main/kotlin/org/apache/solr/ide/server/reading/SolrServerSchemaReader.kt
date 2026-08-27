package org.apache.solr.ide.server.reading

import org.apache.solr.ide.model.SolrConfigsetFacts
import org.apache.solr.ide.model.schema.SolrAnalyzerChain
import org.apache.solr.ide.model.schema.SolrAnalyzerComponent
import org.apache.solr.ide.model.schema.SolrCopyField
import org.apache.solr.ide.model.schema.SolrDynamicField
import org.apache.solr.ide.model.schema.SolrField
import org.apache.solr.ide.model.schema.SolrFieldType
import tools.jackson.databind.JsonNode

/**
 * Reads a Solr Schema API response into the same facts a configset parser produces.
 *
 * **The second parser, and deliberately the same contract as the first.** `SolrSchemaParser` turns
 * schema XML into [SolrConfigsetFacts]; this turns the Schema API's JSON into the same type, so that
 * `SolrFieldModel.of(repository, server)` can merge two instances of one shape without either being
 * privileged. That symmetry is the specification's central design decision and the reason nothing
 * here invents a server-shaped model.
 *
 * Pure text in, facts out, with nothing from IntelliJ in its signature — which is what lets it be
 * tested against captured response bodies rather than a running server.
 *
 * **What it does not populate**, each for a reason argued in the specification: the schema version and
 * the Lucene match version, both of which are the repository's to state, and the field references,
 * which a server does not have because it reports its configuration rather than the file that produced
 * it.
 */
object SolrServerSchemaReader {

    /**
     * The facts in [body], or empty facts where it is not a schema response.
     *
     * @param body a response body from `GET /<collection>/schema`
     * @return the schema it describes; empty where the text is not JSON, or is JSON without a schema
     */
    fun read(body: String): SolrConfigsetFacts {
        val tree = SolrJsonDocuments.treeOf(body) ?: return SolrConfigsetFacts()
        return read(tree)
    }

    /**
     * The facts in an already-parsed [response].
     *
     * The form the transport uses. It parses a body to classify the outcome — a Solr error is found
     * by reading `error.msg` out of the same JSON — so handing the text back to be parsed a second
     * time would be wasteful and, worse, would let the two disagree about whether a body was readable
     * at all.
     *
     * @param response a parsed response from `GET /<collection>/schema`
     * @return the schema it describes; empty where the response carries no schema
     */
    fun read(response: JsonNode): SolrConfigsetFacts {
        // `path` returns a missing node rather than throwing, which is why every step below can be
        // written without a guard: a response carrying no schema walks to an empty array and yields
        // empty facts rather than failing.
        val schema = response.path(SolrSchemaJson.SCHEMA)
        return SolrConfigsetFacts(
            fields = schema.path(SolrSchemaJson.FIELDS).items().map { readField(it) },
            dynamicFields = schema.path(SolrSchemaJson.DYNAMIC_FIELDS).items().map { SolrDynamicField(it.path(SolrSchemaJson.NAME).asString(""), readField(it)) },
            fieldTypes = schema.path(SolrSchemaJson.FIELD_TYPES).items().map { readFieldType(it) },
            copyFields = schema.path(SolrSchemaJson.COPY_FIELDS).items().map {
                SolrCopyField(
                    it.path(SolrSchemaJson.SOURCE).asString(""),
                    it.path(SolrSchemaJson.DESTINATION).asString(""),
                    it.path(SolrSchemaJson.MAX_CHARS).takeIf { node -> node.isNumber }?.asInt(),
                )
            },
            uniqueKey = schema.stringOrNull(SolrSchemaJson.UNIQUE_KEY),
            // schemaVersion and luceneMatchVersion are deliberately absent; see the class comment.
        )
    }

    /**
     * The Solr version a system-info response reports, or null where it names none.
     *
     * **`solr-spec-version`, and its neighbour is a trap.** The same object carries
     * `lucene-spec-version`, which differs by one word and is a different number — Solr 10.0.0
     * reports Lucene 10.3.2. Reading the wrong one yields a major that happens to match today and
     * stops matching the day the lines diverge, which is a value the plugin would state confidently
     * and sourced from the wrong place. The two `-impl-version` keys beside them carry a build hash
     * and are not it either.
     *
     * @param response a parsed response from `GET /admin/info/system`
     * @return the running Solr version as reported, or null
     */
    fun solrVersionIn(response: JsonNode): String? =
        response.path(SolrSchemaJson.LUCENE).stringOrNull(SolrSchemaJson.SOLR_SPEC_VERSION)

    private fun readField(node: JsonNode): SolrField = SolrField(
        name = node.path(SolrSchemaJson.NAME).asString(""),
        type = node.path(SolrSchemaJson.TYPE).asString(""),
        indexed = node.booleanOrNull("indexed"),
        stored = node.booleanOrNull("stored"),
        docValues = node.booleanOrNull("docValues"),
        multiValued = node.booleanOrNull("multiValued"),
        required = node.booleanOrNull("required"),
        defaultValue = node.child(SolrSchemaJson.DEFAULT_VALUE)?.asString(),
        // Everything except the two the repository parser also excludes. The five flags above appear
        // here as well as in their typed properties, because `SolrSchemaParser` fills this map from
        // raw attribute text and a fact populated only one of the two ways would agree in its
        // properties and differ in its map.
        attributes = node.attributesExcept(SolrSchemaJson.NAME, SolrSchemaJson.TYPE),
    )

    private fun readFieldType(node: JsonNode): SolrFieldType = SolrFieldType(
        name = node.path(SolrSchemaJson.NAME).asString(""),
        className = node.path(SolrSchemaJson.CLASS).asString(""),
        attributes = node.attributesExcept(SolrSchemaJson.NAME, SolrSchemaJson.CLASS, SolrSchemaJson.INDEX_ANALYZER, SolrSchemaJson.QUERY_ANALYZER, SolrSchemaJson.ANALYZER),
        // An untyped `analyzer` applies to both phases, exactly as it does in the XML.
        indexAnalyzer = readAnalyzer(node, SolrSchemaJson.INDEX_ANALYZER),
        queryAnalyzer = readAnalyzer(node, SolrSchemaJson.QUERY_ANALYZER),
    )

    private fun readAnalyzer(type: JsonNode, key: String): SolrAnalyzerChain? {
        val node = type.child(key) ?: type.child(SolrSchemaJson.ANALYZER) ?: return null
        return SolrAnalyzerChain(
            charFilters = node.path(SolrSchemaJson.CHAR_FILTERS).items().map { readComponent(it) },
            tokenizer = node.child(SolrSchemaJson.TOKENIZER)?.let { readComponent(it) },
            filters = node.path(SolrSchemaJson.FILTERS).items().map { readComponent(it) },
            className = node.child(SolrSchemaJson.CLASS)?.asString(),
        )
    }

    /**
     * Reads a component under either spelling of its factory.
     *
     * Solr echoes back whichever the schema used and normalizes neither, so a reader accepting one is
     * blind to every configset written the other way — which is the defect the repository parser
     * shipped with until it was found by reading responses from a live server.
     */
    private fun readComponent(node: JsonNode): SolrAnalyzerComponent = SolrAnalyzerComponent(
        className = node.child(SolrSchemaJson.CLASS)?.asString() ?: node.path(SolrSchemaJson.NAME).asString(""),
        attributes = node.attributesExcept(SolrSchemaJson.CLASS, SolrSchemaJson.NAME),
    )

    /**
     * The elements of an array node, or nothing.
     *
     * `toList` rather than `map` on the node itself: Jackson 3's `JsonNode` declares a `map` of its
     * own that shadows the standard library's on `Iterable`, so `node.map { ... }` yields a single
     * value where a list was meant. It declares no `toList`, so this reaches the one that iterates.
     */
    private fun JsonNode.items(): List<JsonNode> = if (isArray) toList() else emptyList()

    /** The child at [field], or null where the node does not have one. */
    private fun JsonNode.child(field: String): JsonNode? = path(field).takeIf { !it.isMissingNode }

    /** The text at [field], or null where it is absent or empty. */
    private fun JsonNode.stringOrNull(field: String): String? =
        path(field).asString("").takeIf { it.isNotEmpty() }

    /** A JSON boolean, or null where the key is absent — absent meaning unset, never false. */
    private fun JsonNode.booleanOrNull(field: String): Boolean? =
        path(field).takeIf { it.isBoolean }?.asBoolean()

    /**
     * Every property except [excluded], rendered as text.
     *
     * Rendered rather than typed because the repository parser fills the same map from XML attribute
     * values, which are always text. A `true` here and a `"true"` there would compare as a difference
     * on two facts that agree.
     */
    private fun JsonNode.attributesExcept(vararg excluded: String): Map<String, String> =
        properties()
            .filter { (name, value) -> name !in excluded && value.isValueNode }
            .associate { (name, value) -> name to value.asString() }
}
