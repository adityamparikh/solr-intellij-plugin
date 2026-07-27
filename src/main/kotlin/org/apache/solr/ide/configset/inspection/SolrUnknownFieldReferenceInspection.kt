package org.apache.solr.ide.configset.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.XmlElementVisitor
import com.intellij.psi.xml.XmlTag
import org.apache.solr.ide.SolrBundle
import org.apache.solr.ide.configset.parsing.SolrConfigParser
import org.apache.solr.ide.configset.parsing.SolrConfigsetReader

/**
 * Reports a request-handler parameter in `solrconfig.xml` naming a field the schema does not
 * declare.
 *
 * This is the file boundary the plugin exists to close. A `qf` naming a field that was renamed in
 * the schema is not an error to Solr — the parameter is simply a string, and a query using it
 * returns fewer results, or none, with no warning anywhere. Nothing in either file connects the
 * two, so nothing else can catch it.
 *
 * The precision rules live in [SolrConfigParser], which decides what counts as a field reference at
 * all, and in [SolrInspections], which decides which names a schema could be expected to declare.
 * Both matter more here than in the schema inspections: `fl` in particular is full of syntax that
 * resembles a field name, and every false positive lands on a file that is entirely correct.
 */
class SolrUnknownFieldReferenceInspection : LocalInspectionTool() {

    /**
     * @param holder collects the problems found
     * @param isOnTheFly whether this is an editor pass rather than a batch run
     * @return a visitor over parameter values, or an empty one outside a configset
     */
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        // Only `solrconfig.xml` carries these; running the visitor over a schema would be wasted
        // work on every keystroke.
        if (holder.file.name != "solrconfig.xml") return PsiElementVisitor.EMPTY_VISITOR
        val model = SolrConfigsetReader.getInstance(holder.project).modelFor(holder.file) ?: return PsiElementVisitor.EMPTY_VISITOR

        return object : XmlElementVisitor() {
            override fun visitXmlTag(tag: XmlTag) {
                if (tag.name !in VALUE_TAGS) return
                val parameterName = parameterNameOf(tag) ?: return
                val text = tag.value.text
                for (name in referencedFieldNames(parameterName, text)) {
                    if (!SolrInspections.isCheckableFieldName(name)) continue
                    if (SolrInspections.resolves(model, name)) continue
                    reportAll(holder, tag, text, name)
                }
            }
        }
    }

    /**
     * Registers a problem on each occurrence of [name] within the parameter's text.
     *
     * Highlighting the whole `<str>` would underline `name^3 description category` when only one of
     * the three is wrong, which is exactly the moment a reader stops trusting the underline.
     */
    private fun reportAll(holder: ProblemsHolder, tag: XmlTag, text: String, name: String) {
        val valueStart = tag.value.textRange.startOffset - tag.textRange.startOffset
        var index = text.indexOf(name)
        while (index >= 0) {
            if (isWholeToken(text, index, name.length)) {
                holder.registerProblem(
                    tag,
                    SolrBundle.message("inspection.fieldReference.unknown", name),
                    ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                    TextRange(valueStart + index, valueStart + index + name.length),
                )
            }
            index = text.indexOf(name, index + 1)
        }
    }

    /**
     * Whether the match at [index] is a whole token rather than part of a longer name.
     *
     * Without this, reporting `name` would also underline the `name` inside `name_prefix`.
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
     */
    private fun parameterNameOf(tag: XmlTag): String? {
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
     * declines to read those. Without this, the inspection reported field references the model
     * itself says do not exist, which is a warning on an entirely correct file.
     */
    private fun enclosingIsParameterList(tag: XmlTag?): Boolean =
        tag?.name == "lst" &&
            tag.getAttributeValue("name") in PARAMETER_SETS &&
            tag.parentTag?.name in PARAMETER_CARRIERS

    /**
     * The field names a parameter's text refers to, reusing the parser's rules rather than
     * restating them.
     *
     * Parsing a one-parameter document is wasteful in the abstract and free in practice — it runs
     * per parameter tag on a file the user is editing — and it guarantees the inspection and the
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

    private companion object {
        /** Tags whose text holds a parameter value. */
        val VALUE_TAGS = setOf("str")

        /** `lst` names whose contents are query parameters. */
        val PARAMETER_SETS = setOf("defaults", "appends", "invariants")

        /**
         * Elements whose parameter lists supply a query.
         *
         * The same set the parser accepts, and for the same reason: a `<lst name="defaults">`
         * elsewhere in `solrconfig.xml` configures something that is not a query.
         */
        val PARAMETER_CARRIERS = setOf("requestHandler", "searchComponent", "initParams")

        /** Characters that may sit either side of a field name in a parameter value. */
        val SEPARATORS = charArrayOf(' ', ',', '\t', '\n', '\r', '^')
    }
}
