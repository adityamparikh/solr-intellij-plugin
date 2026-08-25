package org.apache.solr.ide.code.solrj

import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.apache.solr.ide.configset.activation.SolrProjectDetector
import org.apache.solr.ide.model.query.SolrQueryExpressions
import org.apache.solr.ide.model.query.SolrQueryFields
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UFile
import org.jetbrains.uast.ULiteralExpression
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
 * SolrJ's own `SolrQuery`, its method is one that names fields, and its argument is a literal string.
 * Anything else — a variable, a project's own class with a method of the same name, a method taking
 * a count — produces nothing. That is precision over recall, which is the correct trade for a
 * feature whose failure mode is a warning on somebody's working code.
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
            val literal = argument as? ULiteralExpression ?: continue
            val text = literal.value as? String ?: continue
            val anchor = literal.sourcePsi ?: continue
            for (name in namesIn(method, text)) {
                into += SolrFieldUsage(name, method.parameter, anchor)
            }
        }
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
