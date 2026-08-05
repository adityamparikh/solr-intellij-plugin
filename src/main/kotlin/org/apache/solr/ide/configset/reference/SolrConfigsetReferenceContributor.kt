package org.apache.solr.ide.configset.reference

import com.intellij.patterns.XmlPatterns
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.impl.source.resolve.reference.impl.providers.FileReferenceSet
import com.intellij.psi.util.parentOfType
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag
import com.intellij.util.ProcessingContext
import org.apache.solr.ide.configset.activation.SolrConfigsetDetector
import org.apache.solr.ide.configset.activation.SolrSchemaTags
import org.apache.solr.ide.configset.parsing.SolrConfigParameters
import org.apache.solr.ide.configset.parsing.SolrConfigsetReader

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
        // Narrowed to the attribute that can hold the reference. Registering on every attribute
        // value would consult this provider for every attribute in every XML file in the project —
        // a pom, an Android layout — before the configset check could decline.
        registrar.registerReferenceProvider(
            XmlPatterns.xmlAttributeValue().withLocalName("type"),
            SolrFieldTypeReferenceProvider(),
        )
        val copyFieldEnds = SolrCopyFieldReferenceProvider()
        for (attribute in SolrSchemaTags.COPY_FIELD_ENDS) {
            registrar.registerReferenceProvider(
                XmlPatterns.xmlAttributeValue().withLocalName(attribute),
                copyFieldEnds,
            )
        }
        // Narrowed to the one tag name that holds parameter values, for the same reason as above.
        registrar.registerReferenceProvider(
            XmlPatterns.xmlTag().withLocalName("str"),
            SolrConfigFieldReferenceProvider(),
        )
        val resourceFiles = SolrResourceFileReferenceProvider()
        for (attribute in SolrSchemaTags.RESOURCE_ATTRIBUTES) {
            registrar.registerReferenceProvider(
                XmlPatterns.xmlAttributeValue().withLocalName(attribute),
                resourceFiles,
            )
        }
    }
}

/**
 * Supplies file references from an analyzer component's resource attribute to the files it names.
 *
 * Built on the platform's [FileReferenceSet] rather than a bespoke resolver: it already answers
 * per path segment — `lang/stopwords_en.txt` navigates from `lang` and from the file name — and
 * it is the machinery rename and file-move refactorings already know how to update. Resolution is
 * relative to the schema's own directory, which is the configset root Solr resolves against.
 *
 * A value may hold a comma-separated list — `words="stopwords.txt,extra.txt"` — so each entry
 * gets its own reference set at its own offset.
 */
private class SolrResourceFileReferenceProvider : PsiReferenceProvider() {

    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        val value = element as? XmlAttributeValue ?: return PsiReference.EMPTY_ARRAY
        if (!SolrConfigsetDetector.isConfigsetFile(value.containingFile)) return PsiReference.EMPTY_ARRAY

        val attribute = value.parentOfType<XmlAttribute>() ?: return PsiReference.EMPTY_ARRAY
        if (attribute.name !in SolrSchemaTags.RESOURCE_ATTRIBUTES) return PsiReference.EMPTY_ARRAY
        // `protected` and `words` are ordinary words; the tag is what makes them resource paths.
        val tag = attribute.parentOfType<XmlTag>() ?: return PsiReference.EMPTY_ARRAY
        if (tag.name !in SolrSchemaTags.RESOURCE_CARRIERS) return PsiReference.EMPTY_ARRAY

        val text = value.value
        if (text.isEmpty()) return PsiReference.EMPTY_ARRAY
        val valueStart = ElementManipulators.getValueTextRange(value).startOffset

        val references = mutableListOf<PsiReference>()
        var offset = 0
        for (piece in text.split(',')) {
            val path = piece.trim()
            if (path.isNotEmpty()) {
                val start = valueStart + offset + piece.indexOf(path)
                references += SoftFileReferenceSet(path, value, start).allReferences
            }
            offset += piece.length + 1
        }
        return references.toTypedArray()
    }
}

/**
 * The platform's file-path references, made soft.
 *
 * Soft for the reason every reference in this package is: a missing resource file deserves an
 * inspection speaking Solr's language, not the platform's generic unresolved-path warning, and
 * until that inspection exists the navigation should not bring a warning with it.
 */
private class SoftFileReferenceSet(path: String, element: PsiElement, startInElement: Int) :
    FileReferenceSet(path, element, startInElement, null, true) {

    override fun isSoft(): Boolean = true
}

/** Supplies references from a handler parameter's field names to their schema declarations. */
private class SolrConfigFieldReferenceProvider : PsiReferenceProvider() {

    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        val tag = element as? XmlTag ?: return PsiReference.EMPTY_ARRAY
        // The file-name check runs first because it is the cheapest way to decline the `<str>`
        // tags of every other XML file the pattern lets through.
        if (tag.containingFile.name != "solrconfig.xml") return PsiReference.EMPTY_ARRAY
        if (!SolrConfigsetDetector.isConfigsetFile(tag.containingFile)) return PsiReference.EMPTY_ARRAY

        val occurrences = SolrConfigParameters.fieldNameOccurrences(tag)
        if (occurrences.isEmpty()) return PsiReference.EMPTY_ARRAY
        return occurrences.map { SolrConfigFieldReference(tag, it) }.toTypedArray()
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
        if (tag.name !in SolrSchemaTags.FIELD) return PsiReference.EMPTY_ARRAY
        if (value.value.isEmpty()) return PsiReference.EMPTY_ARRAY

        return arrayOf(SolrFieldTypeReference(value))
    }

}

/** Supplies a reference from each end of a `copyField` to the field it names. */
private class SolrCopyFieldReferenceProvider : PsiReferenceProvider() {

    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        val value = element as? XmlAttributeValue ?: return PsiReference.EMPTY_ARRAY
        if (!SolrConfigsetDetector.isConfigsetFile(value.containingFile)) return PsiReference.EMPTY_ARRAY

        val attribute = value.parentOfType<XmlAttribute>() ?: return PsiReference.EMPTY_ARRAY
        if (attribute.name !in SolrSchemaTags.COPY_FIELD_ENDS) return PsiReference.EMPTY_ARRAY
        // `source` and `dest` are ordinary words — a build file's copy task uses both. The tag is
        // what makes them field names.
        val tag = attribute.parentOfType<XmlTag>() ?: return PsiReference.EMPTY_ARRAY
        if (tag.name != SolrSchemaTags.COPY_FIELD) return PsiReference.EMPTY_ARRAY
        if (value.value.isEmpty()) return PsiReference.EMPTY_ARRAY

        return arrayOf(SolrCopyFieldReference(value))
    }
}

/**
 * Whether a reference spelling [spelling] and resolving to [declaration] may be rewritten by rename.
 *
 * **It may exactly when it spells the declaration's name.** Most references do, and for those this
 * is always true. The exception is the one [SolrDynamicFieldSearcher] introduced: a `qf` naming
 * `body_t` is a genuine usage of `<dynamicField name="*_t">`, but the two relations rename conflates
 * — *is a usage of* and *should become the new name* — come apart there. Substituting the new
 * pattern would write `*_txt` where a field name belongs, which Solr rejects outright.
 *
 * The name left behind matches nothing afterwards, and that is deliberate rather than overlooked:
 * [org.apache.solr.ide.configset.inspection.SolrUnknownFieldReferenceInspection] reports it from the
 * same position, in Solr's vocabulary, so the consequence is visible rather than silent.
 */
private fun mayBeRewritten(declaration: PsiElement?, spelling: String): Boolean =
    (declaration as? XmlAttributeValue)?.value?.equals(spelling) ?: true

/**
 * A reference from `source="title"` or `dest="text"` to the field declaring that name.
 *
 * **A glob resolves to the `dynamicField` spelling it, and to nothing else.** `dest="*_t"` and
 * `<dynamicField name="*_t">` write the same pattern literally, so landing on the declaration is an
 * exact answer rather than a guess. What this deliberately does *not* do is resolve a pattern to the
 * concrete fields it happens to match: which fields those are depends on the documents indexed, not
 * on the schema, so any such target would be invented. That is the same line
 * [org.apache.solr.ide.configset.inspection.SolrDanglingCopyFieldInspection] draws when it stays
 * silent on globs — here it costs a navigation nobody could have trusted, and there it prevents a
 * warning on a correct file.
 *
 * **The other direction is safe, and resolution goes through the model to get it.** A concrete
 * `source="body_t"` supplied only by `<dynamicField name="*_t">` lands on that declaration —
 * Solr's own resolution, declared-beats-dynamic and longest-literal-wins, and the same
 * [org.apache.solr.ide.model.SolrFieldModel.resolve] the inspection consults when it stays silent
 * on exactly this name. A reference that matched literally while the inspection resolved through
 * patterns produced no warning *and* a dead click, which teaches the reader the click is broken.
 *
 * Soft, for the reason [SolrFieldTypeReference] is soft.
 */
internal class SolrCopyFieldReference(element: XmlAttributeValue) :
    PsiReferenceBase<XmlAttributeValue>(element, ElementManipulators.getValueTextRange(element), true) {

    /**
     * The `name` attribute of the declaration supplying this name — declared outright, or the
     * dynamic pattern matching it — or null when nothing does.
     *
     * @return the declaring element, or null
     */
    override fun resolve(): PsiElement? {
        val file = element.containingFile
        val declared = SolrConfigsetReader.getInstance(element.project).modelFor(file)
            ?.resolve(value)?.name ?: return null
        return SolrSchemaPsi.findField(file, declared)
    }

    /**
     * Rewrites this end to [newElementName], unless it is a name a dynamic pattern supplied.
     *
     * See [mayBeRewritten] for why the two cases part company.
     */
    override fun handleElementRename(newElementName: String): PsiElement =
        if (mayBeRewritten(resolve(), value)) super.handleElementRename(newElementName) else element

    /**
     * No completion variants; the completion contributor already offers the fields here, with what
     * each one matches attached.
     */
    override fun getVariants(): Array<Any> = emptyArray()
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
}

/**
 * A reference from a field name inside a handler parameter to the schema declaration it names.
 *
 * The only reference here that crosses a file boundary: the parameter lives in `solrconfig.xml`
 * and the declaration in the schema, and nothing in either file connects the two for the editor.
 * Anchored on the parameter's tag with a range per field name, because a value like
 * `name^3 description` holds several references in one text node.
 *
 * Soft, for the reason [SolrFieldTypeReference] is soft: the unresolved case belongs to
 * [org.apache.solr.ide.configset.inspection.SolrUnknownFieldReferenceInspection], which reports it
 * in Solr's vocabulary from the same positions this reference navigates.
 */
internal class SolrConfigFieldReference(
    tag: XmlTag,
    private val occurrence: SolrConfigParameters.FieldNameOccurrence,
) : PsiReferenceBase<XmlTag>(tag, occurrence.rangeInTag, true) {

    /**
     * The `name` attribute of the declaration supplying this name in the owning configset's
     * schema — declared outright, or the dynamic pattern matching it — or null when nothing does.
     *
     * Resolution goes through [org.apache.solr.ide.model.SolrFieldModel.resolve] for the reason
     * [SolrCopyFieldReference] documents: it is the same answer the unknown-field inspection
     * consults, so the two cannot disagree about which names are real.
     *
     * @return the declaring element, or null
     */
    override fun resolve(): PsiElement? {
        val file = element.containingFile
        val declared = SolrConfigsetReader.getInstance(element.project).modelFor(file)
            ?.resolve(occurrence.fieldName)?.name ?: return null
        val schema = SolrSchemaPsi.schemaFileOf(file) ?: return null
        return SolrSchemaPsi.findField(schema, declared)
    }

    /**
     * Rewrites this occurrence to [newElementName], unless it is a name a dynamic pattern supplied.
     *
     * See [mayBeRewritten]. This is the position where getting it wrong is worst: the parameter is
     * in the other file, so a reader reviewing the rename in the schema would not see the damage.
     */
    override fun handleElementRename(newElementName: String): PsiElement =
        if (mayBeRewritten(resolve(), occurrence.fieldName)) {
            super.handleElementRename(newElementName)
        } else {
            element
        }

    /**
     * No completion variants; what may be offered in a parameter value is the completion
     * contributor's decision, not this reference's.
     */
    override fun getVariants(): Array<Any> = emptyArray()
}
