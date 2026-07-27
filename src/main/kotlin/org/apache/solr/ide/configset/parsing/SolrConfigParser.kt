package org.apache.solr.ide.configset.parsing

import org.apache.solr.ide.model.SolrConfigsetFacts
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
        return name
    }

    private val WHITESPACE = Regex("\\s+")

    /**
     * Characters that mark a token as syntax rather than a field name.
     *
     * Square brackets matter as much as parentheses: `[docid]`, `[explain]` and `[child]` are
     * document transformers, legal in an `fl`, and named like nothing in any schema. Reading one as
     * a field would produce a "no such field" warning on syntax that is entirely correct.
     */
    private val EXCLUDED_CHARACTERS = charArrayOf('*', '(', ')', ':', '$', '{', '}', '"', '\'', '[', ']')

    /** `lst` names whose contents are query parameters. */
    private val PARAMETER_SETS = setOf("defaults", "appends", "invariants")

    /** Parameters holding whitespace-separated field names, each optionally `^`-boosted. */
    private val BOOSTABLE_PARAMETERS = setOf("qf", "pf", "pf2", "pf3", "bf", "boost")

    /** Parameters holding comma-separated `field direction` clauses. */
    private val SORT_PARAMETERS = setOf("sort", "group.sort")

    /** Parameters holding plain field names, one or several. */
    private val PLAIN_PARAMETERS = setOf(
        "df", "fl", "facet.field", "group.field", "hl.fl", "uniqueKey", "facet.pivot", "stats.field",
    )
}
