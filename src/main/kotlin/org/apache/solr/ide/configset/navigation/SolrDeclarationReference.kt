package org.apache.solr.ide.configset.navigation

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.xml.XmlAttributeValue

/**
 * A name written inside a configset, pointing at the schema declaration that supplies it.
 *
 * The three positions differ in where they sit and how they resolve; what they share are the two
 * promises below, which `SolrReferenceContractTest` asserts because no feature test reaches either.
 * Stating them once is the point of this class — a fourth reference position gets them by
 * construction rather than by remembering.
 */
internal abstract class SolrDeclarationReference<T : PsiElement>(element: T, rangeInElement: TextRange) :
    PsiReferenceBase<T>(element, rangeInElement, true) {

    /**
     * Rewrites this occurrence to [newElementName] — unless it is a name a dynamic pattern supplied.
     *
     * **A reference may be rewritten exactly when it spells its declaration's name.** Most do, and
     * for those this is always true. The exception is the one
     * [SolrDynamicFieldSearcher][org.apache.solr.ide.configset.navigation.SolrDynamicFieldSearcher]
     * introduced:
     * a `qf` naming `body_t` is a genuine usage of `<dynamicField name="*_t">`, but the two relations
     * rename conflates — *is a usage of* and *should become the new name* — come apart there.
     * Substituting the new pattern would write `*_txt` where a field name belongs, which Solr rejects
     * outright.
     *
     * The name left behind matches nothing afterwards, and that is deliberate rather than
     * overlooked: [org.apache.solr.ide.configset.solrconfig.inspection.SolrUnknownFieldReferenceInspection]
     * reports it from the same position, in Solr's vocabulary, so the consequence is visible rather
     * than silent.
     *
     * **The comparison is against [getValue] rather than any name parsed on the way in, because
     * [getValue] is the text `super` is about to overwrite** — so the question asked is exactly the
     * question that matters. An unresolved reference is left alone: the platform only offers what a
     * search already resolved to this declaration, so failing to resolve here means the ground moved
     * mid-refactoring, and declining is the conservative answer.
     */
    final override fun handleElementRename(newElementName: String): PsiElement =
        if ((resolve() as? XmlAttributeValue)?.value == value) {
            super.handleElementRename(newElementName)
        } else {
            element
        }

    /**
     * No completion variants, at any of these positions.
     *
     * The completion contributors already own them — the schema's positions belong to
     * [org.apache.solr.ide.configset.schema.completion.SolrSchemaCompletionContributor], and a handler
     * parameter's field names to `org.apache.solr.ide.configset.solrconfig.completion.SolrConfigCompletionContributor`
     * — and both offer names with what each one matches attached. A reference returning variants as
     * well would put every name in the popup twice.
     */
    final override fun getVariants(): Array<Any> = emptyArray()
}
