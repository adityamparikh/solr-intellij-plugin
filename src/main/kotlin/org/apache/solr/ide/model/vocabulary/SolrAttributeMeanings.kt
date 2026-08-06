package org.apache.solr.ide.model.vocabulary

import org.apache.solr.ide.model.schema.SolrSchemaVersion
import org.apache.solr.ide.model.schema.SolrVersionRange

/**
 * What an attribute *means*, as opposed to what type it holds or what it defaults to.
 *
 * A tag offers a caret three positions and, before this, two of them answered: the element explained
 * itself and so did an attribute's value, while the attribute's own name said nothing. This is the
 * middle one.
 *
 * **Written by hand, and that is the deliberate part.** The catalog is generated from bytecode
 * because it is thousands of attributes that move with every Solr line, and
 * [the factory catalog][SolrClassCatalog] proves each one's value type, default and requiredness
 * from the class that reads it. None of that can say what an attribute *does* — Javadoc is written
 * per class, so no generated source carries per-attribute prose, which is why an earlier revision
 * declined to show any.
 *
 * That reasoning was right about the generated source and stopped one step short. The rule this
 * plugin holds is *do not invent facts*: a computed default is recorded as absent rather than
 * guessed, an unknown class stays silent rather than claiming an empty attribute set. Writing down
 * what `minGramSize` does invents nothing — it is a fact, recorded, and it has not changed since
 * 2012. The maintenance argument does not transfer either: this is the two dozen attributes a
 * reader actually hovers, not the whole vocabulary.
 *
 * **Silent by default, everywhere.** An attribute this does not list keeps exactly the popup it had
 * before, and no element the plugin does not model answers at all. The failure that matters here is
 * a popup appearing where none should.
 */
object SolrAttributeMeanings {

    /**
     * What [attributeName] means on [tagName], or null when the pair is not modelled.
     *
     * **Keyed by the pair rather than by the name**, because `name` on a `copyField` is not a field
     * name and describing it as one would be worse than saying nothing. The elements that share a
     * meaning share an entry; the ones that do not, do not.
     *
     * @param tagName the element the attribute is written on
     * @param attributeName the attribute's own name
     * @return a sentence about what it means, or null to stay silent
     */
    fun of(tagName: String, attributeName: String): String? = STRUCTURAL[tagName to attributeName]

    /**
     * What a factory's [attributeName] does, or null when the table does not list it.
     *
     * Not keyed by the owning class: `minGramSize` means the same thing on every n-gram factory,
     * and keying by class would triple the table to say one thing three times. An attribute name
     * that genuinely meant two things on two factories could not be expressed here and would have
     * to move to a class-keyed entry — none does today.
     *
     * @param attributeName the attribute written on the factory tag
     * @return what it does, or null to leave the popup as the catalog built it
     */
    fun ofFactoryAttribute(attributeName: String): String? = FACTORY[attributeName]

    /**
     * What [version] decides, in general and for this schema.
     *
     * Answers specifically because the model already computes it: the same
     * [SolrSchemaVersion] drives every field-property popup's notion of what an undeclared
     * attribute falls back to, and this states it at the attribute that causes it.
     *
     * @param version the version the schema declares, or the assumed one where it declares none
     * @return a sentence naming the version and the defaults it selects
     */
    fun ofSchemaVersion(version: SolrSchemaVersion): String = buildString {
        append(
            "Selects the defaults Solr applies to attributes this schema does not declare. " +
                "Independent of the Solr release: a modern Solr honours an old schema's defaults, " +
                "which is how Solr changed them without breaking schemas already in the field. ",
        )
        append("At <code>${version.label}</code>, ")
        append(
            listOf(
                "<code>docValues</code> defaults ${onOrOff(version, DOC_VALUES_FROM)}",
                "<code>uninvertible</code> defaults ${onOrOff(version, null, UNINVERTIBLE_BELOW)}",
                "<code>autoGeneratePhraseQueries</code> defaults ${onOrOff(version, null, PHRASE_BELOW)}",
            ).joinToString("; "),
        )
        append(".")
    }

    /** Whether a default holds at [version], given the range that turns it on. */
    private fun onOrOff(version: SolrSchemaVersion, from: Float?, below: Float? = null): String =
        if (version in SolrVersionRange(from = from, below = below)) "on" else "off"

    /** The first schema version that turns `docValues` on for the types that support it. */
    private const val DOC_VALUES_FROM = 1.7f

    /** The first schema version that stops defaulting `uninvertible` on. */
    private const val UNINVERTIBLE_BELOW = 1.7f

    /** The first schema version that stops defaulting `autoGeneratePhraseQueries` on. */
    private const val PHRASE_BELOW = 1.4f

    /**
     * The attributes that make a schema a graph rather than text.
     *
     * These are the ones a reader follows — a field names its type, a copy rule names two fields —
     * and none of them explained itself before.
     */
    private val STRUCTURAL: Map<Pair<String, String>, String> = mapOf(
        ("field" to "name") to
            "The name documents use for this field, and the name a query, a copy rule or a handler " +
            "parameter writes to reach it.",
        ("dynamicField" to "name") to
            "A glob — <code>*_t</code>, <code>attr_*</code> — matching field names no " +
            "<code>&lt;field&gt;</code> declares. A document may use any name it matches, and Solr " +
            "prefers a declared field over any pattern, then the longest matching pattern.",
        ("field" to "type") to
            "Names a <code>&lt;fieldType&gt;</code> this schema declares, which decides how values " +
            "are analysed and therefore what a search on this field can match.",
        ("dynamicField" to "type") to
            "Names a <code>&lt;fieldType&gt;</code> this schema declares, applied to every field " +
            "name this pattern supplies.",
        ("fieldType" to "name") to
            "The name fields reference from their <code>type</code>. Local to this schema; it is " +
            "not a Solr-wide identifier.",
        ("fieldType" to "class") to
            "The Java class implementing this type. Decides the value's storage shape and which " +
            "analysis a chain may perform; <code>solr.TextField</code> is the analysed one, " +
            "<code>solr.StrField</code> stores the value whole.",
        ("copyField" to "source") to
            "The field values are copied <em>from</em>, at index time only. May be a dynamic " +
            "pattern, in which case every field it supplies is copied.",
        ("copyField" to "dest") to
            "The field values are copied <em>into</em>, at index time only. Copying happens before " +
            "analysis, so the destination analyses the raw value with its own chain rather than " +
            "inheriting the source's.",
        ("schema" to "name") to
            "Identifies the schema for a human reading it. Carries no behaviour — Solr does not " +
            "resolve anything by this name.",
        ("schema" to "version") to
            "Selects the defaults Solr applies to undeclared attributes. See below.",
    )

    /**
     * What the common factory attributes do.
     *
     * Deliberately partial. Everything here is a fact taken from Solr's Reference Guide, and
     * everything absent keeps the catalog-built popup it already had.
     */
    private val FACTORY: Map<String, String> = mapOf(
        "minGramSize" to
            "The shortest gram to index. At 2, a value is indexed as fragments from two characters " +
            "up, so a two-character query can match it.",
        "maxGramSize" to
            "The longest gram to index. Fragments beyond this length are not indexed, so a query " +
            "longer than it cannot match on this field — index size grows with the span.",
        "preserveOriginal" to
            "Also index the whole term alongside the generated fragments, so an exact match still " +
            "works where the fragments alone would not cover it.",
        "generateWordParts" to
            "Split a term at case and alphabetic/numeric transitions — <code>PowerShot</code> " +
            "becomes <code>Power</code> and <code>Shot</code>.",
        "generateNumberParts" to
            "Split a term at its numeric boundaries, so <code>SD500</code> yields <code>500</code>.",
        "catenateWords" to
            "Also index the word parts joined back together, so <code>wi-fi</code> matches " +
            "<code>wifi</code>.",
        "catenateNumbers" to "Also index consecutive numeric parts joined together.",
        "catenateAll" to "Also index every part joined into one term, words and numbers alike.",
        "splitOnCaseChange" to
            "Treat a case transition as a word boundary. Placed after a lowercasing filter it can " +
            "never fire, because the case it splits on is already gone.",
        "splitOnNumerics" to "Treat a digit next to a letter as a word boundary.",
        "stemEnglishPossessive" to "Remove a trailing <code>'s</code> from each part.",
        "words" to
            "A file of terms, one per line, relative to the configset directory. Edits to it need a " +
            "core reload before Solr reads them.",
        "synonyms" to
            "A file of synonym rules relative to the configset directory. Applied at the position " +
            "the filter sits in the chain, so index-time and query-time placement behave differently.",
        "protected" to "A file of terms this filter must leave untouched.",
        "ignoreCase" to "Match the terms in the file without regard to case.",
        "expand" to
            "Map each synonym group to every one of its members rather than to a single " +
            "representative term.",
        "mapping" to "A file of character-mapping rules relative to the configset directory.",
        "pattern" to "A regular expression the filter matches against each term.",
        "replacement" to "The text substituted where <code>pattern</code> matches.",
        "maxTokenLength" to "Terms longer than this are split at the limit rather than dropped.",
        "mode" to
            "The segmentation mode. <code>search</code> favours shorter segments, which suits " +
            "search over compound words; <code>normal</code> favours longer ones.",
        "userDictionary" to
            "A file of custom segmentation rules relative to the configset directory, for terms the " +
            "built-in dictionary segments differently than you want.",
    )
}
