package org.apache.solr.ide.configset

import com.intellij.openapi.vfs.VirtualFile

/**
 * What a recognized configset entry is allowed to prove.
 *
 * The distinction is the whole reason this enum exists. Solr configsets contain two populations of
 * file whose names carry very different weight, and conflating them would make the plugin activate
 * on projects that have no Solr in them at all.
 */
enum class SolrConfigsetFileRole {

    /**
     * Names distinctive enough to be evidence that a directory *is* a configset.
     *
     * `solrconfig.xml`, `elevate.xml` and `enumsConfig.xml` essentially do not occur outside Solr,
     * and `schema.xml` is common enough that it is only ever believed with corroboration — which is
     * what [SolrConfigsetDetector] supplies.
     */
    IDENTIFYING,

    /**
     * Names too common to prove anything, recognized only from inside a configset already
     * identified by other means.
     *
     * `stopwords.txt` and `synonyms.txt` appear in every second NLP project on disk. Treating them
     * as evidence would activate the plugin on projects with no Solr in them; refusing to recognize
     * them at all would make a filter's `words="stopwords.txt"` attribute unnavigable. Recognizing
     * them only within a known configset gets the navigation without the false positives.
     */
    RESOURCE,
}

/**
 * The kinds of Solr configset entry the plugin recognizes, together with the names identifying each
 * one and what that name is allowed to prove ([role]).
 *
 * Most kinds are files, but analyzer resources may also be a directory: Solr ships per-language
 * stopword and stemming resources under `lang/`, which a filter references as `lang/stopwords_en.txt`.
 * The enum therefore matches directory names as well as file names, and [forFile] dispatches on
 * which of the two a given [VirtualFile] is.
 *
 * Note: `managed-schema` historically ships with no extension; newer Solr writes
 * `managed-schema.xml`. Both are matched, and both are associated with the XML file type in
 * `plugin.xml` so they are parsed as XML for later PSI features.
 *
 * @property role whether this kind may serve as evidence a configset exists
 * @property fileNames the exact file names identifying this kind
 * @property directoryNames the exact directory names identifying this kind
 */
enum class SolrConfigsetFileKind(
    val role: SolrConfigsetFileRole,
    val fileNames: Set<String> = emptySet(),
    val directoryNames: Set<String> = emptySet(),
) {

    /**
     * The schema, declaring fields, field types and analyzer chains.
     *
     * Covers all three names Solr has used: the classic hand-edited `schema.xml`, and the
     * managed-schema variants written by the Schema API.
     */
    SCHEMA(SolrConfigsetFileRole.IDENTIFYING, fileNames = setOf("schema.xml", "managed-schema", "managed-schema.xml")),

    /**
     * The core configuration, declaring request handlers, search components and index settings.
     */
    SOLR_CONFIG(SolrConfigsetFileRole.IDENTIFYING, fileNames = setOf("solrconfig.xml")),

    /**
     * Request parameter sets, written by the Request Parameters API and referenced by handlers
     * through `useParams`.
     */
    PARAMS(SolrConfigsetFileRole.IDENTIFYING, fileNames = setOf("params.json")),

    /**
     * Query elevation rules, mapping queries to documents forced to the top of the results.
     */
    ELEVATE(SolrConfigsetFileRole.IDENTIFYING, fileNames = setOf("elevate.xml")),

    /**
     * Exchange rates backing `CurrencyFieldType`.
     */
    CURRENCY(SolrConfigsetFileRole.IDENTIFYING, fileNames = setOf("currency.xml")),

    /**
     * Enumerated value ordering backing `EnumFieldType`.
     */
    ENUMS_CONFIG(SolrConfigsetFileRole.IDENTIFYING, fileNames = setOf("enumsConfig.xml")),

    /**
     * Stopword lists named by a `StopFilterFactory`'s `words` attribute.
     *
     * A resource, not evidence — see [SolrConfigsetFileRole.RESOURCE].
     */
    STOPWORDS(SolrConfigsetFileRole.RESOURCE, fileNames = setOf("stopwords.txt")),

    /**
     * Synonym mappings named by a `SynonymGraphFilterFactory`'s `synonyms` attribute.
     *
     * A resource, not evidence — see [SolrConfigsetFileRole.RESOURCE].
     */
    SYNONYMS(SolrConfigsetFileRole.RESOURCE, fileNames = setOf("synonyms.txt")),

    /**
     * Protected words shielded from stemming, named by a stemmer factory's `protected` attribute.
     *
     * A resource, not evidence — see [SolrConfigsetFileRole.RESOURCE].
     */
    PROTWORDS(SolrConfigsetFileRole.RESOURCE, fileNames = setOf("protwords.txt")),

    /**
     * The `lang/` directory holding Solr's per-language analyzer resources.
     *
     * The only kind identified by a directory name rather than a file name, and a resource rather
     * than evidence: `lang` is far too common a directory name to prove anything.
     */
    LANGUAGE_RESOURCES(SolrConfigsetFileRole.RESOURCE, directoryNames = setOf("lang"));

    /** Name-based lookup over the declared kinds. */
    companion object {
        /** All recognized configset file names across every kind, resources included. */
        val ALL_FILE_NAMES: Set<String> = entries.flatMap { it.fileNames }.toSet()

        /**
         * The file names that may serve as evidence a directory is a configset.
         *
         * A strict subset of [ALL_FILE_NAMES]: resource names are excluded, because they are common
         * enough outside Solr that believing them would activate the plugin on unrelated projects.
         */
        val IDENTIFYING_FILE_NAMES: Set<String> =
            entries.filter { it.role == SolrConfigsetFileRole.IDENTIFYING }.flatMap { it.fileNames }.toSet()

        /**
         * The kind matching [fileName] (case-sensitive, as Solr file names are), or null.
         *
         * Matches on the name alone, with no regard for where the file sits or whether it is really
         * a file. Use [SolrConfigsetDetector.kindOf] to classify something that must actually belong
         * to a configset.
         *
         * @param fileName a bare file name, not a path
         * @return the matching kind, or null if the name is not a recognized configset file name
         */
        fun forFileName(fileName: String): SolrConfigsetFileKind? =
            entries.firstOrNull { fileName in it.fileNames }

        /**
         * The kind matching a directory called [directoryName], or null.
         *
         * @param directoryName a bare directory name, not a path
         * @return the matching kind, or null if no kind is identified by that directory name
         */
        fun forDirectoryName(directoryName: String): SolrConfigsetFileKind? =
            entries.firstOrNull { directoryName in it.directoryNames }

        /**
         * The kind matching [file], dispatching on whether it is a directory.
         *
         * Keeps a directory named `schema.xml` from being classified as the schema, which name-only
         * matching cannot rule out.
         *
         * @param file the file or directory to classify by name
         * @return the matching kind, or null if the name is not recognized in that form
         */
        fun forFile(file: VirtualFile): SolrConfigsetFileKind? =
            if (file.isDirectory) forDirectoryName(file.name) else forFileName(file.name)
    }
}
