package org.apache.solr.ide.configset.navigation

import com.intellij.openapi.project.DumbService
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
 * still running. The standing rule in this codebase is that a contribution declares itself dumb-aware,
 * and this one must not.
 *
 * **Declining the declaration is not the same as being safe during indexing, which an earlier revision
 * of this comment had exactly backwards.** It read that the guard was unnecessary because not claiming
 * dumb-awareness achieved the same thing. It does not: it keeps this out of the paths that consult the
 * flag, while [SolrClassReference.resolve] is still called directly by anything walking references at a
 * caret. The guard is in `resolve`, and it is load-bearing.
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
     * **A `solr.`-prefixed name the catalog does not carry resolves to nothing**, which is a custom
     * plugin using Solr's own abbreviation — rare, and indistinguishable here from a class that is
     * genuinely absent. Both degrade to nothing, which is what the soft reference is for.
     *
     * **Indexing is a third way to answer nothing, and it must answer nothing rather than throw.**
     * [JavaPsiFacade.findClass] reads the stub index and raises `IndexNotReadyException` in dumb mode,
     * and the throw does not stay here: the platform collects target symbols at the caret before it
     * asks any documentation provider anything, so an exception raised in this method took down the
     * whole quick-documentation popup — including what this plugin could answer from a configset it had
     * already parsed, which needs no index at all. Navigation is genuinely unavailable while indexing;
     * the explanation beside it never was.
     *
     * @return the class, or null
     */
    override fun resolve(): PsiElement? {
        val project = element.project
        if (DumbService.isDumb(project)) return null
        val qualified = qualifiedName(project) ?: return null
        return JavaPsiFacade.getInstance(project)
            .findClass(qualified, GlobalSearchScope.allScope(project))
    }

    /**
     * Rewrites the value when the class it names is renamed, keeping Solr's own spelling.
     *
     * **The inherited behaviour is wrong here, which is the only reason this exists.**
     * [PsiReferenceBase.handleElementRename] replaces the reference's whole range with the new class
     * name, so renaming `StrField` through Java would turn `solr.StrField` into `NewName` — dropping
     * the prefix, and leaving a configset Solr can no longer load. Making a `class` value navigable
     * is what exposes it to rename at all, so the two arrive together.
     *
     * The prefix is restored rather than the rename refused, because refusing would leave the file
     * naming a class that no longer exists. `solr.` abbreviates Solr's own packages, so a class that
     * was written with it is still written with it under a new name.
     *
     * @param newElementName the class's new simple name
     * @return the element, with its value rewritten
     */
    override fun handleElementRename(newElementName: String): PsiElement {
        val replacement = if (written.startsWith(SOLR_PREFIX)) {
            SOLR_PREFIX + newElementName.substringAfterLast('.')
        } else {
            newElementName
        }
        return super.handleElementRename(replacement)
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
