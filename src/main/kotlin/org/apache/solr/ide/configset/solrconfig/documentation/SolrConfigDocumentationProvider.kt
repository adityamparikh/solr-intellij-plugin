package org.apache.solr.ide.configset.solrconfig.documentation

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
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
        return contextElement?.parentOfType<XmlText>(withSelf = true)
            ?.takeIf { parserNameAt(it) != null }
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
        val text = element as? XmlText ?: return null
        val entry = parserNameAt(text) ?: return null
        return SolrConfigPresentation.parserNameDocumentation(entry)
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
