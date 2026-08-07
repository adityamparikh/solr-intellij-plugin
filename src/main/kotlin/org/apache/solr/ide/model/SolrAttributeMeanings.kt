package org.apache.solr.ide.model

/**
 * What an attribute *means*, as opposed to what type it holds or what it defaults to.
 *
 * A tag offers a caret three positions and, before this, two of them answered: the element explained
 * itself and so did an attribute's value, while the attribute's own name said nothing. This is the
 * middle one.
 *
 * **Hand-maintained, and deliberately confined to the attributes that make a schema a graph rather
 * than text.** These ten are not reachable by the machinery that generates the rest: no bytecode
 * anywhere states what a `copyField`'s `dest` is for, because nothing reads it as a named argument —
 * the meaning lives in Solr's copy-rule handling, not in an enumerable structure. They also *define*
 * semantics rather than enumerating what happens to exist, they have not changed in the lifetime of
 * the schema format, and there are ten of them. That is the same exception
 * [SolrFieldProperties] already argues for its own table, met a second time.
 *
 * **What this deliberately does not carry is prose for the analysis factories' attributes**, and the
 * reason is a rule this project has written down: quick documentation *links* to the Reference Guide
 * rather than embedding it, because carrying that prose would mean maintaining a second body of
 * documentation going stale on its own schedule. What `minGramSize` does is Reference Guide content
 * for a vocabulary of some 130 factories that grows every time another attribute is worth
 * explaining, and the factory popup already links to the page that documents it. An earlier revision
 * of this file carried that table; it was removed rather than argued past, because a written rule
 * eroded inside an unrelated change is a rule nobody decided to drop.
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
        // Deliberately terse: the paragraph beneath it, built by [ofSchemaVersion], carries the
        // explanation *and* what this file's value decides. A sentence here that restated the
        // general rule would render immediately above its own repetition — which is exactly how a
        // sandbox pass found it.
        ("schema" to "version") to
            "The schema format version, from <code>1.0</code> to <code>1.7</code>.",
    )

}
