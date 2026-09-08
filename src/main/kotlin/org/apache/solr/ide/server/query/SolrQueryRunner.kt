package org.apache.solr.ide.server.query

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.apache.solr.ide.server.connection.SolrConnection
import org.apache.solr.ide.server.connection.SolrConnectionSettings
import org.apache.solr.ide.server.transport.SolrHttpTransport
import org.apache.solr.ide.server.transport.SolrResponse
import tools.jackson.databind.JsonNode

/**
 * Runs one query against a collection and reads the answer.
 *
 * **The plugin has never sent a query before, and that is worth saying.** Everything the server
 * surface does today either reads a server's shape — its collections, a schema, an index's fields —
 * or writes a configset to it. The query console runs queries, but the *HTTP Client* runs them: this
 * plugin contributes the request text and reads the response back, and never holds the connection.
 * That is exactly right for a request a user is editing, and it is why running a query written in
 * Java needs something new — there is no `.http` file in the picture, and the code being read is not
 * a request.
 *
 * **Only the query itself travels.** No field list, no sort, no rows: the point is to see what a `q`
 * matches, and every parameter added here is one the code did not ask for and the user would have to
 * notice was not theirs.
 *
 * Nothing on the editor path calls this. It runs when a user asks, which is the rule the whole
 * server surface keeps: server data moves on request and on nothing else.
 */
@Service(Service.Level.PROJECT)
class SolrQueryRunner(private val project: Project) {

    /**
     * Sends [query] to [collection] on [connection] and reads what comes back.
     *
     * Named `execute` rather than `run` because Kotlin's standard library puts a `run` extension on
     * every receiver, and a member of that name loses to it at some call sites — silently, by
     * resolving to something that still compiles.
     *
     * @param connection the server to ask
     * @param collection the collection or core to ask it about
     * @param query the `q` to send, exactly as the source spells it
     * @return the parsed result, or the failure that stopped it
     */
    suspend fun execute(
        connection: SolrConnection,
        collection: String,
        query: String,
    ): SolrResponse<SolrQueryResult> {
        // Leading slash, as every other caller of the transport writes it: the path is relative
        // to the root the connection names, and the transport joins the two verbatim.
        val path = "/${encode(collection)}/select?q=${encode(query)}"
        val answered = SolrHttpTransport.getInstance(project).get(
            connection.baseUrl,
            path,
            SolrConnectionSettings.getInstance(project).credentialFor(connection),
        )

        // Read out rather than mapped, because a body that parses to nothing is a *different*
        // outcome from the one that arrived, and `map` can only carry the same one forward.
        return when (answered) {
            is SolrResponse.Success -> parsed(answered.value)
            is SolrResponse.Partial -> parsed(answered.value)
            is SolrResponse.SolrError -> answered
            is SolrResponse.TransportFailure -> answered
            is SolrResponse.Unrecognized -> answered
        }
    }

    /**
     * The result [json] holds, or a failure saying it holds none.
     *
     * A 200 that is not a query answer is a healthy server answering a different question — a
     * handler that is not `select`, or a core rendering an error page. Reported rather than rendered
     * as an empty result, which a reader would take to mean their query matched nothing.
     */
    private fun parsed(json: JsonNode): SolrResponse<SolrQueryResult> =
        SolrQueryResultReader.read(json)
            ?.let { SolrResponse.Success(it) }
            ?: SolrResponse.TransportFailure("the server answered, but not with a query result")

    // Names come from a user and queries from source code, so both carry whatever characters those
    // allow. An unencoded `&` would truncate the query into a parameter Solr reads as something else.
    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    /** Service lookup. */
    companion object {

        /**
         * The runner for [project].
         *
         * @param project the project whose connections and transport it uses
         * @return the project-level service
         */
        fun getInstance(project: Project): SolrQueryRunner = project.service()
    }
}
