package org.apache.solr.ide.server.connection

/**
 * The connections a settings page is editing, before anything is saved.
 *
 * **Separated from the panel that draws it, and the separation is what makes it testable.** A
 * settings page has to answer three questions correctly — has anything changed, what is written on
 * apply, what is restored on reset — and every one of them is logic rather than layout. Bound
 * directly into a panel they can only be exercised by driving a UI; here they are ordinary functions.
 *
 * **Passwords are held apart from the connections and never in one.** A [SolrConnection] is
 * persisted to the workspace file, so a secret on it would be a secret in a file; and a draft that
 * carried one would put it into every equality check, every `toString`, and every diff this class
 * performs to decide whether anything changed. Instead an edited secret is recorded per id, and only
 * ids appearing there are written on apply — which also means a user who edits a display name does
 * not rewrite a password they never touched.
 */
class SolrConnectionsEditorModel(private val settings: SolrConnectionSettings) {

    private val drafts = mutableListOf<SolrConnection>()
    private val editedPasswords = mutableMapOf<String, CharArray?>()

    /** The connections as they currently stand in the editor. */
    val connections: List<SolrConnection> get() = drafts.toList()

    /**
     * Loads what is saved, discarding anything unapplied.
     *
     * What a settings page's reset does, and what its first display does — the two are the same
     * operation, which is why there is one function rather than an `init` and a `reset` that could
     * drift.
     */
    fun reset() {
        drafts.clear()
        drafts += settings.connections
        // Edited secrets are dropped rather than reapplied: reset means what is saved, and a secret
        // typed and not applied was never saved.
        editedPasswords.clear()
    }

    /**
     * Adds [connection] to the editor.
     *
     * @param connection the connection to add
     */
    fun add(connection: SolrConnection) {
        drafts += connection
    }

    /**
     * Replaces the connection at [index] with [connection].
     *
     * @param index which connection to replace
     * @param connection what to replace it with
     */
    fun replace(index: Int, connection: SolrConnection) {
        drafts[index] = connection
    }

    /**
     * Removes the connection at [index], and forgets any secret typed for it.
     *
     * @param index which connection to remove
     */
    fun remove(index: Int) {
        val removed = drafts.removeAt(index)
        editedPasswords.remove(removed.id)
    }

    /**
     * Records a secret for the connection with [id], to be written on apply.
     *
     * @param id the connection the secret belongs to
     * @param password the secret, or null to clear whatever is stored
     */
    fun setPassword(id: String, password: CharArray?) {
        editedPasswords[id] = password
    }

    /**
     * Whether anything here differs from what is saved.
     *
     * **A typed secret counts as a change even where it matches what is stored**, because comparing
     * them would mean reading the stored one back to compare against — and a secret read for no
     * reason is a secret in one more place. Rewriting an identical password costs nothing; reading
     * one to avoid that costs the property this class is careful about.
     *
     * @return true where applying would change something
     */
    fun isModified(): Boolean = drafts != settings.connections || editedPasswords.isNotEmpty()

    /**
     * Writes the editor's state to the settings.
     *
     * Removals are applied before additions so that a connection renamed by being removed and
     * re-added under the same id ends up added rather than removed.
     */
    fun apply() {
        // One call rather than a remove-then-add sequence, so the list is announced once, when it
        // is whole. See `replaceConnections` for what a burst of announcements costs.
        settings.replaceConnections(drafts.toList())
        // After the connections, because `setPassword` files the secret under the connection's
        // username and reads that from the saved connection — writing it first would file it under
        // the username the connection used to have.
        editedPasswords.forEach { (id, password) -> settings.setPassword(id, password) }
        editedPasswords.clear()
    }
}
