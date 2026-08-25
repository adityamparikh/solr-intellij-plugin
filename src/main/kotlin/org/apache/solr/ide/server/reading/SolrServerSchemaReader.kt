package org.apache.solr.ide.server.reading

import org.apache.solr.ide.model.SolrConfigsetFacts
import org.apache.solr.ide.model.schema.SolrAnalyzerChain
import org.apache.solr.ide.model.schema.SolrAnalyzerComponent
import org.apache.solr.ide.model.schema.SolrCopyField
import org.apache.solr.ide.model.schema.SolrDynamicField
import org.apache.solr.ide.model.schema.SolrField
import org.apache.solr.ide.model.schema.SolrFieldType
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

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
        // `path` returns a missing node rather than throwing, which is why every step below can be
        // written without a guard: a body that is not a schema response walks to an empty array and
        // yields empty facts. Reading a 404's HTML page is the case that makes this worth stating —
        // Solr answers a mistyped collection with a servlet error page, not with JSON.
        val schema = runCatching { MAPPER.readTree(body) }.getOrNull()?.path("schema") ?: return SolrConfigsetFacts()
        return SolrConfigsetFacts(
            fields = schema.path("fields").items().map { readField(it) },
            dynamicFields = schema.path("dynamicFields").items().map { SolrDynamicField(it.path("name").asString(""), readField(it)) },
            fieldTypes = schema.path("fieldTypes").items().map { readFieldType(it) },
            copyFields = schema.path("copyFields").items().map {
                SolrCopyField(
                    it.path("source").asString(""),
                    it.path("dest").asString(""),
                    it.path("maxChars").takeIf { node -> node.isNumber }?.asInt(),
                )
            },
            uniqueKey = schema.path("uniqueKey").asString("").takeIf { it.isNotEmpty() },
            // schemaVersion and luceneMatchVersion are deliberately absent; see the class comment.
        )
    }

    private fun readField(node: JsonNode): SolrField = SolrField(
        name = node.path("name").asString(""),
        type = node.path("type").asString(""),
        indexed = node.booleanOrNull("indexed"),
        stored = node.booleanOrNull("stored"),
        docValues = node.booleanOrNull("docValues"),
        multiValued = node.booleanOrNull("multiValued"),
        required = node.booleanOrNull("required"),
        defaultValue = node.path("default").takeIf { !it.isMissingNode }?.asString(),
        // Everything except the two the repository parser also excludes. The five flags above appear
        // here as well as in their typed properties, because `SolrSchemaParser` fills this map from
        // raw attribute text and a fact populated only one of the two ways would agree in its
        // properties and differ in its map.
        attributes = node.attributesExcept("name", "type"),
    )

    private fun readFieldType(node: JsonNode): SolrFieldType = SolrFieldType(
        name = node.path("name").asString(""),
        className = node.path("class").asString(""),
        attributes = node.attributesExcept("name", "class", "indexAnalyzer", "queryAnalyzer", "analyzer"),
        // An untyped `analyzer` applies to both phases, exactly as it does in the XML.
        indexAnalyzer = readAnalyzer(node, "indexAnalyzer"),
        queryAnalyzer = readAnalyzer(node, "queryAnalyzer"),
    )

    private fun readAnalyzer(type: JsonNode, key: String): SolrAnalyzerChain? {
        val node = type.path(key).takeIf { !it.isMissingNode } ?: type.path("analyzer").takeIf { !it.isMissingNode } ?: return null
        return SolrAnalyzerChain(
            charFilters = node.path("charFilters").items().map { readComponent(it) },
            tokenizer = node.path("tokenizer").takeIf { !it.isMissingNode }?.let { readComponent(it) },
            filters = node.path("filters").items().map { readComponent(it) },
            className = node.path("class").takeIf { !it.isMissingNode }?.asString(),
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
        className = node.path("class").takeIf { !it.isMissingNode }?.asString() ?: node.path("name").asString(""),
        attributes = node.attributesExcept("class", "name"),
    )

    /**
     * The elements of an array node, or nothing.
     *
     * Spelled out rather than using `map` directly on the node: Jackson 3's `JsonNode` carries a
     * `map` of its own that shadows the standard-library one on `Iterable`, and the result is a
     * single value where a list was meant. It compiles differently rather than failing at runtime,
     * which is the better direction, but only because the types happened to disagree.
     */
    private fun JsonNode.items(): List<JsonNode> {
        if (!isArray) return emptyList()
        val out = mutableListOf<JsonNode>()
        for (child in this) out.add(child)
        return out
    }

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

    private val MAPPER = JsonMapper.builder().build()
}
