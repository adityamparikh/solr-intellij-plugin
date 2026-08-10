package org.apache.solr.ide.configset.navigation

import com.intellij.openapi.project.Project
import com.intellij.patterns.XmlPatterns
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.parentOfType
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag
import com.intellij.util.ProcessingContext
import org.apache.solr.ide.configset.activation.SolrConfigsetDetector
import org.apache.solr.ide.configset.activation.SolrConfigsetFileKind
import org.apache.solr.ide.configset.reading.SolrConfigsetReader
import org.apache.solr.ide.model.vocabulary.SolrClassCatalog

/**
 * Makes the `class` attribute of a configset element navigable to the class it names.
 *
 * **In `navigation` rather than under an aspect, because a `class` attribute is written in both
 * files.** A `<fieldType class="solr.StrField">` in the schema and a
 * `<requestHandler class="solr.SearchHandler">` in `solrconfig.xml` are the same gesture asking the
 * same question, and filing it under either aspect would make that aspect the owner of a position the
 * other one also has.
 *
 * **The only thing in this plugin that touches Java PSI**, which is why it is registered from
 * `solr-withJava.xml` behind an optional dependency rather than from `plugin.xml`. Everything else
 * reads a configset's own text, so the plugin loads and works in an IDE with no Java support; this
 * feature is simply absent there.
 */
class SolrClassReferenceContributor : PsiReferenceContributor() {

    /**
     * @param registrar the platform's registry of reference providers
     */
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            XmlPatterns.xmlAttributeValue().withParent(XmlPatterns.xmlAttribute().withLocalName("class")),
            SolrClassReferenceProvider(),
        )
    }
}

/**
 * Supplies a reference from a `class` attribute value to the class it names.
 *
 * **Not dumb-aware, and it is the only thing here that is not.** Java class resolution reads the
 * platform's indexes, so unlike every other feature in this plugin it cannot answer while indexing is
 * still running. That is the platform's default for a reference provider, so it is achieved by not
 * claiming otherwise rather than by adding a guard — but it is worth stating, because the standing
 * rule in this codebase is that a contribution declares itself dumb-aware, and this one must not.
 */
private class SolrClassReferenceProvider : PsiReferenceProvider() {

    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        val value = element as? XmlAttributeValue ?: return PsiReference.EMPTY_ARRAY
        val written = value.value.takeIf { it.isNotEmpty() } ?: return PsiReference.EMPTY_ARRAY
        val file = value.containingFile?.originalFile ?: return PsiReference.EMPTY_ARRAY

        // Only a configset's own files, and only the two that carry a `class`. The file-name check is
        // first because it is the cheapest way to decline the `class=` attributes of every other XML
        // file in the project — a Spring bean definition, an Ant build, an Android layout.
        val kind = SolrConfigsetFileKind.forFileName(file.name) ?: return PsiReference.EMPTY_ARRAY
        if (!kind.holdsFieldReferences) return PsiReference.EMPTY_ARRAY
        if (!SolrConfigsetDetector.isConfigsetFile(file)) return PsiReference.EMPTY_ARRAY

        return arrayOf(SolrClassReference(value, written))
    }
}

/**
 * A soft reference from the written class name to the class itself.
 *
 * **Soft, which is the whole contract.** A configset names classes that are not on the project's
 * classpath as a matter of course — the reader is editing configuration, not building Solr — so an
 * unresolved name is the normal case and must produce nothing at all: no underline, no warning, no
 * second opinion about a class the [unknown-class inspection deliberately declines to
 * judge](https://github.com/adityamparikh/solr-intellij-plugin/blob/main/docs/code-organization.md).
 *
 * @property written the value exactly as the file spells it
 */
internal class SolrClassReference(
    element: XmlAttributeValue,
    private val written: String,
) : PsiReferenceBase<XmlAttributeValue>(element, true) {

    /**
     * The class this value names, or null when nothing on the classpath answers to it.
     *
     * Two spellings resolve, and the catalog is what makes the first possible. A configset writes
     * `solr.StrField`, which is Solr's own abbreviation and not a package — so the catalog's entry for
     * that short name supplies the qualified name to look up. Anything else is tried verbatim, which
     * is what makes a custom `com.example.MyComponent` navigable without the catalog knowing it
     * exists.
     *
     * **A `solr.`-prefixed name the catalog does not carry resolves to nothing**, and today that
     * includes every `solrconfig.xml` plugin class, because the catalog covers the schema's four kinds
     * only. Extending it to `solrconfig.xml`'s classes is what makes those navigable; until then this
     * degrades exactly as it does for a class that is genuinely absent.
     *
     * @return the class, or null
     */
    override fun resolve(): PsiElement? {
        val project = element.project
        val qualified = qualifiedName(project) ?: return null
        return JavaPsiFacade.getInstance(project)
            .findClass(qualified, GlobalSearchScope.allScope(project))
    }

    /**
     * Empty on purpose: completion for `class` values is the completion contributor's, and it offers
     * the catalog's classes with what each one does attached. A reference contributing variants as
     * well would put every name in the popup twice.
     */
    override fun getVariants(): Array<Any> = emptyArray()

    private fun qualifiedName(project: Project): String? {
        if (!written.startsWith(SOLR_PREFIX)) return written
        val file = element.containingFile?.originalFile ?: return null
        val model = SolrConfigsetReader.getInstance(project).modelFor(file) ?: return null
        return SolrClassCatalog.find(written, model.solrVersion)?.className
    }

    private companion object {
        /** Solr's abbreviation for its own packages, which is not a package name. */
        const val SOLR_PREFIX = "solr."
    }
}
