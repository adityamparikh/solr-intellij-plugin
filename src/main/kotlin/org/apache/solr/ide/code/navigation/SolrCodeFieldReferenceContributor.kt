package org.apache.solr.ide.code.navigation

import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.util.ProcessingContext
import org.apache.solr.ide.code.SolrRecognizers
import org.apache.solr.ide.configset.activation.SolrProjectConfigsets
import org.apache.solr.ide.configset.navigation.SolrDeclarationReference
import org.apache.solr.ide.configset.navigation.SolrSchemaPsi

/**
 * Makes a Solr field name written in Java or Kotlin navigable to the schema that declares it.
 *
 * **The reference the inspection implies.** The check already says a name is not declared anywhere;
 * this answers the question a reader asks next, which is *where is it declared* — and it answers it
 * from a `.java` or `.kt` file that belongs to no configset, across a boundary nothing else in the
 * IDE connects. A field name in code and its declaration in XML are related by convention and by
 * nothing the tooling can see.
 *
 * **Silent under exactly the conditions the check is silent under**, because they are the same
 * conditions read from the same recognizer: no Solr client on the module, a name the source does not
 * spell out, or no configset in the project to resolve against. A reference that resolved where the
 * check declines — or the reverse — would be two answers about one name.
 */
class SolrCodeFieldReferenceContributor : PsiReferenceContributor() {

    /**
     * @param registrar where the provider is registered
     */
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        // Registered against every PSI element rather than a language's own literal type: the
        // provider asks the recognizer what it found, and the recognizer is what knows which
        // languages it can read. Naming a literal type here would name one language's PSI.
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(), SolrCodeFieldReferenceProvider())
    }
}

/**
 * Supplies one reference per field name a recognizer read out of this element.
 *
 * Asks the recognizer about the containing file and keeps the usages anchored to this element, which
 * is what makes the reference and the warning agree: both come from one reading of the file, and
 * both use the usage's own idea of where its name sits.
 */
private class SolrCodeFieldReferenceProvider : PsiReferenceProvider() {

    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        val file = element.containingFile ?: return PsiReference.EMPTY_ARRAY
        if (SolrProjectConfigsets.getInstance(element.project).all().isEmpty()) return PsiReference.EMPTY_ARRAY

        val references = SolrRecognizers.fieldUsagesIn(file)
            .filter { it.element == element }
            .mapNotNull { usage -> usage.rangeInElement?.let { SolrCodeFieldReference(element, it, usage.fieldName) } }
        return references.toTypedArray()
    }
}

/**
 * A field name in code, pointing at the schema declaration that supplies it.
 *
 * **Resolves against every configset in the project, first answer wins.** A file of code names no
 * configset, so unlike the configset-side references there is no single schema to consult. Two
 * configsets declaring the same field is the ordinary case — a repository with a books and a films
 * collection may declare `id` in both — and offering the first is more useful than offering nothing;
 * what the reader wants is the declaration, and either is one.
 */
internal class SolrCodeFieldReference(
    element: PsiElement,
    rangeInElement: TextRange,
    private val fieldName: String,
) : SolrDeclarationReference<PsiElement>(element, rangeInElement) {

    /**
     * The declaration this name resolves to, or null where no configset declares it.
     *
     * @return the schema's `name` attribute value, which is what the caret lands on
     */
    override fun resolve(): XmlAttributeValue? =
        SolrProjectConfigsets.getInstance(element.project).all()
            .asSequence()
            .mapNotNull { SolrSchemaPsi.schemaFileIn(it, element.project) }
            .mapNotNull { SolrSchemaPsi.findField(it, fieldName) }
            .firstOrNull()
}
