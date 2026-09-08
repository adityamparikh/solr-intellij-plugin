package org.apache.solr.ide.code.solrj

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.jetbrains.uast.UAnnotation
import org.apache.solr.ide.model.query.SolrParameters
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UExpression
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
                return it.qualifiedName == SolrJQueryMethods.BEAN_FIELD_ANNOTATION
            }
            element.toUElementOfType<UCallExpression>()?.let { call ->
                return fieldNamingMethod(call) != null
            }
            element = element.parent
        }
        return false
    }

    /**
     * The query written at [position], where one is written there and spelled out.
     *
     * **Only `q`, and not the filter queries beside it.** `fq` is a query too and is read for field
     * names as one, but running it alone answers a different question: a filter narrows a result set
     * and scores nothing, so a user shown its matches would be shown something their code never
     * asks Solr for. The main query is the one whose answer the code is about.
     *
     * @param position an element inside the argument, or the argument itself
     * @return the query as the source spells it, or null where this is not one
     */
    fun runnableQueryAt(position: PsiElement): String? {
        var element: PsiElement? = position
        while (element != null && element !is PsiFile) {
            val argument = element.toUElementOfType<UExpression>()
            val call = argument?.uastParent as? UCallExpression
            if (call != null) {
                if (fieldNamingMethod(call)?.parameter != SolrParameters.QUERY) return null
                return SolrJRecognizer.constantTextOf(argument)
            }
            element = element.parent
        }
        return null
    }

    /**
     * The query method [call] invokes, where it is one that names fields, and null otherwise.
     *
     * **The one place this rule is written.** Two conditions have to hold together — the method must
     * be one that names fields, and the receiver must resolve to SolrJ's own class rather than to
     * something else carrying a method of that name — and both the recognizer and completion need
     * exactly that answer. Written twice they would be two rules able to drift, which is how
     * completion comes to offer names in a position the check does not examine, or the reverse.
     *
     * @param call any call expression
     * @return the method, or null where this call names no fields
     */
    fun fieldNamingMethod(call: UCallExpression): SolrJQueryMethod? {
        val method = SolrJQueryMethods.forMethod(call.methodName ?: return null) ?: return null
        val owner = call.resolve()?.containingClass?.qualifiedName ?: return null
        return method.takeIf { SolrJQueryMethods.isSolrQueryClass(owner) }
    }
}
