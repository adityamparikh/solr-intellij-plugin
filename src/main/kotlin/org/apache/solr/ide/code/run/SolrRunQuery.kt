package org.apache.solr.ide.code.run

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.psi.PsiElement
import com.intellij.ui.awt.RelativePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.solr.ide.SolrBundle
import org.apache.solr.ide.server.connection.SolrConnection
import org.apache.solr.ide.server.connection.SolrConnectionSettings
import org.apache.solr.ide.server.query.SolrQueryResultRenderer
import org.apache.solr.ide.server.query.SolrQueryRunner
import org.apache.solr.ide.server.reading.SolrServerReader
import org.apache.solr.ide.server.topology.SolrCollectionsScope
import org.apache.solr.ide.server.transport.SolrResponse

/**
 * Running the query at a gutter icon, and showing what came back.
 *
 * **A query in code names no collection, and that is the whole of what this has to solve.**
 * `q.setQuery("category:books")` says what to match and says nothing about where — the collection is
 * chosen when the client is built, often in another file, often from configuration. So the
 * connection comes from the one the user has selected, and the collection is asked for, from the
 * list that server actually holds rather than a box to type into.
 *
 * **Everything it shows comes from surfaces that already exist**: the topology reader supplies the
 * collections, the runner sends the query, and [SolrQueryResultRenderer] formats the answer — the
 * same renderer the query console prints above a response, so a result read here and a result read
 * there are the same text.
 */
internal object SolrRunQuery {

    /**
     * Runs [query] from [element], asking which collection first.
     *
     * @param element the leaf the icon sits on, which anchors the popups
     * @param query the query as the source spells it
     */
    fun from(element: PsiElement, query: String) {
        val project = element.project
        val connection = SolrConnectionSettings.getInstance(project).selectedConnection
            ?: return tell(element, SolrBundle.message("code.runQuery.noConnection"))

        project.service<SolrCollectionsScope>().scope.launch {
            val collections = collectionsOn(project, connection)
            withContext(Dispatchers.EDT) {
                if (collections.isEmpty()) {
                    tell(element, SolrBundle.message("code.runQuery.noCollections", connection.displayName))
                } else {
                    chooseThenRun(project, element, connection, collections, query)
                }
            }
        }
    }

    /** What [connection] holds, as names, empty where it could not be asked. */
    private suspend fun collectionsOn(project: Project, connection: SolrConnection): List<String> {
        val topology = SolrServerReader.getInstance(project).topology(connection)
        val value = (topology as? SolrResponse.Success)?.value
            ?: (topology as? SolrResponse.Partial)?.value
            ?: return emptyList()
        return (value.collections.map { it.name } + value.cores.map { it.name }).distinct().sorted()
    }

    private fun chooseThenRun(
        project: Project,
        element: PsiElement,
        connection: SolrConnection,
        collections: List<String>,
        query: String,
    ) {
        val step = object : BaseListPopupStep<String>(SolrBundle.message("code.runQuery.chooseCollection"), collections) {
            override fun onChosen(selectedValue: String, finalChoice: Boolean): PopupStep<*>? {
                run(project, element, connection, selectedValue, query)
                return FINAL_CHOICE
            }
        }
        val anchor = anchor(element) ?: return
        JBPopupFactory.getInstance().createListPopup(step).show(anchor)
    }

    private fun run(
        project: Project,
        element: PsiElement,
        connection: SolrConnection,
        collection: String,
        query: String,
    ) {
        project.service<SolrCollectionsScope>().scope.launch {
            val answered = SolrQueryRunner.getInstance(project).execute(connection, collection, query)
            val text = when (answered) {
                is SolrResponse.Success -> SolrQueryResultRenderer.render(answered.value)
                is SolrResponse.Partial -> SolrQueryResultRenderer.render(answered.value)
                is SolrResponse.SolrError ->
                    SolrBundle.message("code.runQuery.solrError", answered.code, answered.message.orEmpty())
                is SolrResponse.TransportFailure -> answered.description
                is SolrResponse.Unrecognized -> answered.description
            }
            withContext(Dispatchers.EDT) { tell(element, text) }
        }
    }

    // Shown where the icon is rather than in a tool window: the promise of this gesture is that a
    // reader never leaves the file, and a result that opens a panel somewhere else is a different
    // gesture wearing the same icon.
    private fun tell(element: PsiElement, text: String) {
        val anchor = anchor(element) ?: return
        JBPopupFactory.getInstance().createMessage(text).show(anchor)
    }

    /**
     * Where to put a popup, or null where there is nowhere to put one.
     *
     * Nullable rather than asserted. A gutter click implies an open editor, so demanding one would
     * be right almost always — and the exception is a request that outlives the file it started in,
     * which is ordinary: a server takes a second to answer and the user closes the tab. Throwing
     * there turns a result nobody is waiting for into an error report.
     */
    private fun anchor(element: PsiElement): RelativePoint? =
        FileEditorManager.getInstance(element.project).selectedTextEditor
            ?.component
            ?.let { RelativePoint.getNorthEastOf(it) }
}
