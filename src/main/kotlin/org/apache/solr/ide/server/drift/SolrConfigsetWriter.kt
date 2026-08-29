package org.apache.solr.ide.server.drift

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.apache.solr.ide.server.connection.SolrConnection
import org.apache.solr.ide.server.connection.SolrConnectionSettings
import org.apache.solr.ide.server.reading.SolrServerMode
import org.apache.solr.ide.server.reading.SolrServerReader
import org.apache.solr.ide.server.transport.SolrCredential
import org.apache.solr.ide.server.transport.SolrHttpTransport
import org.apache.solr.ide.server.transport.SolrResponse

/**
 * The two writes the drift view can perform: uploading a configset, and reloading a collection.
 *
 * **Neither reports success on its own answer.** A write that returns HTTP 200 with
 * `responseHeader.status` 0 proves the request was accepted, not that the server now reflects it —
 * and that is not a hypothetical. Uploading a configset lacking `_version_` returns status 0 and the
 * name appears in `action=LIST`, and Solr then refuses to build a collection from it. Reload timing
 * and a multi-replica collection still propagating are two more gaps between the two facts. So the
 * caller re-reads, and the drift view reports from the read rather than from the write.
 *
 * **Upload is a SolrCloud operation and the mode is checked before asking.** A standalone server
 * answers every `/admin/configs` action with HTTP 400 and "Solr instance is not running in SolrCloud
 * mode" — verified — so a writer that tried anyway would report a hard failure against a healthy
 * server, and catching that 400 would also swallow the genuinely different one a malformed request
 * produces.
 */
@Service(Service.Level.PROJECT)
class SolrConfigsetWriter(private val project: Project) {

    /**
     * Uploads [archive] as the configset named [name].
     *
     * @param connection the server to write to
     * @param name the configset name to create or replace
     * @param archive the zipped configset, as [SolrConfigsetArchive] produces it
     * @param overwrite whether to replace a configset of that name that already exists
     * @return what Solr answered, which is not on its own proof the server now agrees
     */
    suspend fun upload(
        connection: SolrConnection,
        name: String,
        archive: ByteArray,
        overwrite: Boolean = true,
    ): SolrResponse<Unit> {
        val mode = SolrServerReader.getInstance(project).topology(connection).let {
            (it as? SolrResponse.Success)?.value?.mode ?: (it as? SolrResponse.Partial)?.value?.mode
        }
        if (mode != null && mode != SolrServerMode.SOLR_CLOUD) {
            return SolrResponse.TransportFailure(NOT_SOLR_CLOUD)
        }

        val path = "/solr/admin/configs?action=UPLOAD&name=${encode(name)}&overwrite=$overwrite"
        return SolrHttpTransport.getInstance(project)
            .post(connection.baseUrl, path, archive, credentialFor(connection))
            .map { }
    }

    /**
     * Reloads [collection] so the server picks up a configset that changed underneath it.
     *
     * **The collection form rather than the core form**, though both answer. A pairing names a
     * collection, and reaching for the cores underneath it would mean resolving replicas this
     * plugin has no reason to know about — and getting that wrong on a multi-replica collection
     * would reload one replica and report the collection reloaded.
     *
     * @param connection the server to write to
     * @param collection the collection to reload
     * @return what Solr answered
     */
    suspend fun reload(connection: SolrConnection, collection: String): SolrResponse<Unit> =
        SolrHttpTransport.getInstance(project)
            .get(
                connection.baseUrl,
                "/solr/admin/collections?action=RELOAD&name=${encode(collection)}",
                credentialFor(connection),
            )
            .map { }

    private fun credentialFor(connection: SolrConnection): SolrCredential {
        val username = connection.username ?: return SolrCredential.None
        val settings = SolrConnectionSettings.getInstance(project)
        val password = settings.getPassword(connection.id) ?: return SolrCredential.Missing(username)
        return SolrCredential.Resolved(username, password)
    }

    // Names come from a user and from a directory on disk, so they carry whatever characters those
    // allow. Encoded rather than trusted: an unencoded `&` would silently truncate the request into
    // a different one, which for a write is a different write.
    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    /** Service lookup. */
    companion object {
        /** What a standalone server is told, in the plugin's words because Solr was never asked. */
        const val NOT_SOLR_CLOUD: String =
            "this server is not running in SolrCloud mode, and only SolrCloud accepts a configset upload"

        /**
         * The writer for [project].
         *
         * @param project the project whose connections it writes through
         * @return the project-level service
         */
        fun getInstance(project: Project): SolrConfigsetWriter = project.service()
    }
}
