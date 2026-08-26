package org.apache.solr.ide.server.reading

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * Reading a Solr response body into a tree, in one place.
 *
 * **The JSON counterpart of `SolrXmlDocuments`, and it exists for the same reason.** That object is
 * where the configset parsers agree on how XML is read; this is where the transport and the schema
 * reader agree on how JSON is. Two mappers configured separately would be two answers to "is this
 * body readable" — and the schema reader's tree overload exists precisely to stop those two from
 * disagreeing, which a second mapper would quietly reintroduce.
 *
 * It is also the one place a read constraint would go. A schema response from a large collection is
 * megabytes of JSON, and if a limit is ever needed it belongs here rather than in whichever caller
 * met the problem first.
 */
internal object SolrJsonDocuments {

    private val MAPPER: JsonMapper = JsonMapper.builder().build()

    /**
     * Reads [text] as JSON, or null where it is not.
     *
     * Null rather than an exception because the case is ordinary rather than exceptional: a mistyped
     * collection is answered by Solr's servlet container with an HTML error page, and that is a
     * routine mistake rather than a fault in the plugin.
     *
     * @param text a response body
     * @return the parsed tree, or null when the text is not JSON
     */
    fun treeOf(text: String): JsonNode? = runCatching { MAPPER.readTree(text) }.getOrNull()
}
