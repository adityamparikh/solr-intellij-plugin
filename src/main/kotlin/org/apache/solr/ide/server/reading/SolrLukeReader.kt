package org.apache.solr.ide.server.reading

import tools.jackson.databind.JsonNode

/**
 * Reads a Luke response into what the index actually holds.
 *
 * **The third reader, answering a question the other two cannot.** The repository parser and the
 * schema reader both produce `SolrConfigsetFacts`, because a configset and a schema response
 * describe the same thing. Luke describes something else: the fields that exist *in the index*,
 * which is not the same set. A field a dynamic pattern created at index time is here and can be in
 * neither of the others, and that asymmetry is why this produces its own type.
 *
 * **Flag letters are decoded from the legend the response carries, never from a table written
 * here.** Every Luke response ships `info.key` — eighteen entries on both supported lines — mapping
 * `I` to Indexed, `D` to DocValues and so on. A copy of that table in this file would be a second
 * list that has to agree with Solr's, and this plugin has already paid twice for exactly that shape
 * of mistake. Reading Solr's own means a letter Solr adds is decoded the day it appears, and a
 * letter this plugin does not recognise is dropped rather than guessed at.
 *
 * Pure text in, contents out, tested against responses captured from running Solr on both lines.
 */
object SolrLukeReader {

    /**
     * What [body] says the index holds, or empty contents where it is not a Luke response.
     *
     * @param body a response body from `GET /<collection>/admin/luke`
     * @return the index's contents; empty where the text is not JSON, or is JSON without fields
     */
    fun read(body: String): SolrIndexContents {
        val tree = SolrJsonDocuments.treeOf(body) ?: return SolrIndexContents()
        return read(tree)
    }

    /**
     * What an already-parsed [response] says the index holds.
     *
     * @param response a parsed response from `GET /<collection>/admin/luke`
     * @return the index's contents
     */
    fun read(response: JsonNode): SolrIndexContents {
        val legend = legendIn(response)
        return SolrIndexContents(
            summary = summaryIn(response.path(INDEX)),
            fields = response.path(FIELDS).properties().map { (name, node) -> fieldIn(name, node, legend) },
        )
    }

    /**
     * Solr's own mapping from flag letter to what it means.
     *
     * @param response a parsed Luke response
     * @return the legend it carries, empty where it carries none
     */
    private fun legendIn(response: JsonNode): Map<Char, String> =
        response.path(INFO).path(KEY).properties()
            .filter { (letter, _) -> letter.length == 1 }
            .associate { (letter, meaning) -> letter.first() to meaning.asString("") }

    private fun summaryIn(index: JsonNode) = SolrIndexSummary(
        numDocs = index.intOrNull(NUM_DOCS),
        maxDoc = index.intOrNull(MAX_DOC),
        deletedDocs = index.intOrNull(DELETED_DOCS),
        segmentCount = index.intOrNull(SEGMENT_COUNT),
        current = index.path(CURRENT).takeIf { it.isBoolean }?.asBoolean(),
    )

    private fun fieldIn(name: String, node: JsonNode, legend: Map<Char, String>): SolrIndexField {
        val index = node.path(INDEX).takeIf { it.isString }?.asString("")
        return SolrIndexField(
            name = name,
            type = node.path(TYPE).asString(""),
            dynamicBase = node.path(DYNAMIC_BASE).asString("").takeIf { it.isNotEmpty() },
            // Absent rather than zero, and the difference reaches users: a point field carries no
            // count at all even with documents in it, having no inverted index to count from.
            // Rendering that as "0 documents" would state something false about exactly the field
            // types Solr recommends.
            docs = node.intOrNull(DOCS),
            schemaProperties = decode(node.path(SCHEMA).takeIf { it.isString }?.asString(""), legend),
            indexProperties = decode(index?.takeIf { it.isFlagString(legend) }, legend),
            // Solr writes prose here where it has no flags to give — `(unstored field)`, observed on
            // both supported lines. Decoding that as flags would manufacture properties out of its
            // punctuation, so it is carried across as the sentence it is.
            indexNote = index?.takeIf { it.isNotEmpty() && !it.isFlagString(legend) },
        )
    }

    /**
     * Whether a value is a flag string rather than something Solr wrote in words.
     *
     * Decided by whether every character is one the legend knows or the `-` that stands for an
     * absent flag, rather than by looking for a leading bracket. A rule keyed on the punctuation of
     * one observed message would be a rule about that message; this one holds for any prose Solr
     * puts there, including prose nobody has seen yet.
     */
    private fun String.isFlagString(legend: Map<Char, String>): Boolean =
        isNotEmpty() && all { it == ABSENT || legend.containsKey(it) }

    private fun decode(flags: String?, legend: Map<Char, String>): List<String> =
        flags.orEmpty().mapNotNull { if (it == ABSENT) null else legend[it] }

    private fun JsonNode.intOrNull(field: String): Int? = path(field).takeIf { it.isNumber }?.asInt()

    // The keys a Luke response uses. Named for the same reason the other readers' are: `path`
    // answers a misspelling with a missing node rather than an error, so a typo here is a field list
    // that comes back empty and a plugin that reports it confidently.
    private const val INDEX = "index"
    private const val FIELDS = "fields"
    private const val INFO = "info"
    private const val KEY = "key"
    private const val TYPE = "type"
    private const val SCHEMA = "schema"
    private const val DYNAMIC_BASE = "dynamicBase"
    private const val DOCS = "docs"
    private const val NUM_DOCS = "numDocs"
    private const val MAX_DOC = "maxDoc"
    private const val DELETED_DOCS = "deletedDocs"
    private const val SEGMENT_COUNT = "segmentCount"
    private const val CURRENT = "current"

    /** The character Solr writes where a flag is not set. */
    private const val ABSENT = '-'
}
