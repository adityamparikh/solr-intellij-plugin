package org.apache.solr.ide.configset.solrconfig

import com.intellij.openapi.util.TextRange
import com.intellij.psi.xml.XmlTag
import org.apache.solr.ide.configset.solrconfig.parsing.SolrConfigParser

/**
 * Where a `solrconfig.xml` parameter names schema fields, as positions in the PSI.
 *
 * [SolrConfigParser] decides *what counts* as a field reference — which parameters carry field
 * names, and how a value like `name^3 description` splits — and this object maps that decision
 * back onto the tag that holds the value, so the unknown-field inspection and the reference
 * provider annotate exactly the same positions. It lives in `parsing` because both consume it,
 * and features go through this package rather than through each other.
 */
internal object SolrConfigParameters {

    /**
     * One field name inside a parameter value.
     *
     * @property parameterName the parameter that holds it, as `solrconfig.xml` spells it. Carried
     *   because not every consumer treats every parameter alike: whether a name must be *declared*
     *   is the same question in `fl` as in `qf`, but whether it must be *indexed* is a question only
     *   the query-field parameters ask
     * @property fieldName the name as the parameter writes it
     * @property rangeInTag where it sits, relative to the start of the enclosing tag
     */
    data class FieldNameOccurrence(
        val parameterName: String,
        val fieldName: String,
        val rangeInTag: TextRange,
    )

    /**
     * The field-name occurrences inside [tag], or empty when it is not a parameter value.
     *
     * Every occurrence of each referenced name is reported, not just the first: underlining or
     * navigating only the first `title` in a value that names it twice is exactly the moment a
     * reader stops trusting the feature. Matches are whole tokens — reporting `name` must not
     * also cover the `name` inside `name_prefix`.
     */
    fun fieldNameOccurrences(tag: XmlTag): List<FieldNameOccurrence> {
        if (tag.name !in SolrConfigParser.VALUE_TAGS) return emptyList()
        val parameterName = parameterNameOf(tag) ?: return emptyList()
        // Before the parse, not after. [referencedFieldNames] builds and parses a synthetic document,
        // and most parameters in a `defaults` list hold a number or a parser name — so on a typical
        // handler this skips that work for the majority of tags on every highlighting pass.
        if (!SolrConfigParser.holdsFieldNames(parameterName)) return emptyList()
        val text = tag.value.text
        val valueStart = tag.value.textRange.startOffset - tag.textRange.startOffset

        val occurrences = mutableListOf<FieldNameOccurrence>()
        for (name in referencedFieldNames(parameterName, text)) {
            var index = text.indexOf(name)
            while (index >= 0) {
                if (isWholeToken(text, index, name.length)) {
                    occurrences += FieldNameOccurrence(
                        parameterName,
                        name,
                        TextRange(valueStart + index, valueStart + index + name.length),
                    )
                }
                index = text.indexOf(name, index + 1)
            }
        }
        return occurrences
    }

    /**
     * Whether the match at [index] is a whole token rather than part of a longer name.
     */
    private fun isWholeToken(text: String, index: Int, length: Int): Boolean {
        val before = text.getOrNull(index - 1)
        val after = text.getOrNull(index + length)
        return (before == null || before in SEPARATORS) && (after == null || after in SEPARATORS)
    }

    /**
     * The `name` of the enclosing parameter, or null when this tag is not inside a parameter list.
     *
     * An `<str>` inside an `<arr name="facet.field">` takes its parameter name from the array; one
     * directly under a `<lst name="defaults">` carries its own.
     *
     * **Internal because completion asks the same question.** Offering field names inside a parameter
     * value needs to know which parameter the caret is in, and that means both spellings — the tag's own
     * `name`, or the enclosing `arr`'s — plus the parameter-list check that makes either meaningful. A
     * second copy would drift from this one at the first of those three that changed.
     *
     * @param tag a candidate value tag
     * @return the parameter name, or null when the tag is not a parameter value
     */
    internal fun parameterNameOf(tag: XmlTag): String? {
        val own = tag.getAttributeValue("name")
        if (own != null) return own.takeIf { enclosingIsParameterList(tag.parentTag) }
        val parent = tag.parentTag ?: return null
        if (parent.name != "arr") return null
        return parent.getAttributeValue("name")?.takeIf { enclosingIsParameterList(parent.parentTag) }
    }

    /**
     * Whether [tag] is a parameter list belonging to something that supplies query parameters.
     *
     * The enclosing check is not optional. `<lst name="defaults">` also appears under elements that
     * have nothing to do with queries — an update processor chain, for one — and the parser already
     * declines to read those. Without this, positions would be reported that the model itself says
     * hold no field references.
     *
     * **Internal rather than private so that completion can ask the same question.** Offering parameter
     * names, or field names inside a parameter, depends on this exact position test, and a second copy
     * of it would agree until the day an update processor chain grew another parameter-list spelling.
     * One predicate, three callers.
     *
     * @param tag the candidate parameter list, usually a value tag's parent
     * @return whether its contents are query parameters
     */
    internal fun enclosingIsParameterList(tag: XmlTag?): Boolean =
        tag?.name == "lst" &&
            tag.getAttributeValue("name") in PARAMETER_SETS &&
            tag.parentTag?.name in PARAMETER_CARRIERS

    /**
     * The field names a parameter's text refers to, reusing the parser's rules rather than
     * restating them.
     *
     * Parsing a one-parameter document is wasteful in the abstract and free in practice — it runs
     * per parameter tag on a file the user is editing — and it guarantees the consumers and the
     * model can never disagree about what counts as a field reference.
     */
    private fun referencedFieldNames(parameterName: String, text: String): List<String> {
        val synthetic = """<config><requestHandler name="x"><lst name="defaults">""" +
            """<str name="${escapeXml(parameterName)}">${escapeXml(text)}</str>""" +
            """</lst></requestHandler></config>"""
        return SolrConfigParser.parse(synthetic).fieldReferences.map { it.fieldName }.distinct()
    }

    private fun escapeXml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")


    /** `lst` names whose contents are query parameters. */
    private val PARAMETER_SETS = setOf("defaults", "appends", "invariants")

    /**
     * Elements whose parameter lists supply a query.
     *
     * The same set the parser accepts, and for the same reason: a `<lst name="defaults">`
     * elsewhere in `solrconfig.xml` configures something that is not a query.
     */
    private val PARAMETER_CARRIERS = setOf("requestHandler", "searchComponent", "initParams")

    /** Characters that may sit either side of a field name in a parameter value. */
    private val SEPARATORS = charArrayOf(' ', ',', '\t', '\n', '\r', '^')
}
