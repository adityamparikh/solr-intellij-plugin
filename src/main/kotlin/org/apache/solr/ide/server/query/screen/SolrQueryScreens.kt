package org.apache.solr.ide.server.query.screen

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import org.apache.solr.ide.server.connection.SolrConnection
import org.apache.solr.ide.server.connection.SolrConnectionSettings
import org.apache.solr.ide.server.connection.SolrConnectionsListener

/**
 * The query console tabs a project has open: one per connection and collection.
 *
 * **Opening a tab that is already open focuses it** rather than adding another, from the tree or
 * from anywhere else; a fill that comes with the request — *Find Documents with category* — replaces
 * what that tab's form holds, because the gesture names the query the user wants.
 *
 * **A tab whose connection is removed is closed**, since it would otherwise offer to query a server
 * that is no longer configured. An edited connection keeps its id and its tab, and the next Execute
 * reads the new address.
 */
@Service(Service.Level.PROJECT)
internal class SolrQueryScreens(private val project: Project) : Disposable {

    init {
        project.messageBus.connect(this).subscribe(
            SolrConnectionSettings.CONNECTIONS_CHANGED,
            SolrConnectionsListener { closeOrphans() },
        )
    }

    /**
     * Opens the tab for [collection] on [connection], or focuses the one already open.
     *
     * @param connection the server to query
     * @param collection the collection or core to query
     * @param fill what the form should hold, or null to leave an open tab's form as it is
     * @return the tab's file
     */
    fun open(connection: SolrConnection, collection: String, fill: SolrQueryScreenState? = null): SolrQueryScreenFile {
        val file = openFiles().firstOrNull { it.connectionId == connection.id && it.collection == collection }
            ?: SolrQueryScreenFile(connection.id, collection, fill ?: SolrQueryScreenState(SolrQueryScreenSpec.bundled))
        if (fill != null) file.update(fill, source = this)
        FileEditorManager.getInstance(project).openFile(file, true)
        return file
    }

    /** The console tabs currently open. */
    fun openFiles(): List<SolrQueryScreenFile> =
        FileEditorManager.getInstance(project).openFiles.filterIsInstance<SolrQueryScreenFile>()

    private fun closeOrphans() {
        val known = SolrConnectionSettings.getInstance(project).connections.mapTo(mutableSetOf()) { it.id }
        val manager = FileEditorManager.getInstance(project)
        openFiles().filter { it.connectionId !in known }.forEach { manager.closeFile(it) }
    }

    /** Nothing to release; being a `Disposable` scopes the connection-list subscription. */
    override fun dispose() = Unit

    /** Service lookup. */
    companion object {
        /**
         * The open tabs for [project].
         *
         * @param project the project
         * @return the project-level service
         */
        fun getInstance(project: Project): SolrQueryScreens = project.service()
    }
}

/**
 * Where the query console runs its queries.
 *
 * A service rather than a scope each editor makes, so the platform owns its lifetime and cancels it
 * with the project; an editor still cancels its own run when it closes.
 *
 * @property scope where queries are launched
 */
@Service(Service.Level.PROJECT)
internal class SolrQueryScreenScope(val scope: CoroutineScope)
