package org.apache.solr.ide.server.topology

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import org.apache.solr.ide.SolrBundle
import org.apache.solr.ide.server.drift.SolrDriftPanel

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
        val factory = ContentFactory.getInstance()

        val collections = SolrCollectionsPanel(project)
        // Tied to the content rather than the project: a panel's lifetime is its tab's, and the
        // scope both fetch on is a project service that outlives them deliberately.
        toolWindow.contentManager.addContent(
            factory.createContent(collections, SolrBundle.message("collections.tab.title"), false)
                .apply { setDisposer(collections) },
        )

        // A second tab rather than a second tool window: drift is about the same server the first
        // tab is browsing, and splitting them would make a user hunt for the answer to a question
        // the collections list just prompted.
        val drift = SolrDriftPanel(project)
        toolWindow.contentManager.addContent(
            factory.createContent(drift, SolrBundle.message("drift.tab.title"), false)
                .apply { setDisposer(drift) },
        )
    }
}
