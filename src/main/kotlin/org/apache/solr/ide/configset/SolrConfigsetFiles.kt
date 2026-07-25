package org.apache.solr.ide.configset

/**
 * The kinds of Solr configset file the plugin recognizes, together with the file names that
 * identify each one. Filename matching is the primary detection signal (see
 * [SolrConfigsetDetector]); directory heuristics refine it.
 *
 * Note: `managed-schema` historically ships with no extension; newer Solr writes
 * `managed-schema.xml`. Both are matched, and both are associated with the XML file type in
 * `plugin.xml` so they are parsed as XML for later PSI features.
 *
 * @property fileNames the exact file names identifying this kind
 */
enum class SolrConfigsetFileKind(val fileNames: Set<String>) {

    /**
     * The schema, declaring fields, field types and analyzer chains.
     *
     * Covers all three names Solr has used: the classic hand-edited `schema.xml`, and the
     * managed-schema variants written by the Schema API.
     */
    SCHEMA(setOf("schema.xml", "managed-schema", "managed-schema.xml")),

    /**
     * The core configuration, declaring request handlers, search components and index settings.
     */
    SOLR_CONFIG(setOf("solrconfig.xml"));

    /** Name-based lookup over the declared kinds. */
    companion object {
        /** All recognized configset file names across every kind. */
        val ALL_FILE_NAMES: Set<String> = entries.flatMap { it.fileNames }.toSet()

        /**
         * The kind matching [fileName] (case-sensitive, as Solr file names are), or null.
         *
         * Matches on the name alone, with no regard for where the file sits. Use
         * [SolrConfigsetDetector.kindOf] to classify a file that must actually belong to a
         * configset.
         *
         * @param fileName a bare file name, not a path
         * @return the matching kind, or null if the name is not a recognized configset file name
         */
        fun forFileName(fileName: String): SolrConfigsetFileKind? =
            entries.firstOrNull { fileName in it.fileNames }
    }
}
