package org.apache.solr.ide.code.solrj

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.toUElementOfType

/**
 * Whether a caret sits somewhere a Solr field name belongs.
 *
 * **A different question from the one the recognizer asks, and it has to be.** The recognizer reads
 * names that are already written; completion runs while one is being typed, when the string is
 * `"cat"` or empty and there is no name to read at all. Asking "what does this say" would answer
 * nothing exactly when the user wants help, so this asks "where is this" instead.
 *
 * Written against UAST for the same reason everything else here is: the two positions — an argument
 * to a query-building call, and the value of a `@Field` annotation — are spelled differently in Java
 * and Kotlin and identically in UAST.
 */
object SolrJFieldPositions {

    /** SolrJ's bean-binding annotation, matched in full so nobody else's `Field` is offered against. */
    private const val BEAN_FIELD_ANNOTATION = "org.apache.solr.client.solrj.beans.Field"

    /**
     * Whether a field name belongs at [position].
     *
     * Walks outward from the caret to the first construct that answers, and stops at the first call
     * or annotation either way — so an argument to some other method *inside* a Solr call is not
     * treated as a field name because a Solr call happens to be further out.
     *
     * @param position the element the caret is in, which during completion is a synthetic leaf
     * @return true where a Solr field name is what belongs there
     */
    fun namesAFieldAt(position: PsiElement): Boolean {
        var element: PsiElement? = position
        while (element != null && element !is PsiFile) {
            element.toUElementOfType<UAnnotation>()?.let {
                return it.qualifiedName == BEAN_FIELD_ANNOTATION
            }
            element.toUElementOfType<UCallExpression>()?.let { call ->
                return namesFields(call)
            }
            element = element.parent
        }
        return false
    }

    /**
     * Whether [call] is a query-building call whose arguments are field names.
     *
     * The same two conditions the recognizer applies to a written name: the method must be one that
     * names fields, and the receiver must resolve to SolrJ's own class rather than to something else
     * carrying a method of that name. Completion is offered in fewer places than a warning is
     * reported, never more.
     */
    private fun namesFields(call: UCallExpression): Boolean {
        if (SolrJQueryMethods.forMethod(call.methodName ?: return false) == null) return false
        val owner = call.resolve()?.containingClass?.qualifiedName ?: return false
        return SolrJQueryMethods.isSolrQueryClass(owner)
    }
}
