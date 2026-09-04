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

        val schema = transport.get(connection.baseUrl, "/$collection/schema", credential)
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
     * What [connection] holds, in whichever vocabulary the server uses.
     *
     * **The mode is read before an endpoint is chosen, never by trying one and catching the
     * refusal.** A standalone Solr answers every `/admin/collections` action with HTTP 400, so a
     * reader that assumed the Collections API would report a hard failure against a healthy server —
     * and catching that 400 would also swallow the genuinely different one a malformed request
     * produces. The mode comes back from the same system-info call the version does, so asking costs
     * nothing.
     *
     * A server that will not say which mode it is in is asked for neither, and the caller is told
     * that rather than shown an empty list it cannot interpret.
     *
     * @param connection the server to ask
     * @return its collections or its cores, or the failure that prevented reading either
     */
    suspend fun topology(connection: SolrConnection): SolrResponse<SolrTopology> {
        val credential = credentialFor(connection)
        val transport = SolrHttpTransport.getInstance(project)

        val systemInfo = transport.get(connection.baseUrl, "/admin/info/system", credential)
        val mode = when (systemInfo) {
            is SolrResponse.Success -> SolrTopologyReader.modeIn(systemInfo.value)
            is SolrResponse.Partial -> SolrTopologyReader.modeIn(systemInfo.value)
            else -> return systemInfo.map { SolrTopology(SolrServerMode.UNKNOWN) }
        }

        return when (mode) {
            SolrServerMode.SOLR_CLOUD ->
                transport.get(connection.baseUrl, "/admin/collections?action=CLUSTERSTATUS", credential)
                    .map { SolrTopologyReader.cloudTopologyIn(it) }
            SolrServerMode.STANDALONE ->
                transport.get(connection.baseUrl, "/admin/cores?action=STATUS", credential)
                    .map { SolrTopologyReader.standaloneTopologyIn(it) }
            // Neither endpoint is asked, because one of them would fail and the other would answer
            // in a vocabulary nothing has established the server uses.
            SolrServerMode.UNKNOWN -> SolrResponse.Success(SolrTopology(SolrServerMode.UNKNOWN))
        }
    }

    /**
     * What [collection] actually holds on [connection], as the Luke handler reports it.
     *
     * **A third question, asked separately and never folded into the other two.** The schema — from
     * a configset or from the Schema API — says what fields are *declared*; this says what the index
     * *has*, which includes every field a dynamic pattern created at index time and no configset can
     * name. Merging the answers would make the drift comparison report those instances as fields the
     * repository forgot to declare.
     *
     * **Term counts are not asked for**, though Luke can give them. They arrive only when the
     * request names specific fields, which makes them one request per field — and server data moves
     * on request and on connection change, so fetching a field list must not quietly become fetching
     * fifty things.
     *
     * @param connection the server to ask
     * @param collection the collection whose index to inspect
     * @return what its index holds, or the failure that prevented reading it
     */
    suspend fun indexContents(connection: SolrConnection, collection: String): SolrResponse<SolrIndexContents> {
        val credential = credentialFor(connection)
        return SolrHttpTransport.getInstance(project)
            .get(connection.baseUrl, "/$collection/admin/luke", credential)
            .map { SolrLukeReader.read(it) }
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
            .get(connection.baseUrl, "/admin/info/system", credential)
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
     * **The username comes from the connection and only the password from `PasswordSafe`**, because
     * those are the two halves a user edits separately. The dialog's password field opens empty on a
     * connection that already has a secret, so changing a username alone leaves the secret unwritten
     * and still filed under the previous user. The stored username is therefore identical to the
     * connection's in every case where nothing is wrong, and differs only where the user has just
     * changed it — so preferring it cannot be right anywhere, and is silent where it is wrong: the
     * connection row shows the new user while every request authenticates as the old one.
     *
     * A connection naming a user the stored secret was not filed under is a credential the server
     * should refuse, which the user can see and correct by re-entering the password.
     */
    private fun credentialFor(connection: SolrConnection): SolrCredential {
        if (connection.username.isNullOrEmpty()) return SolrCredential.None
        return SolrCredential.of(
            username = connection.username,
            password = SolrConnectionSettings.getInstance(project).getPassword(connection.id),
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
