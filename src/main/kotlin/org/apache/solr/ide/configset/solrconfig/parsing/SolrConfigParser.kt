package org.apache.solr.ide.configset.solrconfig.parsing

import org.apache.solr.ide.configset.reading.SolrXmlDocuments
import org.apache.solr.ide.configset.reading.SolrXmlDocuments.attributeOrNull
import org.apache.solr.ide.configset.reading.SolrXmlDocuments.children
import org.apache.solr.ide.configset.reading.SolrXmlDocuments.descendantsNamed
import org.apache.solr.ide.model.SolrConfigsetFacts
import org.apache.solr.ide.model.schema.SolrFieldOperation
import org.apache.solr.ide.model.schema.SolrFieldReference
import org.w3c.dom.Element

/**
 * Parses `solrconfig.xml` for the field names it references.
 *
 * This is the file boundary the plugin exists to close. A handler's `qf` names fields that
 * `managed-schema.xml` declares, and nothing in either file connects them, so a field renamed in one
 * and not the other fails silently at query time rather than at reload.
 *
 * Only parameters *known* to name fields are read. Solr has hundreds of parameters and guessing
 * which hold field names from their values would produce false references, which are worse than
 * missing ones: a false reference becomes a false "no such field" warning on a parameter that was
 * never a field name at all.
 */
object SolrConfigParser {

    /**
     * Reads [xml] as a `solrconfig.xml`.
     *
     * @param xml the document's text
     * @return the field references it makes; empty facts if the text is not well-formed XML
     */
    fun parse(xml: CharSequence): SolrConfigsetFacts {
        val root = SolrXmlDocuments.rootOf(xml) ?: return SolrConfigsetFacts()
        val references = mutableListOf<SolrFieldReference>()

        // `initParams` carries the same parameter lists as a handler and applies them to several,
        // so its references are as real as a handler's. It is named for its `path`, which is what
        // the user would recognize.
        val carriers = root.descendantsNamed("requestHandler") +
            root.descendantsNamed("searchComponent") +
            root.descendantsNamed("initParams")

        for (carrier in carriers) {
            val owner = carrier.attributeOrNull("name") ?: carrier.attributeOrNull("path") ?: continue
            for (list in carrier.children().filter { it.tagName == "lst" }) {
                // `defaults`, `appends` and `invariants` all supply parameters to a query; only
                // their precedence differs, which does not change whether a name is a field.
                if (list.attributeOrNull("name") !in PARAMETER_SETS) continue
                for (parameter in list.children()) {
                    // The same tags the PSI half maps positions for. Reading *every* child was the
                    // wider of the two answers, and the difference was invisible only because nothing
                    // consumes this list directly: a parameter in a tag one half accepted and the
                    // other did not yielded a reference with no position to underline it at.
                    if (parameter.tagName !in VALUE_TAGS && parameter.tagName != "arr") continue
                    val parameterName = parameter.attributeOrNull("name") ?: continue
                    references += referencesIn(owner, parameterName, parameter)
                }
            }
        }
        return SolrConfigsetFacts(
            fieldReferences = references,
            luceneMatchVersion = root.descendantsNamed("luceneMatchVersion").firstOrNull()
                ?.textContent?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /**
     * Whether [parameterName] is one this parser reads field names out of.
     *
     * Asked rather than restated by everything that needs the answer, so that the positions producing
     * references and the positions offering them cannot diverge. It also lets a caller skip the work
     * entirely: most parameters in a `<lst name="defaults">` hold a number or a parser name, and
     * discovering that after parsing is discovering it too late.
     *
     * @param parameterName the parameter as `solrconfig.xml` spells it
     * @return whether its value holds field names
     */
    /**
     * The operation [parameterName] asks of the field it names, or null when it asks nothing that can
     * be checked.
     *
     * **Here rather than in `model` because a parameter name is this file's vocabulary, not a fact
     * about what a configset means.** `model` earns its exception by being what every surface reads;
     * a table only the configuration surface can read would not qualify, and the SolrJ surface will
     * map its own call names onto the same operations without going through `solrconfig.xml`
     * spellings. It also has to agree with the three parameter sets below, and agreement is easier to
     * keep when both live in one file.
     *
     * Null means *offer everything and report nothing*, covering two cases worth not conflating. `fl`
     * returns a stored value and genuinely asks nothing of the index. `bf` and `boost` ask for
     * something real — a per-document value — but write it as a function query rather than a field
     * list, so a rule applied to a whole token there would be applied to the wrong thing.
     *
     * @param parameterName the parameter as `solrconfig.xml` spells it
     * @return the operation it asks for, or null
     */
    internal fun operationFor(parameterName: String): SolrFieldOperation? = OPERATIONS[parameterName]

    /**
     * The partial field name at [caretOffset] inside [value], or null when that offset is not in one.
     *
     * **Here because this is the grammar, and the grammar is already here.** Completion needs to know
     * where a field name starts and whether the caret is in one at all. The alternative was handing it
     * the separator set, the boost marker and the sort-clause rule as three predicates and letting it
     * reassemble a parser from them — which it did, and the copy disagreed: it treated a comma as a
     * boundary in a `qf`, where [fieldNamesIn] splits boostable parameters on whitespace alone, so
     * completion offered names at a position no reference would ever resolve. The invariant the feature
     * is sold on — a name offered is a name that resolves — cannot survive the rule existing twice.
     *
     * An empty return is a real answer: the caret sits where a field name may start and nothing has been
     * typed yet.
     *
     * @param parameterName the parameter as `solrconfig.xml` spells it
     * @param value the parameter's whole text
     * @param caretOffset an offset within [value]
     * @return the token being typed, possibly empty, or null when a field name cannot go there
     */
    internal fun fieldTokenAt(parameterName: String, value: String, caretOffset: Int): String? {
        if (!holdsFieldNames(parameterName)) return null
        val before = value.substring(0, caretOffset.coerceIn(0, value.length))

        // The same splits [fieldNamesIn] performs, so the boundaries agree by construction rather than
        // by two people remembering the same thing.
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
     * A `^`-boost written in a parameter value, at the caret.
     *
     * @property parameterName the parameter holding it, which decides what the boost scales
     * @property boost the text after the `^`, empty when nothing has been written yet. Not parsed as
     *   a number here: Solr rejects a non-numeric boost and the popup says so, which it cannot do if
     *   this discards the text
     * @property fieldName the field the boost applies to, or null when there is not one — a token
     *   that is not a plain field name, or a parameter whose values are function queries
     */
    internal data class SolrBoostOccurrence(
        val parameterName: String,
        val boost: String,
        val fieldName: String?,
    )

    /**
     * The boost under the caret, or null when the caret is not inside one.
     *
     * **The sibling of [fieldTokenAt] rather than a caller of it**, because the two want different
     * halves of the same split. Completion wants the token *before* the caret, since that is what it
     * replaces; documentation wants the whole token *under* it, so that a popup does not change as
     * the caret moves through an unchanged `^3`. They share the separator rules, which is what keeps
     * a boost's extent and a field name's extent the same decision.
     *
     * @param parameterName the parameter as `solrconfig.xml` spells it
     * @param value the parameter's whole text
     * @param caretOffset an offset within [value]
     * @return the boost at that offset, or null when no boost is there
     */
    internal fun boostAt(parameterName: String, value: String, caretOffset: Int): SolrBoostOccurrence? {
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
            fieldName = if (parameterName in FIELD_BOOST_PARAMETERS) plainFieldName(token.substring(0, caret)) else null,
        )
    }

    internal fun holdsFieldNames(parameterName: String): Boolean =
        parameterName in BOOSTABLE_PARAMETERS ||
            parameterName in SORT_PARAMETERS ||
            parameterName in PLAIN_PARAMETERS

    private fun referencesIn(owner: String, parameterName: String, parameter: Element): List<SolrFieldReference> {
        // An `arr` holds one value per child; a `str` holds them all in its text.
        val values = if (parameter.tagName == "arr") {
            parameter.children().map { it.textContent }
        } else {
            listOf(parameter.textContent)
        }
        return values.flatMap { value -> fieldNamesIn(parameterName, value) }
            .map { (fieldName, boost) -> SolrFieldReference(owner, parameterName, fieldName, boost) }
    }

    private fun fieldNamesIn(parameterName: String, value: String): List<Pair<String, String?>> = when (parameterName) {
        in BOOSTABLE_PARAMETERS -> value.trim().split(WHITESPACE).mapNotNull { boostedFieldName(it) }
        in SORT_PARAMETERS -> value.split(",").mapNotNull { clause ->
            clause.trim().split(WHITESPACE).firstOrNull()?.let { plainFieldName(it) }?.let { it to null }
        }
        in PLAIN_PARAMETERS -> value.trim().split(PLAIN_SEPARATORS).mapNotNull { plainFieldName(it) }.map { it to null }
        else -> emptyList()
    }

    private fun boostedFieldName(token: String): Pair<String, String?>? {
        val trimmed = token.trim().ifEmpty { return null }
        val caret = trimmed.indexOf('^')
        val name = if (caret >= 0) trimmed.substring(0, caret) else trimmed
        val boost = if (caret >= 0) trimmed.substring(caret + 1).takeIf { it.isNotEmpty() } else null
        return plainFieldName(name)?.let { it to boost }
    }

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
     * A constant is legal in two of these parameters and is not a field: `boost` takes a multiplier and
     * `bf` an additive function, so `<float name="boost">1.5</float>` is an ordinary way to write a flat
     * boost — and reading `1.5` as a field name produced a warning about a field nobody could declare.
     * The same value is writable as a `<str>`, so this belongs beside the other syntax rules rather than
     * with the tags that reach them.
     *
     * The first character is checked before parsing so that `NaN` and `Infinity`, which
     * [String.toDoubleOrNull] accepts, stay available as field names. They are poor names and they are
     * the author's to choose.
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
     * The boostable parameters one branch above always split on [WHITESPACE] and so never had this;
     * the two branches disagreeing about what a separator is was the defect.
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

    /** `lst` names whose contents are query parameters. */
    private val PARAMETER_SETS = setOf("defaults", "appends", "invariants")

    /**
     * Tags whose text holds a parameter value, shared with the PSI half.
     *
     * Solr's scalar value tags. `arr` is handled beside this rather than in it, because it holds no
     * text of its own — its children do.
     */
    internal val VALUE_TAGS = setOf("str", "int", "long", "float", "double", "bool")

    /**
     * What each parameter asks of the field it names, where the plugin can say.
     *
     * A subset of the three sets below: every entry here holds field names, and not every parameter
     * holding field names asks a checkable question of them.
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

    /** Parameters holding whitespace-separated field names, each optionally `^`-boosted. */
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
