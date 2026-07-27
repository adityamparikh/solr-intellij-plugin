package org.apache.solr.ide.configset.documentation

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.parentOfType
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag
import org.apache.solr.ide.configset.activation.SolrConfigsetDetector
import org.apache.solr.ide.model.SolrFieldModel
import org.apache.solr.ide.model.SolrReferenceGuide
import org.apache.solr.ide.model.SolrVersionSelection
import org.apache.solr.ide.configset.parsing.SolrConfigsetReader

/**
 * Quick documentation for fields and field types in a configset.
 *
 * Answers the question the Reference Guide cannot: not "what does `omitNorms` mean" in general, but
 * what it is *for this field in this schema*, and whether that value came from the field, from its
 * type, or from Solr's default. The guide is linked for the prose; the resolution is the part only
 * something reading the configset can do.
 *
 * Documentation is offered on attribute *values* rather than through references, because reference
 * resolution has not landed yet and this does not need it: the element under the caret carries
 * enough to answer.
 */
class SolrConfigsetDocumentationProvider : AbstractDocumentationProvider() {

    /**
     * Picks the element to document when the caret is inside a schema attribute value.
     *
     * Without this, quick documentation would need a resolved reference to hang off, and there are
     * none in a configset yet.
     *
     * @param editor the editor the caret is in
     * @param file the file being edited
     * @param contextElement the element under the caret
     * @param targetOffset the caret offset
     * @return the attribute value to document, or null
     */
    override fun getCustomDocumentationElement(
        editor: Editor,
        file: PsiFile,
        contextElement: PsiElement?,
        targetOffset: Int,
    ): PsiElement? {
        if (!SolrConfigsetDetector.isConfigsetFile(file)) return null
        return contextElement?.parentOfType<XmlAttributeValue>(withSelf = true)?.takeIf { documentedTarget(it) != null }
    }

    /**
     * The documentation popup for [element], or null when it is not something this documents.
     *
     * @param element the element chosen by [getCustomDocumentationElement]
     * @param originalElement the element originally under the caret
     * @return HTML for the popup, or null
     */
    override fun generateDoc(element: PsiElement, originalElement: PsiElement?): String? {
        val value = element as? XmlAttributeValue ?: return null
        val file = value.containingFile ?: return null
        if (!SolrConfigsetDetector.isConfigsetFile(file)) return null

        val configset = SolrConfigsetDetector.configsetFor(file) ?: return null
        val model = SolrConfigsetReader.getInstance(file.project).modelFor(configset)
        val version = versionOf(model)

        return when (val target = documentedTarget(value)) {
            is Target.Field -> {
                val field = model.fields[target.name]?.effective
                    ?: model.dynamicFields[target.name]?.effective?.field
                    ?: return null
                SolrFieldPresentation.fieldDocumentation(field, model.typeOf(field), version)
            }
            is Target.Type -> {
                val type = model.fieldTypes[target.name]?.effective ?: return null
                SolrFieldPresentation.fieldTypeDocumentation(type, version)
            }
            null -> null
        }
    }

    /**
     * The Reference Guide page for this element, offered as the popup's external-documentation link.
     *
     * Constructed, never fetched — the browser resolves it if the user asks for it.
     *
     * @param element the element being documented
     * @param originalElement the element originally under the caret
     * @return the external documentation URLs, or null when none applies
     */
    override fun getUrlFor(element: PsiElement?, originalElement: PsiElement?): List<String>? {
        val value = element as? XmlAttributeValue ?: return null
        val file = value.containingFile ?: return null
        if (!SolrConfigsetDetector.isConfigsetFile(file)) return null
        val configset = SolrConfigsetDetector.configsetFor(file) ?: return null
        val version = versionOf(SolrConfigsetReader.getInstance(file.project).modelFor(configset))
        return when (documentedTarget(value)) {
            is Target.Field -> listOf(SolrReferenceGuide.fieldPropertiesPage(version))
            is Target.Type -> listOf(SolrReferenceGuide.fieldTypesPage(version))
            null -> null
        }
    }

    /**
     * The Solr line this configset targets.
     *
     * Only two of the spec's three sources are available: the configset's own declaration, and the
     * default. A connected server would outrank both, and will once the server reader lands.
     */
    private fun versionOf(model: SolrFieldModel): SolrVersionSelection =
        model.luceneMatchVersion?.let { SolrVersionSelection.fromLuceneMatchVersion(it) }
            ?: SolrVersionSelection.DEFAULT

    private fun documentedTarget(value: XmlAttributeValue): Target? {
        val attribute = value.parentOfType<XmlAttribute>() ?: return null
        val tag = attribute.parentOfType<XmlTag>() ?: return null
        val name = value.value.takeIf { it.isNotEmpty() } ?: return null
        return when {
            tag.name in FIELD_TAGS && attribute.name == "type" -> Target.Type(name)
            tag.name in FIELD_TAGS && attribute.name == "name" -> Target.Field(name)
            tag.name in TYPE_TAGS && attribute.name == "name" -> Target.Type(name)
            else -> null
        }
    }

    private sealed interface Target {
        data class Field(val name: String) : Target
        data class Type(val name: String) : Target
    }

    private companion object {
        val FIELD_TAGS = setOf("field", "dynamicField")
        val TYPE_TAGS = setOf("fieldType", "fieldtype")
    }
}
