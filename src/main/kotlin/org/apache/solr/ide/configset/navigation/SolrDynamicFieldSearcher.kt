package org.apache.solr.ide.configset.navigation

import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.progress.ProgressManager
import com.intellij.pom.PomTargetPsiElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.search.PsiSearchScopeUtil
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.parentOfType
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag
import com.intellij.util.Processor
import org.apache.solr.ide.configset.activation.SolrConfigsetDetector
import org.apache.solr.ide.configset.activation.SolrConfigsetFileKind
import org.apache.solr.ide.configset.activation.SolrSchemaTags

/**
 * Finds the usages of a dynamic field that its pattern supplies but never spells.
 *
 * `<dynamicField name="*_t">` is referenced two ways. A `copyField dest="*_t"` writes the pattern
 * literally, and the default search finds it. A handler's `qf` naming `body_t` refers to the same
 * declaration just as genuinely —
 * [SolrConfigFieldReference][org.apache.solr.ide.configset.reference.SolrConfigFieldReference]
 * resolves it there — and the default
 * search *cannot* find it, for a reason no amount of care in the reference would fix:
 * `ReferencesSearch` picks candidates out of the word index before asking any reference to confirm
 * itself, and it asks for the declaration's own name. `body_t` shares no word with `*_t`, so it is
 * never a candidate, never offered to `isReferenceTo`, and never appears.
 *
 * Left alone, Find Usages on a dynamic field lists the literal spellings and **silently omits every
 * name the pattern actually supplies** — an empty list that reads as *nothing uses this pattern*, on
 * the declaration whose usages are hardest to find by hand. It is also the only answer to the
 * question a dynamic field provokes, which is *what would I break by changing this pattern*.
 *
 * **The fix is to stop fighting the word index and change the search space instead.** There is no
 * suffix query to ask it, and there does not need to be: the search space for a Solr field reference
 * is not the project but the configset — a handful of files the detector has already identified. So
 * this walks those files and asks the references themselves. It is not a second opinion about what a
 * name means; it is the same opinion, asked from the other end.
 */
class SolrDynamicFieldSearcher :
    QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(true) {

    /**
     * Reports the references resolving to the searched dynamic field that the default search missed.
     *
     * **Declines before doing any work for anything else**, because this is consulted on every
     * `ReferencesSearch` in the project: the first act is a type check on the element searched for.
     * Concrete fields keep the default word-index path, which already finds them and is faster.
     *
     * @param queryParameters what is being searched for, and where
     * @param consumer collects the references found
     */
    override fun processQuery(
        queryParameters: ReferencesSearch.SearchParameters,
        consumer: Processor<in PsiReference>,
    ) {
        val declaration = dynamicFieldNameOf(queryParameters.elementToSearch) ?: return
        val pattern = declaration.value
        val manager = PsiManager.getInstance(declaration.project)

        val scope = queryParameters.effectiveSearchScope
        for (file in configsetFilesOf(declaration.containingFile)) {
            // The configset is the upper bound, not the answer: a caller that narrowed the search
            // has said which results it wants, and the walk must not put back what it excluded.
            if (!scope.contains(file.virtualFile)) continue
            val complete = PsiTreeUtil.processElements(file) { element ->
                ProgressManager.checkCanceled()
                when (element) {
                    is XmlTag, is XmlAttributeValue ->
                        reportUsagesIn(element, pattern, declaration, manager, scope, consumer)

                    else -> true
                }
            }
            if (!complete) return
        }
    }

    /**
     * Reports whichever of [element]'s references resolve to [declaration], returning false when the
     * consumer has had enough.
     *
     * **A reference spelling the pattern literally is skipped, and that is not an omission.** The
     * default word-index search already reports it, since its text *is* the name being searched for;
     * reporting it a second time here would tell the reader there are two usages where there is one.
     * What this adds is precisely what the index cannot reach — the names that differ from the
     * pattern's own text.
     */
    private fun reportUsagesIn(
        element: PsiElement,
        pattern: String,
        declaration: XmlAttributeValue,
        manager: PsiManager,
        scope: SearchScope,
        consumer: Processor<in PsiReference>,
    ): Boolean {
        for (reference in element.references) {
            if (reference.rangeInElement.substring(element.text) == pattern) continue
            // Asked of the element, not of its file: a LocalSearchScope can be built from a single
            // tag, and excluding that tag's siblings is the whole point of building one.
            if (!PsiSearchScopeUtil.isInScope(scope, reference.element)) continue
            if (!manager.areElementsEquivalent(reference.resolve(), declaration)) continue
            if (!consumer.process(reference)) return false
        }
        return true
    }

    /**
     * The `name` attribute value of the `dynamicField` being searched for, or null for anything else.
     *
     * Accepts the search target in both the shapes it arrives in: the [PomTargetPsiElement] the Find
     * Usages action produces through [SolrDeclarationSearcher], and the raw [XmlAttributeValue] a
     * caller with the PSI in hand passes directly.
     */
    private fun dynamicFieldNameOf(element: PsiElement): XmlAttributeValue? {
        val value = when (element) {
            is XmlAttributeValue -> element
            is PomTargetPsiElement -> (element.target as? SolrDeclarationTarget)?.navigationElement as? XmlAttributeValue
            else -> null
        } ?: return null

        val attribute = value.parentOfType<XmlAttribute>() ?: return null
        if (attribute.name != SolrSchemaTags.NAME) return null
        val tag = attribute.parentOfType<XmlTag>() ?: return null
        if (tag.name != SolrSchemaTags.DYNAMIC_FIELD) return null
        return value
    }

    /**
     * The files of [schema]'s configset that can hold a field reference.
     *
     * **Scoped to the owning configset, which is correctness rather than a shortcut.** Solr resolves
     * a field name per configset, so a same-named field in a second configset in the same project is
     * not a usage of this one. The walk is O(configset) — two files — and stays off the editor path:
     * Find Usages is an explicit, occasional gesture.
     */
    private fun configsetFilesOf(schema: PsiFile): List<PsiFile> {
        val configset = SolrConfigsetDetector.configsetFor(schema) ?: return emptyList()
        val manager = PsiManager.getInstance(schema.project)
        return configset.root.children
            .filter { SolrConfigsetFileKind.forFileName(it.name)?.holdsFieldReferences == true }
            .mapNotNull { manager.findFile(it) }
    }
}
