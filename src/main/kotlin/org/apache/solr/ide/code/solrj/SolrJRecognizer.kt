package org.apache.solr.ide.code.solrj

import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.apache.solr.ide.configset.activation.SolrProjectDetector
import org.apache.solr.ide.model.query.SolrQueryExpressions
import org.apache.solr.ide.model.query.SolrQueryFields
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UFile
import org.jetbrains.uast.ULiteralExpression
import org.jetbrains.uast.UPolyadicExpression
import org.jetbrains.uast.toUElementOfType
import org.jetbrains.uast.visitor.AbstractUastVisitor

/**
 * A field name a piece of code references, and where it was written.
 *
 * @property fieldName the name as written, with no surrounding query syntax
 * @property parameter the Solr request parameter it was written into, which is what the field is
 *   being asked to support — `fq` searches, `sort` orders, `facet.field` facets
 * @property element the literal the name was read out of, which is what a finding anchors to
 */
data class SolrFieldUsage(
    val fieldName: String,
    val parameter: String,
    val element: PsiElement,
)

/**
 * Reads field references out of SolrJ calls in Java and Kotlin.
 *
 * **Through UAST, so one implementation serves both languages.** PSI is language-specific — a
 * recognizer written against `PsiMethodCallExpression` sees nothing of Kotlin — while UAST's
 * `UCallExpression` is implemented by each JVM language. The plan settles this under Step 16 and
 * records why Groovy is not included: its UAST provider converts annotations and literals but not
 * calls, so half of this would be silently dark there.
 *
 * **Silence wherever the answer is not certain.** A call is read only when its receiver resolves to
 * SolrJ's own `SolrQuery`, its method is one that names fields, and its argument spells the name out
 * in the source. Anything else — a variable, an interpolated string, a project's own class with a
 * method of the same name, a method taking a count — produces nothing. That is precision over
 * recall, which is the correct trade for a feature whose failure mode is a warning on somebody's
 * working code.
 *
 * **The two languages are held level by a mirrored test suite, because the compiler will not do it.**
 * `SolrJRecognizerKotlinTest` repeats every case in the Java suite, silences included. Until it
 * existed the Kotlin half was entirely dark and the build was green: a Kotlin string is never a
 * literal but always a template, so the cast this once performed failed on every one of them.
 */
object SolrJRecognizer {

    /**
     * The field references [file] makes through SolrJ, in the order they are written.
     *
     * @param file a Java or Kotlin file
     * @return the field usages it contains, empty where the module has no Solr client or the file
     *   references none
     */
    fun fieldUsagesIn(file: PsiFile): List<SolrFieldUsage> {
        // The module gate first, because it is the cheapest question and the one that decides
        // whether any of the rest is worth asking. A module with no Solr client on it is not
        // talking to Solr, whatever its strings look like.
        val module = ModuleUtilCore.findModuleForPsiElement(file) ?: return emptyList()
        if (!SolrProjectDetector.getInstance(file.project).isSolrModule(module)) return emptyList()

        val uFile = file.toUElementOfType<UFile>() ?: return emptyList()
        val usages = mutableListOf<SolrFieldUsage>()
        uFile.accept(
            object : AbstractUastVisitor() {
                override fun visitCallExpression(node: UCallExpression): Boolean {
                    readCall(node, usages)
                    return false
                }
            },
        )
        return usages
    }

    /**
     * Reads one call, or declines to.
     *
     * Three conditions, each of which alone would let a wrong finding through: the method must be
     * one that names fields, the receiver must resolve to SolrJ's own class rather than something
     * else carrying a method of that name, and the argument must be a literal. A variable holding
     * the query is where following values through the program would begin, and this deliberately
     * does not — the specification calls that best-effort by nature and chooses silence.
     */
    private fun readCall(call: UCallExpression, into: MutableList<SolrFieldUsage>) {
        val method = SolrJQueryMethods.forMethod(call.methodName ?: return) ?: return
        val owner = call.resolve()?.containingClass?.qualifiedName ?: return
        if (!SolrJQueryMethods.isSolrQueryClass(owner)) return

        val arguments = if (method.readsOnlyFirstArgument) call.valueArguments.take(1) else call.valueArguments
        for (argument in arguments) {
            val text = constantTextOf(argument) ?: continue
            val anchor = argument.sourcePsi ?: continue
            for (name in namesIn(method, text)) {
                into += SolrFieldUsage(name, method.parameter, anchor)
            }
        }
    }

    /**
     * The string an argument spells out in the source, or null where it does not spell one out.
     *
     * **Not `evaluateString`, which is the obvious answer and the wrong one.** UAST's evaluator
     * follows a Kotlin `val` to its assignment while declining a Java local and even a Java
     * `static final`, so a recognizer built on it reads a variable in one language and not the
     * other — the disagreement that writing against UAST was chosen to prevent.
     *
     * What is wanted is narrower: the argument is read only where the source spells the name out.
     * A Java string literal arrives as [ULiteralExpression]; a Kotlin one never does, because Kotlin
     * models every string as a template that *may* interpolate, so `"categry:books"` arrives as a
     * polyadic expression holding a single literal operand. Both spell the name out.
     *
     * **The recursion is what keeps the two languages honest, and it is not optional.** A flat check
     * — a polyadic whose operands are all literals — would read Java's `"categry" + ":books"`, whose
     * operands are two literals, and decline Kotlin's identical source, whose operands are two
     * templates. That is the same defect this function was written to remove, one level down.
     * Descending through operands asks the same question of every level, so a concatenation of
     * spelled-out parts reads the same in both languages and an interpolated part stops it in both.
     *
     * @param argument one argument of a call already known to name fields
     * @return the string it spells out, or null to say nothing about this call
     */
    private fun constantTextOf(argument: UExpression): String? {
        if (argument is ULiteralExpression) return argument.value as? String
        if (argument !is UPolyadicExpression) return null
        // A single unreadable operand discards the whole argument rather than the part: half a field
        // name is not a field name, and reporting `books` out of `"$prefix:books"` would warn about
        // a field the developer never wrote.
        val parts = argument.operands.map { constantTextOf(it) ?: return null }
        return parts.joinToString("")
    }

    /**
     * The names one argument holds, according to the shape its method declares.
     *
     * Each branch delegates to the grammar that owns it rather than re-deriving one here: a query
     * expression to [SolrQueryExpressions], a field list to [SolrQueryFields], and a single name
     * through the same exclusions a list applies to each element — which is why `addField("*")`
     * yields nothing, a glob being legal in an `fl` and not a field anyone declared.
     */
    private fun namesIn(method: SolrJQueryMethod, text: String): List<String> = when (method.shape) {
        SolrJArgumentShape.QUERY_EXPRESSION -> SolrQueryExpressions.fieldNamesIn(text)
        SolrJArgumentShape.FIELD_LIST -> SolrQueryFields.namesIn(method.parameter, text).map { it.name }
        SolrJArgumentShape.FIELD_NAME -> listOfNotNull(SolrQueryFields.fieldNameOrNull(text))
    }
}
