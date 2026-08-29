package org.apache.solr.ide.server.topology

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * Registers the collections tool window.
 *
 * [DumbAware] because nothing here reads an index — a Solr topology comes off the wire, and there is
 * no reason a developer waiting on indexing should also be unable to look at their server. The
 * marker interface rather than an `isDumbAware()` override, which is the mechanism this extension
 * point uses.
 */
class SolrCollectionsToolWindowFactory : ToolWindowFactory, DumbAware {

    /**
     * Builds the tool window's single tab.
     *
     * @param project the project the tool window belongs to
     * @param toolWindow the tool window to fill
     */
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = SolrCollectionsPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        // Tied to the content rather than the project: the panel's lifetime is the tab's, and the
        // scope it fetches on is a project service that outlives both deliberately.
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}
