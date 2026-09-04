package org.apache.solr.ide.server.connection

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
import com.intellij.util.xmlb.annotations.XCollection
import com.intellij.util.messages.Topic

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
class SolrConnectionSettings(private val project: Project) :
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
        // **Annotated, or it is not written at all.** A `BaseState` collection reaches the
        // workspace file only when a binding is produced for it; without this the property is
        // skipped in silence -- `selectedConnectionId` beside it persists, so the file looks
        // written and merely has no connections in it. Every connection then survives exactly as
        // long as the IDE stays open.
        @get:XCollection
        val connections: MutableList<ConnectionState> by list<ConnectionState>()

        /** The identifier of the connection chosen as the default, or null where none was chosen. */
        var selectedConnectionId: String? by string()
    }

    /**
     * The connection chosen as this project's default, by identifier.
     *
     * The default rather than the only one: per the specification, each server-backed surface may
     * override it, because a drift view and a query console legitimately point at different servers.
     */
    var selectedConnectionId: String?
        get() = state.selectedConnectionId
        set(value) {
            state.selectedConnectionId = value
        }

    /**
     * The connection the default resolves to, or null where there are none configured.
     *
     * **Falls back to the first connection rather than resolving to nothing**, in two cases that
     * both reach users. Nothing is chosen at all until someone chooses — and a developer who
     * configured exactly one server has expressed their preference by doing so, so asking them to
     * also pick it from a list would be the plugin asking a question it can answer. And a chosen
     * identifier can name a connection that no longer exists, because the workspace file is
     * hand-editable and outlives any particular list; resolving that to nothing would show an empty
     * tool window on a project that has servers configured, which reads as broken rather than as
     * unchosen.
     */
    val selectedConnection: SolrConnection?
        get() = connections.firstOrNull { it.id == state.selectedConnectionId } ?: connections.firstOrNull()

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
     * Adds [connection], replacing any existing one with the same [SolrConnection.id], and leaves
     * any stored secret untouched.
     *
     * **The password argument's presence is what decides whether the secret is written**, which is
     * why this overload exists rather than one defaulting the password to null. Defaulted, "save
     * this connection" and "forget this connection's password" were the same call, and a caller
     * saving an edited display name silently dropped the credential. Overloading moves that decision
     * into the call's shape, where it cannot be made by omission.
     *
     * @param connection the non-secret half of the connection
     */
    fun addConnection(connection: SolrConnection) {
        state.connections.removeAll { it.id == connection.id }
        state.connections.add(
            ConnectionState().apply {
                id = connection.id
                displayName = connection.displayName
                baseUrl = connection.baseUrl
                username = connection.username
            },
        )
        connectionsChanged()
    }

    /**
     * Adds [connection] and stores [password] as its secret.
     *
     * The password is passed separately rather than as a field of [SolrConnection] so that it cannot
     * be persisted by accident — a secret that is never in the serialized object cannot leak into
     * the serialized file.
     *
     * @param connection the non-secret half of the connection
     * @param password the secret, stored in [PasswordSafe]; null forgets any stored secret
     */
    fun addConnection(connection: SolrConnection, password: CharArray?) {
        // Saved first, because `setPassword` labels the entry with the username it reads back from
        // the saved connection — writing the secret first would label it with the old username.
        addConnection(connection)
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
        // Cleared with the connection rather than left dangling: a selection naming nothing resolves
        // by falling back, so leaving it would make the fallback permanent and silently ignore the
        // next connection the user actually chooses under that same stale id.
        if (state.selectedConnectionId == id) state.selectedConnectionId = null
        setPassword(id, null)
        connectionsChanged()
    }

    /**
     * Stores or clears the secret for the connection with [id].
     *
     * @param id the connection's identifier
     * @param password the secret to store, or null to forget it
     */
    fun setPassword(id: String, password: CharArray?) {
        // Filed under the connection's real user, not its id: the id keys the entry, and the user
        // field is what names the account in the OS credential store a person may go and read. No
        // request is composed from it — see `getStoredUsername`.
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
     * **Nothing authenticating with this reads it**, and that is the point of saying so here. A
     * request takes its username from the connection and only its password from [PasswordSafe],
     * because those are the halves a user edits separately: the two agree wherever nothing is wrong
     * and differ only where a username was just changed, so a reader preferring this one would
     * authenticate as the previous user with nothing on screen saying so. What it is for is
     * observing where a secret was filed.
     *
     * @param id the connection's identifier
     * @return the stored username, or null
     */
    fun getStoredUsername(id: String): String? =
        PasswordSafe.instance.get(credentialAttributes(id))?.userName

    // Announced rather than polled, and only for the two calls that change the *list* — a password
    // written or forgotten leaves every view of it saying exactly what it said before.
    private fun connectionsChanged() {
        project.messageBus.syncPublisher(CONNECTIONS_CHANGED).connectionsChanged()
    }

    private fun credentialAttributes(id: String) =
        CredentialAttributes(generateServiceName(CREDENTIAL_SUBSYSTEM, id))

    /** Service lookup for these settings. */
    companion object {
        /** The PasswordSafe subsystem name under which Solr credentials are filed. */
        private const val CREDENTIAL_SUBSYSTEM = "Solr Connections"

        /**
         * Announces that the configured connections have changed.
         *
         * **A view of the list needs this because it has more than one author.** Connections are
         * added from the tool window's own `+` and from Settings → Tools → Solr Connections, and a
         * view that rebuilt itself only on the gestures it hosts is stale after every one of the
         * others — silently, because a stale list looks exactly like a short one.
         */
        @JvmField
        val CONNECTIONS_CHANGED: Topic<SolrConnectionsListener> =
            Topic.create("Solr connections changed", SolrConnectionsListener::class.java)

        /**
         * The connection settings for [project].
         *
         * @param project the project whose connections are wanted
         * @return the project-level service holding the per-user connection list
         */
        fun getInstance(project: Project): SolrConnectionSettings = project.service()
    }
}

/**
 * Told when the configured connection list changes.
 *
 * Carries nothing: what changed is the list, and every subscriber reads it back from
 * [SolrConnectionSettings] rather than from an event that would be a second copy able to disagree.
 */
fun interface SolrConnectionsListener {

    /** The connection list has changed; read it again. */
    fun connectionsChanged()
}
