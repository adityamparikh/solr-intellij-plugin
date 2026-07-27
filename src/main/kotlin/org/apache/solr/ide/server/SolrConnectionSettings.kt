package org.apache.solr.ide.server

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * One configured Solr server, as it is persisted — everything except the secret.
 *
 * @property id a stable identifier, used as the PasswordSafe key and never shown to the user
 * @property displayName the label shown in the connections list
 * @property baseUrl the server root, such as `http://localhost:8983/solr`
 * @property username the user to authenticate as, or null for an unauthenticated server
 */
data class SolrConnection(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val username: String? = null,
)

/**
 * Per-user settings holding the Solr servers this developer can talk to.
 *
 * **This is a second settings surface, not an extension of the first.** Configset roots live in
 * [org.apache.solr.ide.configset.activation.SolrConfigsetSettings], which persists to the shared `solr.xml`,
 * because a marked root is a fact about the project — the same directory is a configset for everyone
 * on the team. A connection is the opposite: it is a fact about one developer's machine. The URL may
 * be a personal port-forward and the credentials are personal by definition, so committing either
 * would at best be noise and at worst a leaked secret. Connections therefore persist to the
 * workspace file, which is per-user and conventionally not version-controlled.
 *
 * **Passwords never enter that file either.** Only the non-secret fields of [SolrConnection] are
 * serialized; the secret goes to the IDE's [PasswordSafe] under a key derived from the connection's
 * [SolrConnection.id]. A workspace file that leaks is then an inventory of hostnames, not of
 * credentials.
 *
 * Nothing here is consulted on the editor path. Connections exist for the server-backed surfaces,
 * and configset editing works with no connection configured at all.
 */
@Service(Service.Level.PROJECT)
@State(name = "SolrConnectionSettings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class SolrConnectionSettings :
    SimplePersistentStateComponent<SolrConnectionSettings.State>(State()) {

    /** The persisted, secret-free form of one connection. */
    class ConnectionState : BaseState() {
        /** Stable identifier; also the PasswordSafe key. */
        var id: String? by string()

        /** Label shown in the connections list. */
        var displayName: String? by string()

        /** Server root URL. */
        var baseUrl: String? by string()

        /** User to authenticate as, or null when the server needs no authentication. */
        var username: String? by string()
    }

    /**
     * The persisted form of these settings, serialized to the workspace file and therefore private
     * to one developer's checkout.
     */
    class State : BaseState() {
        /** The configured connections, in the order they were added. */
        val connections: MutableList<ConnectionState> by list()
    }

    /**
     * The configured connections, in the order they were added.
     *
     * Entries missing an id or a base URL are skipped rather than surfaced as broken rows: the
     * workspace file is hand-editable and merged by no one, so a malformed entry is a plausible
     * accident and not worth an error dialog.
     */
    val connections: List<SolrConnection>
        get() = state.connections.mapNotNull { stored ->
            val id = stored.id ?: return@mapNotNull null
            val baseUrl = stored.baseUrl ?: return@mapNotNull null
            SolrConnection(id, stored.displayName ?: baseUrl, baseUrl, stored.username)
        }

    /**
     * Adds [connection], replacing any existing one with the same [SolrConnection.id].
     *
     * The password is passed separately rather than as a field of [SolrConnection] so that it cannot
     * be persisted by accident — a secret that is never in the serialized object cannot leak into
     * the serialized file.
     *
     * @param connection the non-secret half of the connection
     * @param password the secret, stored in [PasswordSafe]; null clears any stored secret
     */
    fun addConnection(connection: SolrConnection, password: CharArray? = null) {
        state.connections.removeAll { it.id == connection.id }
        state.connections.add(
            ConnectionState().apply {
                id = connection.id
                displayName = connection.displayName
                baseUrl = connection.baseUrl
                username = connection.username
            },
        )
        setPassword(connection.id, password)
    }

    /**
     * Removes the connection with [id] and forgets its stored secret.
     *
     * Clearing the secret is part of removal rather than a separate step, so that deleting a
     * connection cannot leave an orphaned credential behind in [PasswordSafe] under a key nothing
     * refers to any more.
     *
     * @param id the identifier of the connection to remove; an unknown id is ignored
     */
    fun removeConnection(id: String) {
        state.connections.removeAll { it.id == id }
        setPassword(id, null)
    }

    /**
     * Stores or clears the secret for the connection with [id].
     *
     * @param id the connection's identifier
     * @param password the secret to store, or null to forget it
     */
    fun setPassword(id: String, password: CharArray?) {
        // Filed under the connection's real user, not its id. The id keys the PasswordSafe entry;
        // the user field has to carry the username the server will actually be sent, or the first
        // authenticated request will send an identifier no Solr has heard of.
        val user = connections.firstOrNull { it.id == id }?.username
        val credentials = password?.let { Credentials(user, it) }
        PasswordSafe.instance.set(credentialAttributes(id), credentials)
    }

    /**
     * The stored secret for the connection with [id], or null if none is stored.
     *
     * @param id the connection's identifier
     * @return the secret, or null
     */
    fun getPassword(id: String): String? =
        PasswordSafe.instance.get(credentialAttributes(id))?.getPasswordAsString()

    /**
     * The username stored alongside the secret for [id], or null if none is stored.
     *
     * Reads back from [PasswordSafe] rather than from the persisted state, so that callers building
     * an authenticated request take both halves of a credential from the same place.
     *
     * @param id the connection's identifier
     * @return the stored username, or null
     */
    fun getStoredUsername(id: String): String? =
        PasswordSafe.instance.get(credentialAttributes(id))?.userName

    private fun credentialAttributes(id: String) =
        CredentialAttributes(generateServiceName(CREDENTIAL_SUBSYSTEM, id))

    /** Service lookup for these settings. */
    companion object {
        /** The PasswordSafe subsystem name under which Solr credentials are filed. */
        private const val CREDENTIAL_SUBSYSTEM = "Solr Connections"

        /**
         * The connection settings for [project].
         *
         * @param project the project whose connections are wanted
         * @return the project-level service holding the per-user connection list
         */
        fun getInstance(project: Project): SolrConnectionSettings = project.service()
    }
}
