package org.apache.solr.ide.configset

/**
 * The kinds of Solr configset file the plugin recognizes, together with the file names that
 * identify each one. Filename matching is the primary detection signal (see
 * [SolrConfigsetDetector]); directory heuristics refine it.
 *
 * Note: `managed-schema` historically ships with no extension; newer Solr writes
 * `managed-schema.xml`. Both are matched, and both are associated with the XML file type in
 * `plugin.xml` so they are parsed as XML for later PSI features.
 */
enum class SolrConfigsetFileKind(val fileNames: Set<String>) {
    SCHEMA(setOf("schema.xml", "managed-schema", "managed-schema.xml")),
    SOLR_CONFIG(setOf("solrconfig.xml"));

    companion object {
        /** All recognized configset file names across every kind. */
        val ALL_FILE_NAMES: Set<String> = entries.flatMap { it.fileNames }.toSet()

        /** The kind matching [fileName] (case-sensitive, as Solr file names are), or null. */
        fun forFileName(fileName: String): SolrConfigsetFileKind? =
            entries.firstOrNull { fileName in it.fileNames }
    }
}
