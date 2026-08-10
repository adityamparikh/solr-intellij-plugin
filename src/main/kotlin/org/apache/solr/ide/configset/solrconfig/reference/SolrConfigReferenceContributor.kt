package org.apache.solr.ide.configset.solrconfig.reference

import com.intellij.patterns.XmlPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.xml.XmlTag
import com.intellij.util.ProcessingContext
import org.apache.solr.ide.configset.activation.SolrConfigsetDetector
import org.apache.solr.ide.configset.activation.SolrConfigsetFileKind
import org.apache.solr.ide.configset.solrconfig.parsing.SolrConfigParser
import org.apache.solr.ide.configset.navigation.SolrDeclarationReference
import org.apache.solr.ide.configset.solrconfig.SolrConfigParameters
import org.apache.solr.ide.configset.reading.SolrConfigsetReader
import org.apache.solr.ide.configset.navigation.SolrSchemaPsi

/**
 * Makes the field names inside a `solrconfig.xml` handler parameter navigable.
 *
 * A parameter such as `qf` or `fl` names fields the *schema* declares, so these are the references
 * that cross the file boundary — and the reason Find Usages on a schema declaration has anything to
 * find outside its own file.
 *
 * Registered apart from
 * [SolrSchemaReferenceContributor][org.apache.solr.ide.configset.schema.reference.SolrSchemaReferenceContributor]
 * because the two answer for different files and different elements, and a contributor is consulted
 * for every XML file in the project.
 */
class SolrConfigReferenceContributor : PsiReferenceContributor() {

    /**
     * @param registrar the platform's registry of reference providers
     */
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        // Narrowed to the tags that hold parameter values: registering on every tag would consult
        // this provider for every element in every XML file before it could decline.
        //
        // **The set is the parser's, not a shorter one of this file's own.** It was `str` alone while
        // `SolrConfigParameters` read six tags, so a `qf` written as `<int name="qf">` was inspected
        // and completed but not navigable — the one gesture of the three that silently did nothing.
        registrar.registerReferenceProvider(
            XmlPatterns.xmlTag().withLocalName(*SolrConfigParser.VALUE_TAGS.toTypedArray()),
            SolrConfigFieldReferenceProvider(),
        )
    }
}

/** Supplies references from a handler parameter's field names to their schema declarations. */
private class SolrConfigFieldReferenceProvider : PsiReferenceProvider() {

    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        val tag = element as? XmlTag ?: return PsiReference.EMPTY_ARRAY
        // The file-name check runs first because it is the cheapest way to decline the value
        // tags of every other XML file the pattern lets through.
        if (SolrConfigsetFileKind.forFileName(tag.containingFile.name)?.isSolrConfig != true) {
            return PsiReference.EMPTY_ARRAY
        }
        if (!SolrConfigsetDetector.isConfigsetFile(tag.containingFile)) return PsiReference.EMPTY_ARRAY

        val occurrences = SolrConfigParameters.fieldNameOccurrences(tag)
        if (occurrences.isEmpty()) return PsiReference.EMPTY_ARRAY
        return occurrences.map { SolrConfigFieldReference(tag, it) }.toTypedArray()
    }
}

/**
 * A reference from a field name inside a handler parameter to the schema declaration it names.
 *
 * The only reference here that crosses a file boundary: the parameter lives in `solrconfig.xml`
 * and the declaration in the schema, and nothing in either file connects the two for the editor.
 * Anchored on the parameter's tag with a range per field name, because a value like
 * `name^3 description` holds several references in one text node.
 *
 * Soft, for the reason [SolrFieldTypeReference][org.apache.solr.ide.configset.schema.reference.SolrFieldTypeReference] is soft: the unresolved case belongs to
 * [org.apache.solr.ide.configset.solrconfig.inspection.SolrUnknownFieldReferenceInspection], which reports it
 * in Solr's vocabulary from the same positions this reference navigates.
 */
internal class SolrConfigFieldReference(
    tag: XmlTag,
    private val occurrence: SolrConfigParameters.FieldNameOccurrence,
) : SolrDeclarationReference<XmlTag>(tag, occurrence.rangeInTag) {

    /**
     * The `name` attribute of the declaration supplying this name in the owning configset's
     * schema — declared outright, or the dynamic pattern matching it — or null when nothing does.
     *
     * Resolution goes through [org.apache.solr.ide.model.SolrFieldModel.resolve] for the reason
     * [SolrCopyFieldReference][org.apache.solr.ide.configset.schema.reference.SolrCopyFieldReference] documents: it is the same answer the unknown-field inspection
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
}
