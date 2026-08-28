package org.apache.solr.ide.server.reading

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import org.apache.solr.ide.model.SolrConfigsetFacts
import org.apache.solr.ide.server.connection.SolrConnection
import org.apache.solr.ide.server.connection.SolrConnectionSettings
import org.apache.solr.ide.server.transport.SolrCredential
import org.apache.solr.ide.server.transport.SolrHttpTransport
import org.apache.solr.ide.server.transport.SolrResponse

/**
 * What one collection on one server reported.
 *
 * @property facts the collection's schema, in the shape a configset parser also produces, so the two
 *   can be merged without either being privileged
 * @property solrVersion the version the server reports running, or null where it could not be read.
 *   Nullable because it is asked for separately and is not what the caller came for
 */
data class SolrServerRead(
    val facts: SolrConfigsetFacts,
    val solrVersion: String?,
)

/**
 * Reads a collection through a connection.
 *
 * **The layer the steps above this all reach for**, and the only place a connection's stored secret
 * is resolved. Everything beneath it exists on its own and is tested on its own — a transport that
 * classifies an answer, a reader that maps JSON into facts, a credential type that distinguishes a
 * missing password from an absent one. This composes them, and composition is all it does.
 *
 * **The credential is resolved here and cached nowhere.** It is read from `PasswordSafe` per call,
 * handed to the transport, and never held in a field, logged, or put into a failure's text. That is
 * the specification's rule and the reason this class exists rather than callers each reaching for the
 * settings themselves: one place to get wrong is better than four.
 *
 * **Nothing here touches the editor's model.** `SolrConfigsetReader.modelFor` builds a
 * repository-only model and continues to; a caller that wants both halves merges what this returns
 * with what that one holds, at the point of asking. The editor never waits on a network.
 */
@Service(Service.Level.PROJECT)
class SolrServerReader(private val project: Project) {

    /**
     * Reads [collection] from [connection].
     *
     * Two requests, and only the first is the point. The schema is what the caller asked for, so a
     * failure to read it is the outcome; the version is asked for beside it and its absence is
     * reported as absence — a server that answers its schema and refuses its system info has still
     * told the caller everything a comparison needs, and failing there would trade a complete answer
     * for an incomplete objection.
     *
     * @param connection the server to ask
     * @param collection the collection whose schema to read
     * @return the collection's facts and the server's version, or the failure that prevented it
     */
    suspend fun read(connection: SolrConnection, collection: String): SolrResponse<SolrServerRead> {
        val credential = credentialFor(connection)
        val transport = SolrHttpTransport.getInstance(project)

        val schema = transport.get(connection.baseUrl, "/solr/$collection/schema", credential)
            .map { SolrServerSchemaReader.read(it) }

        // The version is asked for only where a schema arrived. A server that could not answer the
        // question the caller asked will not be asked a second one it did not.
        return when (schema) {
            is SolrResponse.Success -> SolrResponse.Success(
                SolrServerRead(schema.value, versionOf(connection, credential)),
            )
            // Incomplete, and reported as such: the facts are real and are not all of them, which is
            // a distinction a drift comparison has to keep or it invents disagreement from truncation.
            is SolrResponse.Partial -> SolrResponse.Partial(SolrServerRead(schema.value, null), schema.detail)
            else -> schema.map { SolrServerRead(it, null) }
        }
    }

    /**
     * The version the server reports, or null where it did not answer.
     *
     * Every failure is null here rather than an outcome, which is the whole reason it is a separate
     * function: the caller is not asking for a version, and a model with facts and no version
     * resolves exactly as it did before a server was ever consulted.
     */
    private suspend fun versionOf(connection: SolrConnection, credential: SolrCredential): String? {
        val response = SolrHttpTransport.getInstance(project)
            .get(connection.baseUrl, "/solr/admin/info/system", credential)
        val body = when (response) {
            is SolrResponse.Success -> response.value
            is SolrResponse.Partial -> response.value
            else -> return null
        }
        return SolrServerSchemaReader.solrVersionIn(body)
    }

    /**
     * The credential this connection authenticates with, read at the point of use.
     *
     * The stored username is preferred over the connection's own, because `PasswordSafe` holds the
     * pair that was actually saved and a connection edited after the fact may name a user no secret
     * was filed under.
     */
    private fun credentialFor(connection: SolrConnection): SolrCredential {
        if (connection.username.isNullOrEmpty()) return SolrCredential.None
        val settings = SolrConnectionSettings.getInstance(project)
        return SolrCredential.of(
            username = settings.getStoredUsername(connection.id) ?: connection.username,
            password = settings.getPassword(connection.id),
        )
    }

    /** Service lookup. */
    companion object {

        /**
         * The reader for [project].
         *
         * @param project the project whose connections and transport it uses
         * @return the project-level service
         */
        fun getInstance(project: Project): SolrServerReader = project.service()
    }
}
