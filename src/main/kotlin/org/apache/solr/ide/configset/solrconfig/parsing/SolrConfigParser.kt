package org.apache.solr.ide.configset.solrconfig.parsing

import org.apache.solr.ide.configset.reading.SolrXmlDocuments
import org.apache.solr.ide.configset.reading.SolrXmlDocuments.attributeOrNull
import org.apache.solr.ide.configset.reading.SolrXmlDocuments.children
import org.apache.solr.ide.configset.reading.SolrXmlDocuments.descendantsNamed
import org.apache.solr.ide.model.SolrConfigsetFacts
import org.apache.solr.ide.model.query.SolrBoostOccurrence
import org.apache.solr.ide.model.query.SolrQueryFields
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
     * What [parameterName] asks of the field it names, where the plugin can say.
     *
     * These four delegate to [SolrQueryFields], which owns this grammar now that `solrconfig.xml` is
     * not its only reader. They stay here as the names the solrconfig surfaces already call: five
     * call sites reaching past this object for one lookup each would be churn without a reader.
     *
     * @param parameterName the parameter as `solrconfig.xml` spells it
     * @return the operation it asks for, or null
     */
    internal fun operationFor(parameterName: String): SolrFieldOperation? =
        SolrQueryFields.operationFor(parameterName)

    /**
     * The partial field name at [caretOffset] inside [value], or null when that offset is not in one.
     *
     * @param parameterName the parameter as `solrconfig.xml` spells it
     * @param value the parameter's whole text
     * @param caretOffset an offset within [value]
     * @return the token being typed, possibly empty, or null when a field name cannot go there
     */
    internal fun fieldTokenAt(parameterName: String, value: String, caretOffset: Int): String? =
        SolrQueryFields.tokenAt(parameterName, value, caretOffset)

    /**
     * The boost under the caret, or null when the caret is not inside one.
     *
     * @param parameterName the parameter as `solrconfig.xml` spells it
     * @param value the parameter's whole text
     * @param caretOffset an offset within [value]
     * @return the boost at that offset, or null when no boost is there
     */
    internal fun boostAt(parameterName: String, value: String, caretOffset: Int): SolrBoostOccurrence? =
        SolrQueryFields.boostAt(parameterName, value, caretOffset)

    /**
     * Whether [parameterName] is one whose value names fields.
     *
     * @param parameterName the parameter as `solrconfig.xml` spells it
     * @return true if a value of this parameter contains field names
     */
    internal fun holdsFieldNames(parameterName: String): Boolean =
        SolrQueryFields.holdsFieldNames(parameterName)

    private fun referencesIn(owner: String, parameterName: String, parameter: Element): List<SolrFieldReference> {
        // An `arr` holds one value per child; a `str` holds them all in its text.
        val values = if (parameter.tagName == "arr") {
            parameter.children().map { it.textContent }
        } else {
            listOf(parameter.textContent)
        }
        return values.flatMap { value -> SolrQueryFields.namesIn(parameterName, value) }
            .map { SolrFieldReference(owner, parameterName, it.name, it.boost) }
    }

    /** `lst` names whose contents are query parameters. */
    private val PARAMETER_SETS = setOf("defaults", "appends", "invariants")

    /**
     * Tags whose text holds a parameter value, shared with the PSI half.
     *
     * Solr's scalar value tags. `arr` is handled beside this rather than in it, because it holds no
     * text of its own — its children do.
     */
    internal val VALUE_TAGS = setOf("str", "int", "long", "float", "double", "bool")
}
