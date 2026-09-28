package org.apache.solr.ide.code.highlighting

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import org.apache.solr.ide.code.SolrRecognizers
import org.apache.solr.ide.code.solrj.SolrJFieldPositions
import org.apache.solr.ide.model.query.SolrQueryExpressions
import org.apache.solr.ide.model.query.SolrQuerySpanKind
import org.jetbrains.uast.UastFacade

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
 * **The colours are this plugin's own keys, falling back to the platform's** — see [SolrQueryColors]
 * for why the fallback has to be one a string literal cannot swallow, and why a key that is a colour
 * in the abstract was invisible here in practice.
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
        // A file no JVM language reads can hold no call, and turning it away here is what keeps the
        // module gate below — an order-entry walk under a read action — off every editor pass in
        // every Markdown, YAML and JSON file the IDE has open.
        if (UastFacade.findPlugin(element.language) == null) return
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
        SolrQuerySpanKind.FIELD -> SolrQueryColors.FIELD
        SolrQuerySpanKind.OPERATOR -> SolrQueryColors.OPERATOR
    }
}
