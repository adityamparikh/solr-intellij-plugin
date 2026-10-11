package org.apache.solr.ide.server.query.screen

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.BoxLayout
import javax.swing.JPanel
import org.apache.solr.ide.SolrBundle
import org.apache.solr.ide.server.query.SolrQueryResultRenderer

/**
 * What the query console shows under its form: the request, what it amounted to, and what came back.
 *
 * **The raw response is always there**, in a read-only viewer highlighted by its content type, so an
 * XML answer, a CSV one or the error page behind a 404 is shown exactly as sent. The summary table
 * that the HTTP Client prints above a Solr answer is shown above it where the answer is a JSON query
 * result, and only there.
 *
 * @param project the project, for the viewer's highlighting
 */
internal class SolrQueryScreenResults(private val project: Project) : Disposable {

    /** Called when the request line is clicked. */
    var onRequestLine: () -> Unit = {}

    private val requestLine = ActionLink("") { onRequestLine() }
    private val status = JBLabel()
    private val summary = JBTextArea().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.Fonts.label().size)
    }
    private val document = EditorFactory.getInstance().createDocument("")
    private val viewer = EditorFactory.getInstance().createViewer(document, project, EditorKind.PREVIEW) as EditorEx
    private val tabs = JBTabbedPane().apply {
        addTab(SolrBundle.message("queryScreen.tab.results"), JBScrollPane(summary))
        addTab(SolrBundle.message("queryScreen.tab.response"), viewer.component)
    }

    /** The panel, for an editor to place. */
    val component: JPanel = JPanel(BorderLayout()).apply {
        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(4, 8)
            add(requestLine)
            add(status)
        }
        add(header, BorderLayout.NORTH)
        add(tabs, BorderLayout.CENTER)
    }

    init {
        requestLine.isVisible = false
    }

    /** The request line as shown, empty before a run. */
    val shownUrl: String get() = requestLine.text
    /** The status line as shown. */
    val statusText: String get() = status.text.orEmpty()
    /** The summary table as shown. */
    val summaryText: String get() = summary.text
    /** The raw response as shown. */
    val responseText: String get() = document.text
    /** Whether the raw response is the tab in front. */
    val showsResponse: Boolean get() = tabs.selectedIndex == RESPONSE

    /**
     * Shows that [url] has been sent and nothing has come back yet.
     *
     * @param url the request, as a GET URL
     */
    fun showRunning(url: String) {
        requestLine.text = url
        requestLine.isVisible = true
        status.text = SolrBundle.message("queryScreen.running")
        summary.text = ""
        setResponse("", null)
    }

    /**
     * Shows a reason nothing was sent, leaving the last answer in place.
     *
     * @param text the reason
     */
    fun showProblem(text: String) {
        status.text = text
    }

    /**
     * Shows what an answer amounted to.
     *
     * @param answer the answer
     */
    fun show(answer: SolrQueryScreenAnswer) {
        when (answer) {
            is SolrQueryScreenAnswer.Table -> {
                status.text = when {
                    !answer.partial -> ""
                    answer.partialDetail != null -> SolrBundle.message("queryScreen.partial", answer.partialDetail)
                    else -> SolrBundle.message("queryScreen.partial.noDetail")
                }
                summary.text = SolrQueryResultRenderer.render(answer.result)
                setResponse(answer.raw.body, answer.raw.contentType)
                tabs.selectedIndex = RESULTS
            }
            is SolrQueryScreenAnswer.Raw -> {
                status.text = ""
                summary.text = SolrBundle.message("queryScreen.notAQueryResult")
                setResponse(answer.raw.body, answer.raw.contentType)
                tabs.selectedIndex = RESPONSE
            }
            is SolrQueryScreenAnswer.Refused -> {
                status.text = answer.message
                    ?.let { SolrBundle.message("queryScreen.refusedBySolr", answer.code.toString(), it) }
                    ?: SolrBundle.message("queryScreen.refusedBySolr.noMessage", answer.code.toString())
                summary.text = ""
                setResponse(answer.raw.body, answer.raw.contentType)
                tabs.selectedIndex = RESPONSE
            }
            is SolrQueryScreenAnswer.NotSent -> {
                status.text = SolrBundle.message("queryScreen.notSent", answer.description)
                summary.text = ""
                setResponse("", null)
            }
        }
    }

    // A document refuses `\r`, and a servlet's error page arrives with `\r\n`.
    private fun setResponse(body: String, contentType: String?) {
        WriteAction.run<Throwable> { document.setText(StringUtil.convertLineSeparators(body)) }
        val fileType = FileTypeManager.getInstance().getFileTypeByExtension(SolrQueryScreenAnswer.extensionFor(contentType))
        viewer.highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, fileType)
    }

    /** Releases the viewer. */
    override fun dispose() {
        EditorFactory.getInstance().releaseEditor(viewer)
    }

    private companion object {
        const val RESULTS = 0
        const val RESPONSE = 1
    }
}
