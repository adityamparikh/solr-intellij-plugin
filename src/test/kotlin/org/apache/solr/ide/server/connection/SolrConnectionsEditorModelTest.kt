package org.apache.solr.ide.server.connection

import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/**
 * What a settings page has to get right, tested without drawing one.
 *
 * Three questions decide whether a settings page behaves: has anything changed, what is written on
 * apply, and what comes back on reset. All three are logic, and bound into a panel they could only
 * be exercised by driving a UI.
 */
class SolrConnectionsEditorModelTest : SolrConfigsetTestCase() {

    private val model get() = SolrConnectionsEditorModel(connectionSettings)

    private fun connection(id: String, username: String? = null) = SolrConnection(
        id = id,
        displayName = "Solr $id",
        baseUrl = "http://localhost:8983/solr",
        username = username,
    )

    // --- what it shows ----------------------------------------------------------------------------

    fun testResetShowsWhatIsSaved() {
        connectionSettings.addConnection(connection("a"))
        val editor = model

        editor.reset()

        assertEquals(listOf("a"), editor.connections.map { it.id })
    }

    fun testNothingIsModifiedBeforeAnythingIsEdited() {
        connectionSettings.addConnection(connection("a"))
        val editor = model
        editor.reset()

        assertFalse(editor.isModified())
    }

    // --- what it writes ---------------------------------------------------------------------------

    fun testAnAddedConnectionIsSaved() {
        val editor = model
        editor.reset()

        editor.add(connection("new"))

        assertTrue(editor.isModified())
        editor.apply()
        assertEquals(listOf("new"), connectionSettings.connections.map { it.id })
    }

    fun testARemovedConnectionIsDeleted() {
        connectionSettings.addConnection(connection("a"))
        connectionSettings.addConnection(connection("b"))
        val editor = model
        editor.reset()

        editor.remove(0)
        editor.apply()

        assertEquals(listOf("b"), connectionSettings.connections.map { it.id })
    }

    fun testAnEditedConnectionIsReplacedRatherThanDuplicated() {
        connectionSettings.addConnection(connection("a"))
        val editor = model
        editor.reset()

        editor.replace(0, connection("a").copy(baseUrl = "http://elsewhere:8983/solr"))
        editor.apply()

        assertEquals(1, connectionSettings.connections.size)
        assertEquals("http://elsewhere:8983/solr", connectionSettings.connections.single().baseUrl)
    }

    // --- the secret, which is the part worth being careful about -----------------------------------

    /**
     * A typed secret reaches `PasswordSafe` and nothing else.
     *
     * Written after the connections deliberately: `setPassword` files the secret under the
     * connection's username and reads that from the *saved* connection, so writing it first would
     * file it under the username the connection used to have.
     */
    fun testATypedPasswordIsStored() {
        val editor = model
        editor.reset()
        editor.add(connection("a", username = "solr"))

        editor.setPassword("a", "SolrRocks".toCharArray())
        editor.apply()

        assertEquals("SolrRocks", connectionSettings.getPassword("a"))
        assertEquals("solr", connectionSettings.getStoredUsername("a"))
    }

    /**
     * Editing something else does not rewrite a password nobody touched.
     *
     * The reason edited secrets are tracked separately rather than carried on a draft: a draft with a
     * password on it would rewrite the secret on every apply, and a user renaming a connection would
     * silently re-file a credential they never looked at.
     */
    fun testEditingADisplayNameLeavesAStoredPasswordAlone() {
        connectionSettings.addConnection(connection("a", username = "solr"))
        connectionSettings.setPassword("a", "SolrRocks".toCharArray())
        val editor = model
        editor.reset()

        editor.replace(0, connection("a", username = "solr").copy(displayName = "Renamed"))
        editor.apply()

        assertEquals("SolrRocks", connectionSettings.getPassword("a"))
    }

    /** Clearing is a change, and reaches the store as one. */
    fun testAClearedPasswordIsForgotten() {
        connectionSettings.addConnection(connection("a", username = "solr"))
        connectionSettings.setPassword("a", "SolrRocks".toCharArray())
        val editor = model
        editor.reset()

        editor.setPassword("a", null)
        editor.apply()

        assertNull(connectionSettings.getPassword("a"))
    }

    /**
     * A typed secret counts as a change even where it matches what is stored.
     *
     * Comparing them would mean reading the stored secret back, which puts it somewhere it need not
     * be. Rewriting an identical password costs nothing; reading one to avoid that costs the property
     * this class is careful about.
     */
    fun testATypedPasswordCountsAsAChange() {
        connectionSettings.addConnection(connection("a", username = "solr"))
        connectionSettings.setPassword("a", "SolrRocks".toCharArray())
        val editor = model
        editor.reset()

        editor.setPassword("a", "SolrRocks".toCharArray())

        assertTrue(editor.isModified())
    }

    /** Removing a connection forgets a secret typed for it, rather than writing it on apply. */
    fun testRemovingAConnectionDropsASecretTypedForIt() {
        val editor = model
        editor.reset()
        editor.add(connection("a", username = "solr"))
        editor.setPassword("a", "SolrRocks".toCharArray())

        editor.remove(0)

        assertFalse("nothing remains to write", editor.isModified())
    }

    // --- what it discards -------------------------------------------------------------------------

    fun testResetDiscardsUnappliedEdits() {
        connectionSettings.addConnection(connection("a"))
        val editor = model
        editor.reset()
        editor.add(connection("b"))
        editor.setPassword("b", "secret".toCharArray())

        editor.reset()

        assertEquals(listOf("a"), editor.connections.map { it.id })
        assertFalse(editor.isModified())
    }
}
