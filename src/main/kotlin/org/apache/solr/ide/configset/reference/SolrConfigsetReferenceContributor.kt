package org.apache.solr.ide.configset.reference

import com.intellij.openapi.util.TextRange
import com.intellij.patterns.XmlPatterns
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.util.parentOfType
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag
import com.intellij.util.ProcessingContext
import org.apache.solr.ide.configset.activation.SolrConfigsetDetector

/**
 * Makes the names inside a configset navigable.
 *
 * A configset is held together entirely by string references — a field names its type, a
 * `copyField` names two fields, a handler parameter names fields in another file — and none of them
 * is a reference as far as the editor is concerned. This is what turns them into one.
 */
class SolrConfigsetReferenceContributor : PsiReferenceContributor() {

    /**
     * @param registrar the platform's registry of reference providers
     */
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(XmlPatterns.xmlAttributeValue(), SolrFieldTypeReferenceProvider())
    }
}

/** Supplies a reference from a field's `type` to the field type it names. */
private class SolrFieldTypeReferenceProvider : PsiReferenceProvider() {

    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        val value = element as? XmlAttributeValue ?: return PsiReference.EMPTY_ARRAY
        if (!SolrConfigsetDetector.isConfigsetFile(value.containingFile)) return PsiReference.EMPTY_ARRAY

        val attribute = value.parentOfType<XmlAttribute>() ?: return PsiReference.EMPTY_ARRAY
        if (attribute.name != "type") return PsiReference.EMPTY_ARRAY
        val tag = attribute.parentOfType<XmlTag>() ?: return PsiReference.EMPTY_ARRAY
        if (tag.name !in FIELD_TAGS) return PsiReference.EMPTY_ARRAY
        if (value.value.isEmpty()) return PsiReference.EMPTY_ARRAY

        return arrayOf(SolrFieldTypeReference(value))
    }

    private companion object {
        val FIELD_TAGS = setOf("field", "dynamicField")
    }
}

/**
 * A reference from `type="text_general"` to the `<fieldType name="text_general">` that declares it.
 *
 * **Soft, deliberately.** A hard reference that fails to resolve is reported by the platform's own
 * unresolved-reference inspection, which would put a second warning on a type name that
 * [org.apache.solr.ide.configset.inspection.SolrUnknownFieldTypeInspection] already reports — in the platform's vocabulary rather than
 * Solr's, and saying less. Soft references navigate when they resolve and stay quiet when they do
 * not, which leaves the diagnosis with the inspection that can phrase it properly.
 */
internal class SolrFieldTypeReference(element: XmlAttributeValue) :
    PsiReferenceBase<XmlAttributeValue>(element, ElementManipulators.getValueTextRange(element), true) {

    /**
     * The `name` attribute of the declaring `fieldType`, or null when nothing declares it.
     *
     * Resolves within the containing file: a field's type is declared in the same schema, and
     * searching the rest of the configset would find types that Solr itself would not.
     */
    override fun resolve(): PsiElement? =
        SolrSchemaPsi.findFieldType(element.containingFile, value)

    /**
     * No completion variants.
     *
     * Completion for this position is contributed by [org.apache.solr.ide.configset.completion.SolrConfigsetCompletionContributor], which can
     * show what each type matches. Returning variants here as well would produce every type twice.
     */
    override fun getVariants(): Array<Any> = emptyArray()

    /** The range covered, which is the text inside the quotes rather than the quotes themselves. */
    override fun getRangeInElement(): TextRange = ElementManipulators.getValueTextRange(element)
}
