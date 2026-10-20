package org.apache.solr.ide.server.query.screen

import com.intellij.httpClient.http.request.HttpRequestLanguage
import com.intellij.icons.AllIcons
import com.intellij.ide.scratch.ScratchFileService
import com.intellij.ide.scratch.ScratchRootType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import java.time.Duration
import javax.swing.JComponent
import javax.swing.JPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.solr.ide.SolrBundle
import org.apache.solr.ide.server.connection.SolrConnectionSettings
import org.apache.solr.ide.server.transport.SolrHttpTransport

/**
 * A query console tab: Solr Admin's query form above, what came back below.
 *
 * **Queries run through the plugin's own transport**, as a form POST, so every parameter is encoded
 * on its own and a value holding `&`, `+`, `#` or `%` arrives as typed. That makes them the
 * plugin's traffic, and bounded like the rest of it: a timeout longer than the default ten seconds,
 * since a console's query may be slow; one run per tab, a new Execute cancelling the one in flight;
 * and the run cancelled when the tab closes.
 *
 * **Open as .http** is the bridge to the HTTP Client, where a query is saved and shared.
 *
 * @param project the project
 * @param file the tab
 */
internal class SolrQueryScreenEditor(
  private val project: Project,
  private val file: SolrQueryScreenFile,
) : UserDataHolderBase(), FileEditor {

  /** The form. */
  val form = SolrQueryScreenForm(project, file, this)

  /** What came back. */
  val results =
    SolrQueryScreenResults(project).also { results ->
      Disposer.register(this, results)
      results.onRequestLine = { openAsHttp() }
    }

  /** The run in flight, or the last one. */
  var running: Job? = null
    private set

  private val settings
    get() = SolrConnectionSettings.getInstance(project)

  private val executeAction =
    object :
      DumbAwareAction(
        SolrBundle.message("queryScreen.execute"),
        SolrBundle.message("queryScreen.execute.description"),
        AllIcons.Actions.Execute,
      ) {
      override fun actionPerformed(event: AnActionEvent) {
        execute()
      }

      override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

  private val openAsHttpAction =
    object :
      DumbAwareAction(
        SolrBundle.message("queryScreen.openAsHttp"),
        SolrBundle.message("queryScreen.openAsHttp.description"),
        AllIcons.Actions.MenuOpen,
      ) {
      override fun actionPerformed(event: AnActionEvent) {
        openAsHttp()
      }

      override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

  private val root =
    JPanel(BorderLayout()).also { panel ->
      val toolbar =
        ActionManager.getInstance()
          .createActionToolbar(
            TOOLBAR_PLACE,
            DefaultActionGroup(executeAction, openAsHttpAction),
            true,
          )
      toolbar.targetComponent = panel
      panel.add(toolbar.component, BorderLayout.NORTH)
      panel.add(
        OnePixelSplitter(true, SPLIT).apply {
          firstComponent = JBScrollPane(form.component)
          secondComponent = results.component
        },
        BorderLayout.CENTER,
      )
      executeAction.registerCustomShortcutSet(CommonShortcuts.getCtrlEnter(), panel, this)
    }

  /**
   * Sends what the form holds, cancelling a run still in flight.
   *
   * @return the run, or null where nothing was sent — the reason is shown instead
   */
  fun execute(): Job? {
    val connection =
      settings.connections.firstOrNull { it.id == file.connectionId }
        ?: return null.also { results.showProblem(SolrBundle.message("queryScreen.noConnection")) }
    val request =
      when (val built = SolrQueryScreenRequest.of(file.state, file.collection)) {
        is SolrQueryScreenRequest.Built.Ready -> built.request
        is SolrQueryScreenRequest.Built.Refused ->
          return null.also { results.showProblem(messageFor(built)) }
      }

    running?.cancel()
    results.showRunning(request.shownUrl(connection.baseUrl))
    val run =
      project.service<SolrQueryScreenScope>().scope.launch {
        // Off the EDT: the credential is read from PasswordSafe, which may block.
        val answer =
          SolrHttpTransport.getInstance(project)
            .postForm(
              connection.baseUrl,
              request.path,
              request.sentParameters,
              settings.credentialFor(connection),
              CONSOLE_TIMEOUT,
            )
        // Parsed here too: a large answer read on the EDT would freeze the IDE while it parses.
        val shown = SolrQueryScreenAnswer.of(answer)
        withContext(Dispatchers.EDT) { results.show(shown) }
      }
    running = run
    return run
  }

  /**
   * Opens what the form holds as an HTTP Client request, in a new scratch file.
   *
   * @return the scratch file, or null where the form cannot be sent — the reason is shown instead
   */
  fun openAsHttp(): VirtualFile? {
    val request =
      when (val built = SolrQueryScreenRequest.of(file.state, file.collection)) {
        is SolrQueryScreenRequest.Built.Ready -> built.request
        is SolrQueryScreenRequest.Built.Refused ->
          return null.also { results.showProblem(messageFor(built)) }
      }
    val scratch =
      ScratchRootType.getInstance()
        .createScratchFile(
          project,
          "solr-${file.collection}.http",
          HttpRequestLanguage.INSTANCE,
          request.httpRequestText(),
          ScratchFileService.Option.create_new_always,
        ) ?: return null
    FileEditorManager.getInstance(project).openFile(scratch, true)
    return scratch
  }

  private fun messageFor(refused: SolrQueryScreenRequest.Built.Refused): String =
    when (refused.reason) {
      SolrQueryScreenRequest.Reason.HANDLER_NOT_A_PATH ->
        SolrBundle.message("queryScreen.handlerNotAPath", refused.detail)
      SolrQueryScreenRequest.Reason.RAW_WITHOUT_NAME ->
        SolrBundle.message("queryScreen.rawWithoutName", refused.detail)
    }

  override fun getComponent(): JComponent = root

  override fun getPreferredFocusedComponent(): JComponent? = form.input(FOCUSED) ?: form.component

  override fun getName(): String = SolrBundle.message("queryScreen.editorName")

  override fun getFile(): VirtualFile = file

  override fun setState(state: FileEditorState) = Unit

  override fun isModified(): Boolean = false

  override fun isValid(): Boolean = true

  override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit

  override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit

  /** Cancels the run in flight; the form and results go with the editor's disposal. */
  override fun dispose() {
    running?.cancel()
  }

  private companion object {
    /** Longer than the transport's ten seconds: a console's query may legitimately be slow. */
    val CONSOLE_TIMEOUT: Duration = Duration.ofSeconds(60)
    const val TOOLBAR_PLACE = "SolrQueryConsole"
    const val FOCUSED = "q"
    const val SPLIT = 0.55f
  }
}
