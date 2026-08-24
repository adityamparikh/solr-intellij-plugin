package org.apache.solr.ide.configset.solrconfig.documentation

import org.apache.solr.ide.model.query.SolrBoostOccurrence
import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.FakePsiElement
import com.intellij.psi.util.parentOfType
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText
import org.apache.solr.ide.configset.activation.SolrConfigsetDetector
import org.apache.solr.ide.configset.activation.SolrConfigsetFileKind
import org.apache.solr.ide.configset.reading.SolrConfigsetReader
import org.apache.solr.ide.configset.solrconfig.SolrConfigParameters
import org.apache.solr.ide.configset.solrconfig.parsing.SolrConfigParser
import org.apache.solr.ide.model.SolrVersionSelection
import org.apache.solr.ide.model.schema.SolrFieldOperation
import org.apache.solr.ide.model.schema.SolrFieldOperations
import org.apache.solr.ide.model.vocabulary.SolrParameterCatalog
import org.apache.solr.ide.model.vocabulary.SolrParameterEntry

/**
 * What a `solrconfig.xml` parameter is, and what a `defType` value means.
 *
 * **In `solrconfig` rather than in a shared package, because both positions are only ever in this
 * file.** A parameter name is written in a `<lst name="defaults">` and nowhere else, and a `defType`
 * holds a parser name in no other configset file — so unlike a `class` attribute, which is written in
 * both and therefore belongs to `navigation`, these two belong to the aspect whose file they live in.
 *
 * **What it declines is the contract.** `solrconfig.xml` accepts components from outside Solr that read
 * parameters of their author's choosing, so a name the generated resource does not carry is the ordinary
 * case and must produce nothing at all — not an empty popup, which is a claim, and certainly not a
 * warning. The same vow the `class` popup takes for a custom plugin class.
 */
class SolrConfigDocumentationProvider : AbstractDocumentationProvider(), DumbAware {

    /**
     * The parameter name or `defType` value under the caret, or null when neither is there.
     *
     * @param editor the editor the caret is in
     * @param file the file being edited
     * @param contextElement the element under the caret
     * @param targetOffset the caret offset
     * @return the attribute value or text this documents, or null
     */
    override fun getCustomDocumentationElement(
        editor: Editor,
        file: PsiFile,
        contextElement: PsiElement?,
        targetOffset: Int,
    ): PsiElement? {
        if (SolrConfigsetFileKind.forFileName(file.name)?.isSolrConfig != true) return null
        if (!SolrConfigsetDetector.isConfigsetFile(file)) return null
        // Claimed only where something is actually known, so a `name` this resource does not carry falls
        // through to whatever else would have answered rather than being swallowed by an empty popup.
        contextElement?.parentOfType<XmlAttributeValue>(withSelf = true)
            ?.takeIf { parameterAt(it) != null }
            ?.let { return it }
        val text = contextElement?.parentOfType<XmlText>(withSelf = true) ?: return null
        boostAt(text, targetOffset)?.let { return SolrBoostElement(text, it) }
        return text.takeIf { parserNameAt(it) != null }
    }

    /**
     * The boost at [offset] within [text], or null when the caret is not in one.
     *
     * The offset is why this needs an element of its own: a boost is a range inside a text node
     * rather than a node, and `name^3 title^5` holds two of them, so the position cannot be
     * recovered from the element alone the way a `defType` value can.
     */
    private fun boostAt(text: XmlText, offset: Int): SolrBoostOccurrence? {
        val parameterName = SolrConfigParameters.parameterNameOf(text.parentTag ?: return null) ?: return null
        if (!SolrConfigParameters.enclosingIsParameterList(text.parentTag?.parentTag)) return null
        return SolrConfigParser.boostAt(parameterName, text.text, offset - text.textRange.startOffset)
    }

    /**
     * The popup for whichever of the two [element] is.
     *
     * @param element the element chosen by [getCustomDocumentationElement]
     * @param originalElement the element originally under the caret
     * @return HTML for the popup, or null
     */
    override fun generateDoc(element: PsiElement, originalElement: PsiElement?): String? {
        (element as? XmlAttributeValue)?.let { value ->
            val entry = parameterAt(value) ?: return null
            return SolrConfigPresentation.parameterDocumentation(entry)
        }
        (element as? SolrBoostElement)?.let { boost ->
            return SolrConfigPresentation.boostDocumentation(boost.occurrence, boostedField(boost))
        }
        val text = element as? XmlText ?: return null
        val entry = parserNameAt(text) ?: return null
        return SolrConfigPresentation.parserNameDocumentation(entry)
    }

    /**
     * What the configset resolves the boosted field to, or null when there is nothing to say.
     *
     * Null covers three cases that all end the same way: the boost follows something that is not a
     * field name, the parameter's values are function queries, or the schema does not declare the
     * name. The last is
     * [org.apache.solr.ide.configset.solrconfig.inspection.SolrUnknownFieldReferenceInspection]'s to
     * report, and repeating it in a popup would be a second voice on one mistake.
     *
     * Whether the field is searchable is asked of
     * [org.apache.solr.ide.model.schema.SolrFieldOperations], the same disjunction over `indexed`
     * and `docValues` the relevance inspection asks, so the popup and the warning cannot disagree.
     */
    private fun boostedField(boost: SolrBoostElement): SolrConfigPresentation.BoostedField? {
        val fieldName = boost.occurrence.fieldName ?: return null
        val model = SolrConfigsetReader.getInstance(boost.project)
            .modelFor(boost.containingFile?.originalFile ?: return null) ?: return null
        val field = model.resolve(fieldName) ?: return null
        val fieldType = model.typeOf(field)
        return SolrConfigPresentation.BoostedField(
            name = fieldName,
            searchable = SolrFieldOperations.supports(
                SolrFieldOperation.SEARCH,
                field,
                fieldType,
                model.schemaVersion,
                model.traitsOf(fieldType),
            ),
        )
    }

    /**
     * A boost, as something the platform can hold on to.
     *
     * A boost is a range inside a text node and not a node, so there is nothing real to return from
     * [getCustomDocumentationElement] — and the offset that identifies which boost cannot be
     * recovered later, because [generateDoc] is handed an element and no caret. This carries the
     * answer computed while the offset was still in hand.
     *
     * **[getName] is not optional here, and its absence is not a compile error.** The hover path
     * computes a presentation for the target alongside the documentation, and
     * `targetPresentation` refuses an element it cannot name — so without this the popup threw with
     * its HTML already built and correct. It shipped to a sandbox that way, because a test that
     * asks only for the HTML asks for half of what a reader's hover does.
     */
    private class SolrBoostElement(
        private val text: XmlText,
        val occurrence: SolrBoostOccurrence,
    ) : FakePsiElement() {
        override fun getParent(): PsiElement = text
        override fun getTextRange(): TextRange = text.textRange

        /** What the popup's header shows: the boost as it is written, marker and all. */
        override fun getName(): String = "^${occurrence.boost}"
    }

    /**
     * The parameter this attribute value names, or null when it is not a parameter name at all.
     *
     * The position test is the parser's own, so a `<str name="…">` under an update processor chain — a
     * parameter list Solr reads for something that is not a query — answers nothing here, exactly as it
     * offers nothing in completion and resolves nothing in navigation.
     */
    private fun parameterAt(value: XmlAttributeValue): SolrParameterEntry? {
        val attribute = value.parentOfType<XmlAttribute>() ?: return null
        if (attribute.name != "name") return null
        val tag = attribute.parentOfType<XmlTag>() ?: return null
        if (tag.name !in SolrConfigParser.VALUE_TAGS && tag.name != "arr") return null
        if (!SolrConfigParameters.enclosingIsParameterList(tag.parentTag)) return null
        val name = value.value.takeIf { it.isNotEmpty() } ?: return null
        return SolrParameterCatalog.parameter(name, versionFor(value))
    }

    /** The query parser this `defType` names, or null when this text is not a `defType` value. */
    private fun parserNameAt(text: XmlText): SolrParameterEntry? {
        val tag = text.parentOfType<XmlTag>() ?: return null
        if (SolrConfigParameters.parameterNameOf(tag) != "defType") return null
        val written = text.value.trim().takeIf { it.isNotEmpty() } ?: return null
        return SolrParameterCatalog.queryParserName(written, versionFor(text))
    }

    private fun versionFor(element: PsiElement): SolrVersionSelection {
        val file = element.containingFile?.originalFile ?: return SolrVersionSelection.DEFAULT
        return SolrConfigsetReader.getInstance(element.project).modelFor(file)?.solrVersion
            ?: SolrVersionSelection.DEFAULT
    }
}
