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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.solr.ide.SolrBundle
import org.apache.solr.ide.server.connection.SolrConnection
import org.apache.solr.ide.server.connection.SolrConnectionSettings
import org.apache.solr.ide.server.query.SolrQueryResultRenderer
import org.apache.solr.ide.server.query.SolrQueryRunner
import org.apache.solr.ide.server.reading.SolrServerMode
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
     * @return the request in flight, up to the chooser or the refusal being shown; the gutter click
     *   ignores it, and a test waits on it to drive the gesture through without a popup to press
     */
    fun from(element: PsiElement, query: String): Job {
        val project = element.project
        return project.service<SolrCollectionsScope>().scope.launch {
            val choices = choicesFor(project)
            withContext(Dispatchers.EDT) {
                when (choices) {
                    is SolrRunChoices.Refusal -> tell(element, choices.message)
                    is SolrRunChoices.Offer ->
                        chooseThenRun(project, element, choices.connection, choices.collections, query)
                }
            }
        }
    }

    /**
     * What the chooser offers on the selected connection, or why it offers nothing.
     *
     * **A server that could not be asked is not a server with no collections**, and saying it was
     * would send the reader to look for collections that may well exist. An empty answer names the
     * connection that gave it; a failed one says what failed, in the same words a failed query uses;
     * and one that answered without saying which mode it runs in is refused as not Solr, since
     * nothing was asked for its collections at all.
     */
    suspend fun choicesFor(project: Project): SolrRunChoices {
        val connection = SolrConnectionSettings.getInstance(project).selectedConnection
            ?: return SolrRunChoices.Refusal(SolrBundle.message("code.runQuery.noConnection"))
        val topology = SolrServerReader.getInstance(project).topology(connection)
        val value = topology.arrived() ?: return SolrRunChoices.Refusal(failureText(topology))
        // Answered, but not as Solr: both supported lines always say which mode they run in, and
        // the reader asks for neither list without one. Its empty topology is the absence of an
        // answer, and reporting it as "no collections" is the claim this function exists to avoid.
        if (value.mode == SolrServerMode.UNKNOWN) {
            return SolrRunChoices.Refusal(SolrBundle.message("code.runQuery.notSolr", connection.displayName))
        }
        val collections = (value.collections.map { it.name } + value.cores.map { it.name }).distinct().sorted()
        return if (collections.isEmpty()) {
            SolrRunChoices.Refusal(SolrBundle.message("code.runQuery.noCollections", connection.displayName))
        } else {
            SolrRunChoices.Offer(connection, collections)
        }
    }

    /** Runs [query] against [collection] and returns what the popup beside the icon says. */
    suspend fun answerFor(project: Project, connection: SolrConnection, collection: String, query: String): String {
        val answered = SolrQueryRunner.getInstance(project).execute(connection, collection, query)
        return answered.arrived()?.let(SolrQueryResultRenderer::render) ?: failureText(answered)
    }

    // What arrived, complete or not: a partial answer is real, as `SolrResponse.map` also holds.
    private fun <T> SolrResponse<T>.arrived(): T? = when (this) {
        is SolrResponse.Success -> value
        is SolrResponse.Partial -> value
        else -> null
    }

    private fun failureText(response: SolrResponse<*>): String = when (response) {
        is SolrResponse.SolrError -> SolrBundle.message("code.runQuery.solrError", response.code, response.message.orEmpty())
        is SolrResponse.TransportFailure -> response.description
        is SolrResponse.Unrecognized -> response.description
        is SolrResponse.Success, is SolrResponse.Partial -> error("not a failure: $response")
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
            val text = answerFor(project, connection, collection, query)
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

/**
 * What the run gesture can offer once the selected connection has been asked.
 *
 * The click itself is popups, which a headless test cannot drive; this is the decision under them,
 * returned rather than shown so that a test can hold it.
 */
internal sealed interface SolrRunChoices {

    /**
     * The collections and cores [connection] holds, sorted, for the chooser.
     *
     * @property connection the connection that answered, which the query then runs on
     * @property collections the names to choose from, never empty
     */
    data class Offer(val connection: SolrConnection, val collections: List<String>) : SolrRunChoices

    /**
     * Nothing to choose from, and the sentence saying why.
     *
     * @property message what the popup beside the icon says instead of a chooser
     */
    data class Refusal(val message: String) : SolrRunChoices
}
