package org.apache.solr.ide.code.run

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import org.apache.solr.ide.SolrBundle
import org.apache.solr.ide.code.SolrRecognizers
import org.apache.solr.ide.code.solrj.SolrJFieldPositions
import org.jetbrains.uast.UastFacade

/**
 * A gutter icon beside a Solr query written in Java or Kotlin, which runs it.
 *
 * **The point is not to save typing, it is to close a loop.** A query in code can only be tried by
 * building the application, or by copying the string somewhere that can send it — and the copy is
 * where the mistake happens, because the string that gets pasted is rarely the string the program
 * builds. Running it from where it is written means the query that ran is the query in the file.
 *
 * **On the leaf, not on the expression**, which the platform requires: a marker returned for an
 * element that has children is reported as an error, and the daemon drops it. The leaf whose parent
 * is the argument is what carries it, which is also where a user's eye is when they read the line.
 */
class SolrRunQueryLineMarkerProvider : LineMarkerProvider {

    /**
     * A marker where [element] is the leaf of a query argument, and nothing anywhere else.
     *
     * Both conditions are the recognizer's rather than this file's: the module must carry a Solr
     * client, and the call must be one that names a query. A gutter icon that appeared on any string
     * resembling a query would be an offer to run something the plugin cannot actually see.
     *
     * @param element every leaf the daemon walks, on every editor pass
     * @return the marker, or null where this leaf carries no query
     */
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element.firstChild != null) return null
        // **Cheapest question first, and it is what makes registering without a language
        // affordable.** This runs for every leaf of every file the editor shows — Markdown, YAML,
        // JSON — and the module gate below walks the module's libraries under a read action. A file
        // no JVM language reads can hold no call, so it is turned away before that.
        if (UastFacade.findPlugin(element.language) == null) return null
        val file = element.containingFile ?: return null
        if (!SolrRecognizers.recognizeSolrIn(file)) return null

        val query = SolrJFieldPositions.runnableQueryAt(element) ?: return null

        return LineMarkerInfo(
            element,
            element.textRange,
            AllIcons.RunConfigurations.TestState.Run,
            { SolrBundle.message("code.runQuery.tooltip", query) },
            { _, clicked -> SolrRunQuery.from(clicked, query) },
            GutterIconRenderer.Alignment.LEFT,
            { SolrBundle.message("code.runQuery.name") },
        )
    }
}
