package org.apache.solr.ide.model.query

import org.apache.solr.ide.model.schema.SolrFieldOperation

/**
 * A field name written in a query parameter's value, with the boost that follows it.
 *
 * @property name the field name as written, with no boost and no surrounding syntax
 * @property boost the text after the `^`, or null when the name carries none. Kept as text rather
 *   than parsed: Solr rejects a non-numeric boost and the plugin says so, which it cannot do if the
 *   text is discarded on the way in
 */
data class SolrQueryFieldName(val name: String, val boost: String?)

/**
 * A `^`-boost written in a parameter value, at a caret.
 *
 * @property parameterName the parameter holding it, which decides what the boost scales
 * @property boost the text after the `^`, empty when nothing has been written yet
 * @property fieldName the field the boost applies to, or null when there is not one — a token that
 *   is not a plain field name, or a parameter whose values are function queries
 */
data class SolrBoostOccurrence(
    val parameterName: String,
    val boost: String,
    val fieldName: String?,
)

/**
 * Which Solr query parameters name fields, and which names a value contains.
 *
 * **The grammar of a query parameter's value, in one place, because it has more than one reader.**
 * `solrconfig.xml` writes these parameters inside `<lst name="defaults">`; a Java or Kotlin file
 * writes the same strings into `SolrQuery.addFilterQuery` and its siblings; the query console will
 * write them directly. All three ask the same two questions — does this parameter name fields, and
 * which names are in this value — and all three must answer them identically, because the promise
 * the plugin is sold on is that a name it offers is a name that resolves.
 *
 * This lived inside `SolrConfigParser` while `solrconfig.xml` was the only reader, and the KDoc on
 * [tokenAt] records what happened the one time a second copy of these rules existed. Moving it here
 * is that lesson applied before the second reader arrives rather than after.
 *
 * **In `model` because it imports nothing from IntelliJ.** It is a function from two strings to a
 * list of names, which is what makes it testable without a fixture and reusable by a reader that has
 * no PSI at all. Distinct from `org.apache.solr.ide.model.vocabulary`, which is about what an XML
 * element may contain: these parameters are not XML, and reach the plugin from three directions of
 * which XML is only one.
 *
 * Only parameters *known* to name fields are read. Solr has hundreds and guessing which hold field
 * names from their values would produce false references, which are worse than missing ones: a false
 * reference becomes a false "no such field" warning on a parameter that was never a field name.
 */
object SolrQueryFields {

    /**
     * Whether [parameterName] is one whose value names fields.
     *
     * @param parameterName the parameter as it is written
     * @return true if a value of this parameter contains field names
     */
    fun holdsFieldNames(parameterName: String): Boolean =
        parameterName in BOOSTABLE_PARAMETERS ||
            parameterName in SORT_PARAMETERS ||
            parameterName in PLAIN_PARAMETERS

    /**
     * What [parameterName] asks of the fields it names, where the plugin can say.
     *
     * @param parameterName the parameter as it is written
     * @return the operation it asks for, or null where there is no single answer
     */
    fun operationFor(parameterName: String): SolrFieldOperation? = OPERATIONS[parameterName]

    /**
     * The field names in [value], in the order they appear.
     *
     * @param parameterName the parameter whose value this is, which decides how it splits
     * @param value the parameter's whole text
     * @return the names it references, empty when the parameter names no fields
     */
    fun namesIn(parameterName: String, value: String): List<SolrQueryFieldName> = when (parameterName) {
        in BOOSTABLE_PARAMETERS -> value.trim().split(WHITESPACE).mapNotNull { boostedFieldName(it) }
        in SORT_PARAMETERS -> value.split(",").mapNotNull { clause ->
            clause.trim().split(WHITESPACE).firstOrNull()
                ?.let { plainFieldName(it) }
                ?.let { SolrQueryFieldName(it, null) }
        }
        in PLAIN_PARAMETERS -> value.trim().split(PLAIN_SEPARATORS)
            .mapNotNull { plainFieldName(it) }
            .map { SolrQueryFieldName(it, null) }
        else -> emptyList()
    }

    /**
     * The partial field name at [caretOffset] inside [value], or null when that offset is not in one.
     *
     * **Here because this is the grammar, and the grammar is here.** Completion needs to know where a
     * field name starts and whether the caret is in one at all. The alternative was handing it the
     * separator set, the boost marker and the sort-clause rule as three predicates and letting it
     * reassemble a parser from them — which it did, and the copy disagreed: it treated a comma as a
     * boundary in a `qf`, where [namesIn] splits boostable parameters on whitespace alone, so
     * completion offered names at a position no reference would ever resolve. The invariant the
     * feature is sold on — a name offered is a name that resolves — cannot survive the rule existing
     * twice.
     *
     * An empty return is a real answer: the caret sits where a field name may start and nothing has
     * been typed yet.
     *
     * @param parameterName the parameter whose value this is
     * @param value the parameter's whole text
     * @param caretOffset an offset within [value]
     * @return the token being typed, possibly empty, or null when a field name cannot go there
     */
    fun tokenAt(parameterName: String, value: String, caretOffset: Int): String? {
        if (!holdsFieldNames(parameterName)) return null
        val before = value.substring(0, caretOffset.coerceIn(0, value.length))

        // The same splits [namesIn] performs, so the boundaries agree by construction rather than by
        // two people remembering the same thing.
        val boostable = parameterName in BOOSTABLE_PARAMETERS
        val separators = if (boostable) WHITESPACE_CHARACTERS else WHITESPACE_CHARACTERS + ','
        val token = before.takeLastWhile { it !in separators }

        // A `^` puts the caret inside a boost rather than in the name it boosts.
        if (boostable && '^' in token) return null
        // A sort clause is `field direction`, so only its first token is a field.
        if (parameterName in SORT_PARAMETERS && before.substringAfterLast(',').trimStart() != token) {
            return null
        }
        return token
    }

    /**
     * The boost under the caret, or null when the caret is not inside one.
     *
     * **The sibling of [tokenAt] rather than a caller of it**, because the two want different halves
     * of the same split. Completion wants the token *before* the caret, since that is what it
     * replaces; documentation wants the whole token *under* it, so that a popup does not change as
     * the caret moves through an unchanged `^3`. They share the separator rules, which is what keeps
     * a boost's extent and a field name's extent the same decision.
     *
     * @param parameterName the parameter whose value this is
     * @param value the parameter's whole text
     * @param caretOffset an offset within [value]
     * @return the boost at that offset, or null when no boost is there
     */
    fun boostAt(parameterName: String, value: String, caretOffset: Int): SolrBoostOccurrence? {
        if (parameterName !in BOOSTABLE_PARAMETERS) return null
        val offset = caretOffset.coerceIn(0, value.length)

        // A caret *on* a separator is between tokens, not in one. Without this the popup attaches to
        // whichever token happens to be to the left, so hovering the gap in `name^3 title^5` claims
        // the first one's boost.
        if (offset < value.length && value[offset] in WHITESPACE_CHARACTERS) return null

        val start = value.lastIndexOfAny(WHITESPACE_CHARACTERS, offset - 1) + 1
        val end = value.indexOfAny(WHITESPACE_CHARACTERS, offset).let { if (it < 0) value.length else it }
        val token = value.substring(start, end)

        val caret = token.indexOf('^')
        if (caret < 0) return null
        // A caret before the `^` is in the name, which already answers; only at or after it is a boost.
        if (offset < start + caret) return null

        return SolrBoostOccurrence(
            parameterName = parameterName,
            boost = token.substring(caret + 1),
            fieldName = if (parameterName in FIELD_BOOST_PARAMETERS) {
                plainFieldName(token.substring(0, caret))
            } else {
                null
            },
        )
    }

    private fun boostedFieldName(token: String): SolrQueryFieldName? {
        val trimmed = token.trim().ifEmpty { return null }
        val caret = trimmed.indexOf('^')
        val name = if (caret >= 0) trimmed.substring(0, caret) else trimmed
        val boost = if (caret >= 0) trimmed.substring(caret + 1).takeIf { it.isNotEmpty() } else null
        return plainFieldName(name)?.let { SolrQueryFieldName(it, boost) }
    }

    /**
     * A bare field name, or null if [token] is something else wearing a field's clothes.
     *
     * Public because a single-field argument in code — `addField("*")` — needs the same exclusions a
     * field list applies to each of its elements, and a second copy of them is how the two would come
     * to disagree about whether a glob is a field.
     *
     * @param token one token, already separated from whatever surrounded it
     * @return the field name, or null where the token is syntax rather than a name
     */
    fun fieldNameOrNull(token: String): String? = plainFieldName(token)

    /**
     * A bare field name, or null if the token is something else wearing a field's clothes.
     *
     * Glob, function-query, alias and parameter-reference syntax all appear in these parameters and
     * none of them is a field name. Reading `max(price,0)` as a field would produce a warning about
     * a field nobody wrote.
     */
    private fun plainFieldName(token: String): String? {
        val name = token.trim()
        if (name.isEmpty()) return null
        if (name.any { it in EXCLUDED_CHARACTERS }) return null
        if (name.isNumericLiteral()) return null
        return name
    }

    /**
     * Whether the token is a number rather than a name.
     *
     * A constant is legal in two of these parameters and is not a field: `boost` takes a multiplier
     * and `bf` an additive function, so `<float name="boost">1.5</float>` is an ordinary way to write
     * a flat boost — and reading `1.5` as a field name produced a warning about a field nobody could
     * declare. The same value is writable as a `<str>`, so this belongs beside the other syntax rules
     * rather than with the tags that reach them.
     *
     * The first character is checked before parsing so that `NaN` and `Infinity`, which
     * [String.toDoubleOrNull] accepts, stay available as field names. They are poor names and they
     * are the author's to choose.
     */
    private fun String.isNumericLiteral(): Boolean =
        (first().isDigit() || first() in NUMBER_LEADS) && toDoubleOrNull() != null

    private val WHITESPACE = Regex("\\s+")

    /**
     * What separates one name from the next in `fl` and its kin: a comma, whitespace, or both.
     *
     * **A literal space is not the whole of whitespace, and reading it as such reported a field
     * nobody wrote.** These parameters are routinely written one name per line, which Solr accepts —
     * it splits on commas and whitespace alike. Splitting on `","` and `" "` left the newline inside
     * the token, and since a newline is not an excluded character the trimmed result was a single
     * "field" spelling two names with a line break in the middle. The unknown-field inspection then
     * reported it, on a file Solr reads correctly.
     *
     * The boostable parameters always split on [WHITESPACE] and so never had this; the two branches
     * disagreeing about what a separator is was the defect.
     */
    private val PLAIN_SEPARATORS = Regex("[,\\s]+")

    /** The characters [WHITESPACE] matches, for the caller that needs them one at a time. */
    private val WHITESPACE_CHARACTERS = charArrayOf(' ', '\t', '\n', '\r')

    /**
     * Characters that mark a token as syntax rather than a field name.
     *
     * Square brackets matter as much as parentheses: `[docid]`, `[explain]` and `[child]` are
     * document transformers, legal in an `fl`, and named like nothing in any schema. Reading one as
     * a field would produce a "no such field" warning on syntax that is entirely correct.
     */
    private val EXCLUDED_CHARACTERS = charArrayOf('*', '(', ')', ':', '$', '{', '}', '"', '\'', '[', ']')

    /** Characters a number may start with, besides a digit. */
    private val NUMBER_LEADS = charArrayOf('-', '+', '.')

    /**
     * What each parameter asks of the field it names, where the plugin can say.
     *
     * `fl` is absent deliberately: it returns a stored value and genuinely asks nothing of the index.
     * `bf` and `boost` ask for something real — a per-document value — but write it as a function
     * query rather than a field list, so a rule applied to a whole token there would be applied to
     * the wrong thing.
     */
    private val OPERATIONS = mapOf(
        // DisMax's query fields and edismax's inheritance of them, plus the phrase-field family: the
        // parameters whose values become term and phrase queries.
        "qf" to SolrFieldOperation.SEARCH,
        "pf" to SolrFieldOperation.SEARCH,
        "pf2" to SolrFieldOperation.SEARCH,
        "pf3" to SolrFieldOperation.SEARCH,
        // Every name in a pivot is faceted on, so a pivot is as much a facet as `facet.field`.
        "facet.field" to SolrFieldOperation.FACET,
        "facet.pivot" to SolrFieldOperation.FACET,
        // Grouping orders documents by the field's value and fails the way sorting does.
        "sort" to SolrFieldOperation.SORT,
        "group.sort" to SolrFieldOperation.SORT,
        "group.field" to SolrFieldOperation.SORT,
    )

    /** Parameters whose values may carry a `^`-boost. */
    private val BOOSTABLE_PARAMETERS = setOf("qf", "pf", "pf2", "pf3", "bf", "boost")

    /**
     * The boostable parameters whose boost follows a *field*.
     *
     * `bf` and `boost` are boostable and are not here, which is the same split the relevance
     * inspection makes and for the same reason: their values are function queries, so what precedes
     * the `^` is a function rather than a field name and must not be described as one.
     */
    private val FIELD_BOOST_PARAMETERS = setOf("qf", "pf", "pf2", "pf3")

    /** Parameters holding comma-separated `field direction` clauses. */
    private val SORT_PARAMETERS = setOf("sort", "group.sort")

    /** Parameters holding plain field names, one or several. */
    private val PLAIN_PARAMETERS = setOf(
        "df", "fl", "facet.field", "group.field", "hl.fl", "uniqueKey", "facet.pivot", "stats.field",
    )
}
