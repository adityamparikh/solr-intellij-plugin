package org.apache.solr.ide.model

/**
 * A kind of class a configset can name in a `class` attribute.
 *
 * @property token the word the generated catalog uses for this kind
 */
enum class SolrClassKind(internal val token: String) {

    /** The implementation behind a `<fieldType>`, such as `solr.StrField`. */
    FIELD_TYPE("fieldType"),

    /** The `<tokenizer>` that splits a value into terms. */
    TOKENIZER("tokenizer"),

    /** A `<filter>` in an analyzer chain, applied to the tokens a tokenizer produced. */
    TOKEN_FILTER("tokenFilter"),

    /** A `<charFilter>`, applied to the raw text before tokenization. */
    CHAR_FILTER("charFilter"),
}

/**
 * One class a configset may name.
 *
 * @property kind what the class is, and therefore where it may be written
 * @property className the fully qualified name, as reflection sees it
 * @property shortName the `solr.`-prefixed form a configset normally uses
 */
data class SolrClassEntry(
    val kind: SolrClassKind,
    val className: String,
    val shortName: String,
)

/**
 * The Solr and Lucene classes a configset can name, per supported Solr line.
 *
 * **Generated at build time, not written down.** There are roughly 185 entries per line and they
 * differ between lines — Solr 10 dropped `CurrencyField`, `EnumField`, `ExternalFileField` and
 * `PreAnalyzedField`, and added `BinaryQuantizedDenseVectorField`. The specification argues the
 * general case under "The factory catalog"; the operative fact here is that this file only reads
 * what `:generateSolrCatalog` produced, and knows nothing about Solr itself.
 *
 * The tab-separated form is deliberate. Reading it needs no parser and no dependency, and a
 * regenerated catalog produces a diff a human can review — which matters, because the way a
 * generator fails is by producing a plausible short list rather than an error.
 */
object SolrClassCatalog {

    /**
     * The Solr major lines with a shipped catalog, newest first.
     *
     * Kept in step with the `supportedSolrLines` the build declares. A line named here with no
     * generated resource behind it reads as empty rather than failing, because a missing catalog
     * must not take the editor down with it.
     */
    val SUPPORTED_LINES: List<Int> = listOf(10, 9)

    private val byLine = HashMap<Int, List<SolrClassEntry>>()

    /**
     * Every class the [version] line can name.
     *
     * @param version the Solr line this configset targets
     * @return the entries for that line, or the newest line's when it names none this ships
     */
    fun entriesFor(version: SolrVersionSelection): List<SolrClassEntry> = load(lineFor(version))

    /**
     * The classes of one [kind] that the [version] line can name.
     *
     * @param kind the position being completed or documented
     * @param version the Solr line this configset targets
     * @return the matching entries, in the order the catalog lists them
     */
    fun of(kind: SolrClassKind, version: SolrVersionSelection): List<SolrClassEntry> =
        entriesFor(version).filter { it.kind == kind }

    /**
     * The entry for [name], matched against either spelling.
     *
     * A configset may write `solr.StrField` or the fully qualified name, and both are the same
     * class — so a lookup that only understood one would report a correct file as unrecognized.
     *
     * @param name the class name as written in the configset
     * @param version the Solr line this configset targets
     * @return the entry, or null when this line names no such class
     */
    fun find(name: String, version: SolrVersionSelection): SolrClassEntry? =
        entriesFor(version).firstOrNull { it.shortName == name || it.className == name }

    /**
     * The line whose catalog answers for [version].
     *
     * An unsupported or undeclared version falls back to the newest line rather than to nothing.
     * A configset declaring Solr 7 is one this plugin does not support, but a reader looking at it
     * is better served by the newest line's vocabulary than by silence.
     */
    private fun lineFor(version: SolrVersionSelection): Int {
        val declared = version.guidePathSegment.substringBefore('_').toIntOrNull()
        return declared?.takeIf { it in SUPPORTED_LINES } ?: SUPPORTED_LINES.first()
    }

    private fun load(line: Int): List<SolrClassEntry> = synchronized(byLine) {
        byLine.getOrPut(line) { read(line) }
    }

    private fun read(line: Int): List<SolrClassEntry> {
        val stream = SolrClassCatalog::class.java.getResourceAsStream("/solr-catalog/solr-$line.tsv")
            ?: return emptyList()
        val kinds = SolrClassKind.entries.associateBy { it.token }
        return stream.bufferedReader().useLines { lines ->
            lines.mapNotNull { row ->
                if (row.startsWith("#") || row.isBlank()) return@mapNotNull null
                val columns = row.split('\t')
                if (columns.size != 3) return@mapNotNull null
                kinds[columns[0]]?.let { SolrClassEntry(it, columns[1], columns[2]) }
            }.toList()
        }
    }
}
