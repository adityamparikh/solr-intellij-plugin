package org.apache.solr.ide.configset.parsing

import org.apache.solr.ide.model.SolrConfigsetFacts
import org.apache.solr.ide.model.SolrFieldOperation
import org.apache.solr.ide.model.SolrFieldReference
import org.apache.solr.ide.configset.parsing.SolrXmlDocuments.attributeOrNull
import org.apache.solr.ide.configset.parsing.SolrXmlDocuments.children
import org.apache.solr.ide.configset.parsing.SolrXmlDocuments.descendantsNamed
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
        in PLAIN_PARAMETERS -> value.trim().split(",", " ").mapNotNull { plainFieldName(it) }.map { it to null }
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

    /** Parameters holding comma-separated `field direction` clauses. */
    private val SORT_PARAMETERS = setOf("sort", "group.sort")

    /** Parameters holding plain field names, one or several. */
    private val PLAIN_PARAMETERS = setOf(
        "df", "fl", "facet.field", "group.field", "hl.fl", "uniqueKey", "facet.pivot", "stats.field",
    )
}
