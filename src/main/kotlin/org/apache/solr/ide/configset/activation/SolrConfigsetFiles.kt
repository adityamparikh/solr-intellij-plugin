package org.apache.solr.ide.configset.activation

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
     * `solrconfig.xml`, `managed-schema`, `elevate.xml` and `enumsConfig.xml` essentially do not
     * occur outside Solr — the product name, or a spelling only Solr uses, is in the file name. One
     * of these is enough to make its directory a configset root.
     */
    SELF_IDENTIFYING,

    /**
     * Names Solr uses that are too common to prove anything on their own.
     *
     * `schema.xml` belongs to XSDs and half a dozen frameworks; `params.json` could be anything.
     * These are recognized only inside a directory a [SELF_IDENTIFYING] file has already proven,
     * which is what keeps an unrelated `schema.xml` from being read as a Solr schema in a project
     * that happens to use Solr elsewhere.
     *
     * The project-level dependency check in [SolrProjectDetector] does not settle this. It answers
     * whether the *project* uses Solr; it cannot say whether *this file* is Solr's.
     */
    AMBIGUOUS,

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
     * The schema under the names the Schema API writes: `managed-schema`, historically without an
     * extension, and the newer `managed-schema.xml`.
     *
     * Self-identifying — nothing but Solr calls a file `managed-schema`. Split from
     * [SCHEMA_CLASSIC] only because the two spellings carry different weight as evidence; use
     * [isSchema] where the distinction does not matter.
     */
    SCHEMA_MANAGED(SolrConfigsetFileRole.SELF_IDENTIFYING, fileNames = setOf("managed-schema", "managed-schema.xml")),

    /**
     * The schema under its classic hand-edited name, `schema.xml`.
     *
     * Ambiguous: XSDs and several unrelated frameworks use exactly this name, so it is recognized
     * only inside a directory a self-identifying file has already proven — in practice the
     * `solrconfig.xml` that sits beside it in every real configset.
     */
    SCHEMA_CLASSIC(SolrConfigsetFileRole.AMBIGUOUS, fileNames = setOf("schema.xml")),

    /**
     * The core configuration, declaring request handlers, search components and index settings.
     */
    SOLR_CONFIG(SolrConfigsetFileRole.SELF_IDENTIFYING, fileNames = setOf("solrconfig.xml")),

    /**
     * Request parameter sets, written by the Request Parameters API and referenced by handlers
     * through `useParams`.
     */
    PARAMS(SolrConfigsetFileRole.AMBIGUOUS, fileNames = setOf("params.json")),

    /**
     * Query elevation rules, mapping queries to documents forced to the top of the results.
     */
    ELEVATE(SolrConfigsetFileRole.SELF_IDENTIFYING, fileNames = setOf("elevate.xml")),

    /**
     * Exchange rates backing `CurrencyFieldType`.
     */
    CURRENCY(SolrConfigsetFileRole.AMBIGUOUS, fileNames = setOf("currency.xml")),

    /**
     * Enumerated value ordering backing `EnumFieldType`.
     */
    ENUMS_CONFIG(SolrConfigsetFileRole.SELF_IDENTIFYING, fileNames = setOf("enumsConfig.xml")),

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

    /**
     * Whether this kind is the schema, under either of the names Solr has used for it.
     *
     * The managed and classic spellings are separate kinds because they differ as *evidence*, not
     * because they differ as *content*. Everything downstream reads the same fields out of both, so
     * it should ask this rather than naming the two kinds.
     */
    val isSchema: Boolean get() = this == SCHEMA_MANAGED || this == SCHEMA_CLASSIC

    /**
     * Whether a file of this kind activates features on its own account.
     *
     * True for everything except resources, which are recognized inside a configset but are never
     * the reason one is found.
     */
    val activatesFeatures: Boolean get() = role != SolrConfigsetFileRole.RESOURCE

    /** Name-based lookup over the declared kinds. */
    companion object {
        /** All recognized configset file names across every kind, resources included. */
        val ALL_FILE_NAMES: Set<String> = entries.flatMap { it.fileNames }.toSet()

        /**
         * The file names that on their own make a directory a configset root.
         *
         * A strict subset of [ALL_FILE_NAMES], and the narrowest of the three tiers: every name here
         * carries Solr's own vocabulary, so none of them needs corroborating. Ambiguous and resource
         * names are excluded — they are recognized *within* a configset this set has already found,
         * never as the reason one is found.
         */
        val SELF_IDENTIFYING_FILE_NAMES: Set<String> =
            entries.filter { it.role == SolrConfigsetFileRole.SELF_IDENTIFYING }.flatMap { it.fileNames }.toSet()

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
