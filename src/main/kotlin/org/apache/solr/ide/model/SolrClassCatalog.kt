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
    CHAR_FILTER("charFilter");

    /** Lookup from the schema's element vocabulary. */
    companion object {

        /**
         * The kind of class [tagName]'s `class` attribute names, or null when the element does
         * not carry one.
         *
         * Both spellings of `fieldType` are accepted, as Solr accepts both. This is the single
         * mapping from schema vocabulary to catalog population; completion and documentation both
         * read it, so a kind added to one cannot silently be missed by the other.
         *
         * @param tagName an element name as written in a schema
         * @return the kind, or null
         */
        fun forTag(tagName: String): SolrClassKind? = when (tagName) {
            "fieldType", "fieldtype" -> FIELD_TYPE
            "tokenizer" -> TOKENIZER
            "filter" -> TOKEN_FILTER
            "charFilter" -> CHAR_FILTER
            else -> null
        }
    }
}

/**
 * One attribute a class reads, and what kind of value it accepts.
 *
 * **[defaultValue] and [required] are recorded only where the bytecode proves them**, for the same
 * reason [valueType] is: a factory reads a literal default beside the attribute name — `getInt(args,
 * "generateWordParts", 1)` — and marks an attribute required by reading it with `requireInt` rather
 * than `getInt`. A default computed at runtime is recorded as absent rather than guessed, so a value
 * the plugin shows on hover is one Solr would genuinely fall back to. The pair is what the factory
 * half of quick documentation and the restates-the-default inspection consume.
 *
 * @property name the attribute name as a configset writes it
 * @property valueType what the class does with the value
 * @property defaultValue the value Solr uses when the attribute is absent, or null when none is
 *   declared or the default is computed rather than literal
 * @property required whether Solr rejects the class when the attribute is absent
 */
data class SolrClassAttribute(
    val name: String,
    val valueType: SolrValueType = SolrValueType.FREE,
    val defaultValue: String? = null,
    val required: Boolean = false,
) {

    /** Service lookup for the token spellings the generated catalog uses. */
    companion object {

        /**
         * Reads one attribute entry as the generated catalog writes it.
         *
         * The grammar is `name:type`, optionally carrying one of two mutually exclusive markers: a
         * trailing `!` for a required attribute (`minGramSize:int!`), or `=` and a literal default
         * (`generateWordParts:int=1`). A required attribute never carries a default, because the
         * reader that marks it required — `requireInt` — takes none.
         *
         * An entry with no `:` reads as [SolrValueType.FREE] and no default, so a catalog generated
         * before types or defaults existed degrades to "no value checking" rather than to an
         * exception.
         *
         * @param entry one comma-separated attribute from the catalog's fourth column
         * @return the parsed attribute
         */
        fun parse(entry: String): SolrClassAttribute {
            val name = entry.substringBefore(':')
            val rest = entry.substringAfter(':', "")
            return when {
                '=' in rest -> SolrClassAttribute(
                    name,
                    SolrValueType.forToken(rest.substringBefore('=')),
                    defaultValue = rest.substringAfter('='),
                )
                rest.endsWith('!') -> SolrClassAttribute(
                    name,
                    SolrValueType.forToken(rest.dropLast(1)),
                    required = true,
                )
                else -> SolrClassAttribute(name, SolrValueType.forToken(rest))
            }
        }
    }
}

/**
 * One class a configset may name.
 *
 * @property kind what the class is, and therefore where it may be written
 * @property className the fully qualified name, as reflection sees it
 * @property shortName the `solr.`-prefixed form a configset normally uses
 * @property attributes the attributes this class reads, empty where none are known
 * @property summary a one-sentence summary of the class's own Javadoc, or null when the line's
 *   `-sources` artifacts did not resolve or carried none for this class
 * @property traits what this class decides about its fields' property defaults; always empty for an
 *   analysis factory, which has no defaults to decide. An empty set on a field type is a positive
 *   answer — the class carries no trait — and is what makes a default resolvable as `false` rather
 *   than left undetermined
 */
data class SolrClassEntry(
    val kind: SolrClassKind,
    val className: String,
    val shortName: String,
    val attributes: List<SolrClassAttribute> = emptyList(),
    val summary: String? = null,
    val traits: Set<SolrTypeTrait> = emptySet(),
) {

    /** The attribute [name], or null when this class does not read one by that name. */
    fun attribute(name: String): SolrClassAttribute? = attributes.firstOrNull { it.name == name }
}

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
 *
 * **The attributes are the part reflection cannot supply.** A factory reads them out of a
 * `Map<String, String>` by string literal, so they exist only inside its constructor body and are
 * neither fields nor annotations. The generator recovers them from bytecode; anything that
 * enumerated members instead would produce a short, plausible, wrong list.
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
        return stream.bufferedReader().useLines { parse(it) }
    }

    /**
     * The entries in [rows], skipping anything that is not one.
     *
     * Separate from reading the resource so it can be tested against input the generator would
     * never produce. That matters more here than the shipped file suggests: this parser is the one
     * place a malformed or half-written catalog reaches the editor, and the guarantee worth having
     * is that it drops the bad row rather than the file.
     *
     * @param rows the catalog's lines
     * @return the entries, in the order they appear
     */
    internal fun parse(rows: Sequence<String>): List<SolrClassEntry> {
        val kinds = SolrClassKind.entries.associateBy { it.token }
        return rows.mapNotNull { row ->
            if (row.startsWith("#") || row.isBlank()) return@mapNotNull null
            val columns = row.split('\t')
            if (columns.size < 3) return@mapNotNull null
            val attributes = columns.getOrNull(3).orEmpty()
                .split(',')
                .filter { it.isNotBlank() }
                .map { SolrClassAttribute.parse(it) }
            // Absent on a catalog generated before the documentation column existed, and blank when
            // this line's `-sources` artifacts resolved but carried nothing for this class -- both
            // read as "nothing to show" rather than as an empty sentence.
            val summary = columns.getOrNull(4)?.takeIf { it.isNotBlank() }
            // An unknown trait token is dropped rather than failing the entry: a catalog written by
            // a newer build naming a trait this one has never heard of should cost that one trait.
            val traits = columns.getOrNull(5).orEmpty()
                .split(',')
                .mapNotNullTo(mutableSetOf()) { SolrTypeTrait.forToken(it.trim()) }
            kinds[columns[0]]?.let { SolrClassEntry(it, columns[1], columns[2], attributes, summary, traits) }
        }.toList()
    }
}
