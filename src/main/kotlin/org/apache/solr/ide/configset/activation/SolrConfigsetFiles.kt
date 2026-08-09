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
     * Whether this kind is `solrconfig.xml`.
     *
     * Trivial, and it exists for the same reason [isSchema] does: it is how a feature *declares which
     * file it serves*. The packages here are organised by capability — inspection, completion,
     * reference — so nothing above this line says whether a given inspection reads the schema or the
     * configuration, and a reader has to find the gate at the top of `buildVisitor` to know. Asking
     * `isSolrConfig` or [isSchema] makes that gate the same shape everywhere and greppable, where four
     * hand-written comparisons against a file-name literal were neither.
     */
    val isSolrConfig: Boolean get() = this == SOLR_CONFIG

    /**
     * Whether a file of this kind can hold a reference to a field: the schema and `solrconfig.xml`.
     *
     * The pairing several features need and none of them should restate. A field name is written in
     * the schema that declares it, in a `copyField` that copies it and in a handler parameter that
     * queries it — three positions across two file kinds — so anything walking a configset for field
     * references, or supplying a vocabulary to both files, asks this rather than spelling out the
     * disjunction. A second copy of the pairing is a second thing to forget when a kind is added.
     */
    val holdsFieldReferences: Boolean get() = isSchema || isSolrConfig

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
        fun forFileName(fileName: String): SolrConfigsetFileKind? = BY_FILE_NAME[fileName]

        /**
         * Every recognized file name, to its kind.
         *
         * A map rather than a scan because the reference contributor asks this per tag while declining
         * a file, where its own comment promises the cheapest possible decline. Scanning twelve entries
         * and testing a set membership on each was cheap in absolute terms and needlessly so.
         */
        private val BY_FILE_NAME: Map<String, SolrConfigsetFileKind> =
            entries.flatMap { kind -> kind.fileNames.map { it to kind } }.toMap()

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

/**
 * The element names a Solr schema uses for the things this plugin reasons about.
 *
 * Knowledge about Solr's vocabulary, not about any one feature. It was previously declared in six
 * files under three different names, which is the shape a constant takes just before someone updates
 * five of the six.
 */
object SolrSchemaTags {

    /**
     * The attribute a declaration writes its name in.
     *
     * The one entry here that is not a tag name, and it is here rather than beside its readers
     * because it is the same fact about Solr's vocabulary. `name` is also the commonest attribute
     * in XML, so a reader of this constant is never asking it alone — the tag decides whether the
     * attribute means a declaration, exactly as [COPY_FIELD_ENDS] and [RESOURCE_ATTRIBUTES] do.
     */
    const val NAME: String = "name"

    /** Tags that declare a field: a concrete one, or a dynamic pattern. */
    val FIELD: Set<String> = setOf("field", "dynamicField")

    /**
     * The tag declaring a dynamic field on its own.
     *
     * Named separately from [FIELD] for the readers that must tell the two apart rather than
     * accept either — a dynamic field supplies names it does not spell, so what is true of
     * searching for one is not true of searching for a concrete field.
     */
    const val DYNAMIC_FIELD: String = "dynamicField"

    /** Tags that declare a field type. Solr accepts both spellings and real configsets use both. */
    val FIELD_TYPE: Set<String> = setOf("fieldType", "fieldtype")

    /** The tag declaring a copy rule. */
    const val COPY_FIELD: String = "copyField"

    /**
     * The two attributes of a [COPY_FIELD] that name a field.
     *
     * Unlike the tag names, these are ordinary words — an Ant-style `<copy source= dest=>` uses
     * both. They mean a field only in the tag position, so every reader of this set has to check
     * the tag as well.
     */
    val COPY_FIELD_ENDS: Set<String> = setOf("source", "dest")

    /** The tag wrapping one analysis chain, index-time or query-time. */
    const val ANALYZER: String = "analyzer"

    /** The tag declaring the chain's tokenizer, the one component a chain has at most one of. */
    const val TOKENIZER: String = "tokenizer"

    /** The tag declaring an analyzer filter — the position whose attributes may name resource files. */
    const val FILTER: String = "filter"

    /** The tag declaring a char filter — the other analyzer component that reads resource files. */
    const val CHAR_FILTER: String = "charFilter"

    /**
     * The three tags naming a factory in their `class`, which is what the generated catalog indexes.
     *
     * Distinct from [RESOURCE_CARRIERS] below, which is a subset chosen for a different reason: this
     * set is every component whose `class` can be looked up, that one is the components whose
     * attributes may name a file. A tag can be in one and not the other, and the tokenizer is.
     */
    val ANALYSIS_COMPONENTS: Set<String> = setOf(TOKENIZER, FILTER, CHAR_FILTER)

    /**
     * The analyzer components a resource attribute may sit on.
     *
     * Tokenizers are deliberately absent: the stock tokenizers that read files at all take them
     * through attributes named per factory (`rulefiles`), and guessing at those would put file
     * references on values that are not paths.
     */
    val RESOURCE_CARRIERS: Set<String> = setOf(FILTER, CHAR_FILTER)

    /**
     * The analyzer component attributes whose value is a path to a resource file in the configset.
     *
     * `words` on a stop filter, `synonyms` on a synonym graph, `protected` on the stemmers,
     * `articles` on elision, `mapping` on the mapping char filter, and the Hunspell
     * `dictionary`/`affix` pair. Ordinary words, like [COPY_FIELD_ENDS] — `protected`
     * especially — so they mean a file only on a [RESOURCE_CARRIERS] tag, and every reader of
     * this set has to check the tag as well.
     */
    val RESOURCE_ATTRIBUTES: Set<String> =
        setOf("words", "synonyms", "protected", "articles", "mapping", "dictionary", "affix")
}
