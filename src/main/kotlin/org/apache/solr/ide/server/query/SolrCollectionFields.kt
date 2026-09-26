package org.apache.solr.ide.server.query

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap
import org.apache.solr.ide.configset.reading.SolrCompletionField
import org.apache.solr.ide.model.SolrConfigsetFacts
import org.apache.solr.ide.server.connection.SolrConnection
import org.apache.solr.ide.server.connection.SolrConnectionSettings
import org.apache.solr.ide.server.connection.SolrConnectionsListener
import org.apache.solr.ide.server.reading.SolrServerReader
import org.apache.solr.ide.server.transport.SolrResponse

/**
 * The fields a collection on a server holds, read once when asked and remembered after.
 *
 * **Filled only by an explicit ask, never by a timer and never ahead of need.** Completion in an
 * `.http` file is what asks, and only when the user invoked it rather than when it popped up while
 * typing — so a keystroke never opens a socket, and the one request a session costs is one the user
 * requested. This is the server-side half of the rule that nothing on the editor path contacts a
 * server: the file being typed in is a request about to be sent, and the gesture that fills this is
 * the user asking about the server it will be sent to.
 *
 * **Forgotten when the connection list changes or the collections view refreshes**, and at no other
 * time. An edited connection keeps its id, so a read keyed by that id would otherwise go on
 * describing the server it used to point at; and a refresh is the user saying that what the plugin
 * last read is no longer to be trusted. Only a successful read is kept — a failure is not an answer,
 * and remembering it would make the next explicit ask return the same failure without trying.
 */
@Service(Service.Level.PROJECT)
class SolrCollectionFields(private val project: Project) : Disposable {

    private data class Key(val connectionId: String, val collection: String)

    private val known = ConcurrentHashMap<Key, List<SolrCompletionField>>()

    init {
        project.messageBus.connect(this).subscribe(
            SolrConnectionSettings.CONNECTIONS_CHANGED,
            SolrConnectionsListener { forget() },
        )
    }

    /**
     * What an earlier read of [collection] on [connection] found, or null where there has been none.
     *
     * @param connection the server
     * @param collection the collection
     * @return the fields and patterns, or null where nothing has been read or the last read was
     *   forgotten
     */
    fun cached(connection: SolrConnection, collection: String): List<SolrCompletionField>? =
        known[Key(connection.id, collection)]

    /**
     * Reads [collection]'s schema from [connection], remembering it where the read succeeds.
     *
     * **A partial read is reported and not offered.** Its facts are real and not all of them, and
     * completion that offers half a collection's fields is read as offering all of them.
     *
     * @param connection the server to ask
     * @param collection the collection whose fields to read
     * @return the fields and patterns, or the failure that prevented reading them
     */
    suspend fun fetch(connection: SolrConnection, collection: String): SolrResponse<List<SolrCompletionField>> {
        val read = SolrServerReader.getInstance(project).schema(connection, collection)
            .map { fieldsIn(it, "$collection · ${connection.displayName}") }
        if (read is SolrResponse.Success) known[Key(connection.id, collection)] = read.value
        return read
    }

    /** Forgets every read, so the next explicit ask goes to the server. */
    fun forget() {
        known.clear()
    }

    /** Nothing to release; being a `Disposable` is what scopes the connection-list subscription. */
    override fun dispose() = Unit

    private fun fieldsIn(facts: SolrConfigsetFacts, source: String): List<SolrCompletionField> =
        facts.fields.map { SolrCompletionField(it.name, it.type, source) } +
            facts.dynamicFields.map { SolrCompletionField(it.pattern, it.field.type, source, dynamic = true) }

    /** Service lookup. */
    companion object {
        /**
         * The remembered reads for [project].
         *
         * @param project the project
         * @return the project-level service
         */
        fun getInstance(project: Project): SolrCollectionFields = project.service()
    }
}
