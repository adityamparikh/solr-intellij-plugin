package org.apache.solr.ide.configset.activation

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vfs.VirtualFile
import org.apache.solr.ide.SolrBundle

/**
 * *Mark Directory as Solr Configset Root*, in the Project view's context menu — and *Unmark* on a
 * directory already marked.
 *
 * **For the configset detection turns away.** A repository holding Solr XML and no Solr client
 * cannot satisfy the dependency gate, so without a hand mark the plugin never wakes there. The
 * settings page lists the same roots; this is the gesture from where the directory already is.
 *
 * One directory at a time, because a toggle over a selection that is half marked has no single
 * right answer. Dumb-aware: it reads and writes settings and touches no index.
 */
class SolrMarkConfigsetRootAction : DumbAwareAction() {

    /**
     * Shows the entry for a single selected directory, worded for what pressing it would do.
     *
     * @param event the menu being shown
     */
    override fun update(event: AnActionEvent) {
        val dir = selectedDirectory(event)
        val project = event.project
        event.presentation.isEnabledAndVisible = dir != null && project != null
        if (dir == null || project == null) return
        val marked = dir.path in SolrConfigsetSettings.getInstance(project).manualRoots
        event.presentation.text =
            SolrBundle.message(if (marked) "configset.action.unmarkRoot" else "configset.action.markRoot")
    }

    /**
     * Marks the directory, or unmarks it where it is already marked, then re-checks open files.
     *
     * @param event the menu entry chosen
     */
    override fun actionPerformed(event: AnActionEvent) {
        val dir = selectedDirectory(event) ?: return
        val project = event.project ?: return
        val settings = SolrConfigsetSettings.getInstance(project)
        if (dir.path in settings.manualRoots) settings.removeManualRoot(dir.path) else settings.addManualRoot(dir)
        DaemonCodeAnalyzer.getInstance(project).restart()
    }

    /**
     * Reads the selection on a background thread, where the platform prefers action updates to run.
     *
     * @return the thread [update] runs on
     */
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    private fun selectedDirectory(event: AnActionEvent): VirtualFile? =
        event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.singleOrNull()?.takeIf { it.isDirectory }
}
