package org.apache.solr.ide.code.highlighting

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import org.apache.solr.ide.code.SolrRecognizers
import org.apache.solr.ide.code.solrj.SolrJFieldPositions
import org.apache.solr.ide.model.query.SolrQueryExpressions
import org.apache.solr.ide.model.query.SolrQuerySpanKind

/**
 * Colours the parts of a Solr query written inside a Java or Kotlin string.
 *
 * **A query in code is one undifferentiated string, and that is the problem.**
 * `"category:books AND price:[1 TO 9]"` is the same colour end to end, so the reader does the
 * parsing — which field is being searched, where one clause stops and the next begins. Colouring the
 * fields and the operators is the smallest thing that makes the structure visible, and it needs no
 * grammar.
 *
 * **Deliberately not a language injection, and not a parser.** Injecting a language would give
 * structure, folding and a parse tree, and would need a grammar for Solr's query syntax — which is
 * larger than this, and larger than the payoff for a string that is usually one clause long. What is
 * here is a scan, sharing the rules the checks already use.
 *
 * **The colours come from the platform's own palette rather than a scheme this plugin defines.** A
 * field is a keyword and an operator is an operator in every language the IDE highlights; using
 * those keys means the query follows a reader's theme, including themes this plugin has never seen,
 * and adds no colour settings page to maintain.
 */
class SolrQueryHighlightAnnotator : Annotator {

    /**
     * Paints [element] where it holds a query, and leaves it alone otherwise.
     *
     * **The cheapest question first**, because an annotator runs on every element of every file in
     * the editor: a leaf, then a query position, then the module gate. Only then is the text scanned.
     *
     * @param element every element the daemon walks
     * @param holder where annotations are added
     */
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element.firstChild != null) return
        val query = SolrJFieldPositions.queryTextAt(element) ?: return
        val file = element.containingFile ?: return
        if (!SolrRecognizers.recognizeSolrIn(file)) return

        // Where the query sits inside the element, which is not at its start: the element's text
        // includes the quotes that are not part of the query.
        val offset = element.text.indexOf(query).takeIf { it >= 0 } ?: return

        for (span in SolrQueryExpressions.spansIn(query)) {
            val range = TextRange(
                element.textRange.startOffset + offset + span.start,
                element.textRange.startOffset + offset + span.end,
            )
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(range)
                .textAttributes(colourOf(span.kind))
                .create()
        }
    }

    private fun colourOf(kind: SolrQuerySpanKind) = when (kind) {
        // The field is the thing a clause is *about*, which is what a keyword is in a language.
        SolrQuerySpanKind.FIELD -> DefaultLanguageHighlighterColors.KEYWORD
        SolrQuerySpanKind.OPERATOR -> DefaultLanguageHighlighterColors.OPERATION_SIGN
    }
}
