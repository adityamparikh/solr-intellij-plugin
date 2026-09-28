package org.apache.solr.ide.code.solrj

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.jetbrains.uast.UAnnotation
import org.apache.solr.ide.model.query.SolrParameters
import org.apache.solr.ide.model.query.SolrQueryExpressions
import org.apache.solr.ide.model.query.SolrQueryFields
import org.apache.solr.ide.model.schema.SolrFieldOperation
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.expressions.UInjectionHost
import org.jetbrains.uast.toUElementOfType

/**
 * A field name being typed in code, and what the call it is typed into will ask of the field.
 *
 * @property prefix the part of the name typed so far, possibly empty
 * @property operation what the call asks of the field — a sort sorts, a facet facets — or null where
 *   it asks nothing a schema can refuse, as `fl` and a `@Field` binding do
 */
data class SolrJFieldSlot(val prefix: String, val operation: SolrFieldOperation?)

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
     * The field name being typed at [caretOffset], and what the call will ask of it — or null where
     * the caret is not somewhere a field name goes.
     *
     * **Finer than [namesAFieldAt], and completion needs the difference.** That answers for a whole
     * argument; this answers for the caret inside it. `setQuery("category:bo|")` is an argument that
     * names fields, and the caret is in a *value*: a field offered there completes to
     * `category:category`. So the text up to the caret is read with the grammar of the argument's
     * shape — a query, a field list, or one whole name — and only a field position answers.
     *
     * **Only a string written out as a literal**, since only its text up to the caret is knowable
     * while it is being typed; a constant or a concatenation declines. And only the argument that
     * holds a field: `setSort("id", |)` is a call that names fields and a caret that is not in one.
     *
     * @param position the element at or just before the caret — during completion a leaf of the
     *   copied file, while typing a leaf of the real one
     * @param caretOffset the caret's offset in [position]'s file
     * @return the partial name and the operation asked of it, or null where no field name goes here
     */
    fun fieldSlotAt(position: PsiElement, caretOffset: Int): SolrJFieldSlot? {
        var element: PsiElement? = position
        while (element != null && element !is PsiFile) {
            element.toUElementOfType<UAnnotation>()?.let { annotation ->
                if (annotation.qualifiedName != SolrJQueryMethods.BEAN_FIELD_ANNOTATION) return null
                val literal = stringLiteralAround(position) ?: return null
                return typedIn(literal, caretOffset)?.let { SolrJFieldSlot(it, operation = null) }
            }
            val argument = element.toUElementOfType<UExpression>()
            val call = argument?.uastParent as? UCallExpression
            if (call != null) {
                val method = fieldNamingMethod(call) ?: return null
                if (argument !is UInjectionHost) return null
                if (method.readsOnlyFirstArgument && call.valueArguments.firstOrNull()?.sourcePsi != element) return null
                val typed = typedIn(element, caretOffset) ?: return null
                val prefix = when (method.shape) {
                    SolrJArgumentShape.QUERY_EXPRESSION -> SolrQueryExpressions.tokenAt(typed, typed.length)
                    SolrJArgumentShape.FIELD_LIST -> SolrQueryFields.tokenAt(method.parameter, typed, typed.length)
                    SolrJArgumentShape.FIELD_NAME -> typed
                } ?: return null
                return SolrJFieldSlot(prefix, method.operation)
            }
            element = element.parent
        }
        return null
    }

    /** The string literal [position] sits in, as the one PSI element spanning its quotes. */
    private fun stringLiteralAround(position: PsiElement): PsiElement? {
        var element: PsiElement? = position
        while (element != null && element !is PsiFile) {
            if (element.toUElementOfType<UExpression>() is UInjectionHost) return element
            element = element.parent
        }
        return null
    }

    /**
     * The text of [literal] from after its opening quote up to [caretOffset], or null where the
     * caret is not inside the quotes.
     *
     * Read from the source rather than from the evaluated value, because the value is not yet a
     * value — during completion it carries the platform's placeholder at the caret, and while typing
     * the closing quote may not exist. Escapes are left as written; a query's grammar reads a `\"` as
     * a character inside a token, which is what an escaped quote is.
     */
    private fun typedIn(literal: PsiElement, caretOffset: Int): String? {
        val text = literal.text
        val quote = QUOTES.firstOrNull { text.startsWith(it) } ?: return null
        val end = caretOffset - literal.textRange.startOffset
        if (end < quote.length || end > text.length) return null
        return text.substring(quote.length, end)
    }

    /** The opening delimiters of a string in Java and Kotlin, longest first so a text block wins. */
    private val QUOTES = listOf("\"\"\"", "\"")

    /**
     * A query expression written at [position], where one is written there and spelled out.
     *
     * **Both `q` and the filter queries beside it.** A filter is a query and reads as one; what
     * differs is only what may be *done* with it, which is [runnableQueryAt]'s question rather than
     * this one. Colour, for instance, claims only that this text is a query, and that is true of an
     * `fq`.
     *
     * @param position an element inside the argument, or the argument itself
     * @return the query as the source spells it, or null where this is not one
     */
    fun queryTextAt(position: PsiElement): String? =
        queryAt(position)?.takeIf { it.first.shape == SolrJArgumentShape.QUERY_EXPRESSION }?.second

    /**
     * The query at [position] that it makes sense to run on its own.
     *
     * **Only `q`, and not the filter queries beside it.** Running an `fq` alone answers a different
     * question from the one the code asks: a filter narrows a result set and scores nothing, so a
     * user shown its matches would be shown something their code never asks Solr for.
     *
     * @param position an element inside the argument, or the argument itself
     * @return the query as the source spells it, or null where running this would answer something
     *   the code does not ask
     */
    fun runnableQueryAt(position: PsiElement): String? =
        queryAt(position)?.takeIf { it.first.parameter == SolrParameters.QUERY }?.second

    /**
     * The method and the text at [position], or null where no call spells a query out there.
     *
     * One walk for both questions above, because they differ only in what they accept afterwards.
     * Two walks would be two chances to disagree about *where* a query is, which is not the thing
     * they are meant to disagree about.
     */
    private fun queryAt(position: PsiElement): Pair<SolrJQueryMethod, String>? {
        var element: PsiElement? = position
        while (element != null && element !is PsiFile) {
            val argument = element.toUElementOfType<UExpression>()
            val call = argument?.uastParent as? UCallExpression
            if (call != null) {
                val method = fieldNamingMethod(call) ?: return null
                val text = SolrJRecognizer.constantTextOf(argument) ?: return null
                return method to text
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
