package org.apache.solr.ide.server.query.screen

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.LightVirtualFile
import java.util.concurrent.CopyOnWriteArrayList
import org.apache.solr.ide.SolrBundle

/**
 * One query console tab: which connection and collection it queries, and what its form holds.
 *
 * **The state lives here, not in an editor.** Split Right makes a second editor for the same file,
 * and a fill from the collections tree reaches an open tab without knowing its editors; both have to
 * meet the form as it is. Each change says who made it, so a form can ignore its own writes and
 * redraw for anyone else's.
 *
 * A light file, so nothing reaches the disk and nothing is restored after a restart: query history
 * is how a query comes back.
 *
 * @property connectionId the connection it queries, by id, so an edited address is picked up
 * @property collection the collection or core it queries
 */
internal class SolrQueryScreenFile(
    val connectionId: String,
    val collection: String,
    initial: SolrQueryScreenState,
) : LightVirtualFile(SolrBundle.message("queryScreen.tabTitle", collection)) {

    private val listeners = CopyOnWriteArrayList<(Any?) -> Unit>()

    /** What the form holds now. */
    var state: SolrQueryScreenState = initial
        private set

    init {
        isWritable = false
    }

    /**
     * Replaces the state, and tells every listener who did.
     *
     * @param next the new state
     * @param source who made the change, so its own listener can ignore it
     */
    fun update(next: SolrQueryScreenState, source: Any?) {
        if (next == state) return
        state = next
        listeners.forEach { it(source) }
    }

    /**
     * Calls [listener] with the change's source whenever the state is replaced, until [parent] goes.
     *
     * @param parent what the subscription lives as long as
     * @param listener told who changed the state
     */
    fun addListener(parent: Disposable, listener: (source: Any?) -> Unit) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
    }
}
