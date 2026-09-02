package org.apache.solr.ide.server.indexing

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.apache.solr.ide.server.connection.SolrConnection
import org.apache.solr.ide.server.connection.SolrConnectionSettings
import org.apache.solr.ide.server.transport.SolrCredential
import org.apache.solr.ide.server.transport.SolrHttpTransport
import org.apache.solr.ide.server.transport.SolrResponse

/**
 * When an indexed document becomes findable.
 *
 * **Stated in the confirmation rather than defaulted silently**, because an uncommitted document
 * that "isn't findable yet" is indistinguishable from a failed index to a user who was not told
 * which to expect — verified: a document sent with `commitWithin` returns `status: 0` and a query
 * for it immediately afterwards finds nothing.
 *
 * @property parameter the update parameter this sends, or null where it sends none
 * @property label what the confirmation calls it
 */
enum class SolrCommitMode(val parameter: String?, val label: String) {

    /**
     * Findable shortly, without forcing a commit.
     *
     * The default, and deliberately so. A hard commit on a shared server is somebody else's latency
     * spike and is the wrong thing to make easiest; three equal options in front of someone
     * indexing one test document is a quiz rather than a choice.
     */
    WITHIN("commitWithin=1000", "findable within a second"),

    /** Findable immediately, at the cost of a commit the whole server pays for. */
    IMMEDIATE("commit=true", "findable immediately, forcing a commit"),

    /** Sent and left to the server's own commit policy. */
    NONE(null, "left to the server's commit policy"),
}

/**
 * Sends a document to a collection.
 *
 * **The write shares the discipline the configset writes already follow**: invoked by name,
 * confirmed before acting, naming the server it is about to touch — and reporting from what Solr
 * answered rather than from having sent the request.
 *
 * What this does *not* do is decide whether a document is worth sending. That is
 * [SolrDocumentValidation]'s job, and it has to run before this rather than after, because the
 * outcomes it catches are ones Solr reports as success.
 */
@Service(Service.Level.PROJECT)
class SolrDocumentIndexer(private val project: Project) {

    /**
     * Indexes [document] into [collection].
     *
     * @param connection the server to write to
     * @param collection the collection to index into
     * @param document the document as JSON — one object, or an array of them
     * @param commit when the document should become findable
     * @return what Solr answered, which is not on its own proof the document is findable
     */
    suspend fun index(
        connection: SolrConnection,
        collection: String,
        document: String,
        commit: SolrCommitMode = SolrCommitMode.WITHIN,
    ): SolrResponse<Unit> {
        val query = commit.parameter?.let { "?$it" }.orEmpty()
        return SolrHttpTransport.getInstance(project)
            .post(
                connection.baseUrl,
                "/${encode(collection)}/update$query",
                // Wrapped where it is not already a list. Solr's update handler takes either, and a
                // user editing a generated document should not have to know which.
                asDocumentList(document).toByteArray(),
                SolrHttpTransport.JSON,
                credentialFor(connection),
            )
            .map { }
    }

    /**
     * [document] as something the update handler will take.
     *
     * Reachable so the wrapping can be checked without a server: a document wrapped twice is
     * rejected with a parse error that reads as the user's mistake.
     *
     * @param document a JSON object or array
     * @return an array, whichever arrived
     */
    internal fun asDocumentList(document: String): String {
        val trimmed = document.trim()
        return if (trimmed.startsWith("[")) trimmed else "[$trimmed]"
    }

    private fun credentialFor(connection: SolrConnection): SolrCredential {
        val username = connection.username ?: return SolrCredential.None
        val password = SolrConnectionSettings.getInstance(project).getPassword(connection.id)
            ?: return SolrCredential.Missing(username)
        return SolrCredential.Resolved(username, password)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    /** Service lookup. */
    companion object {
        /**
         * The indexer for [project].
         *
         * @param project the project whose connections it writes through
         * @return the project-level service
         */
        fun getInstance(project: Project): SolrDocumentIndexer = project.service()
    }
}
