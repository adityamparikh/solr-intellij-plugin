package org.apache.solr.ide.server

import org.apache.solr.ide.configset.SolrConfigsetTestCase

class SolrConnectionSettingsTest : SolrConfigsetTestCase() {

    private val local = SolrConnection(
        id = "local",
        displayName = "Local Solr",
        baseUrl = "http://localhost:8983/solr",
        username = "solr",
    )

    fun testAddedConnectionIsListed() {
        connectionSettings.addConnection(local)
        assertEquals(listOf(local), connectionSettings.connections)
    }

    fun testAddingTheSameIdReplacesRatherThanDuplicates() {
        connectionSettings.addConnection(local)
        connectionSettings.addConnection(local.copy(baseUrl = "http://localhost:9983/solr"))

        assertEquals(1, connectionSettings.connections.size)
        assertEquals("http://localhost:9983/solr", connectionSettings.connections.single().baseUrl)
    }

    fun testRemovingAConnectionDropsIt() {
        connectionSettings.addConnection(local)
        connectionSettings.removeConnection(local.id)
        assertTrue(connectionSettings.connections.isEmpty())
    }

    fun testRemovingAnUnknownIdIsIgnored() {
        connectionSettings.addConnection(local)
        connectionSettings.removeConnection("no-such-connection")
        assertEquals(1, connectionSettings.connections.size)
    }

    fun testPasswordRoundTripsThroughPasswordSafe() {
        connectionSettings.addConnection(local, "s3cret".toCharArray())
        assertEquals("s3cret", connectionSettings.getPassword(local.id))
    }

    /**
     * The credential is filed under the connection's real username, not its id. Getting this wrong
     * is invisible until the first authenticated request, which would then send an identifier no
     * Solr has ever heard of.
     */
    fun testCredentialIsFiledUnderTheRealUsername() {
        connectionSettings.addConnection(local, "s3cret".toCharArray())
        assertEquals("solr", connectionSettings.getStoredUsername(local.id))
    }

    fun testConnectionWithNoUsernameStoresNone() {
        val anonymous = local.copy(id = "anon", username = null)
        connectionSettings.addConnection(anonymous, "s3cret".toCharArray())
        assertNull(connectionSettings.getStoredUsername(anonymous.id))
        assertEquals("s3cret", connectionSettings.getPassword(anonymous.id))
    }

    /**
     * The point of the whole design: the secret must not be reachable from the serialized state,
     * because that state is what gets written to the workspace file.
     */
    fun testPasswordIsNotHeldInThePersistedState() {
        connectionSettings.addConnection(local, "s3cret".toCharArray())
        val serialized = connectionSettings.state.connections.single()
        assertFalse(listOfNotNull(serialized.id, serialized.displayName, serialized.baseUrl, serialized.username)
            .any { it.contains("s3cret") })
    }

    /** A deleted connection must not leave an orphaned credential behind under its old key. */
    fun testRemovingAConnectionForgetsItsPassword() {
        connectionSettings.addConnection(local, "s3cret".toCharArray())
        connectionSettings.removeConnection(local.id)
        assertNull(connectionSettings.getPassword(local.id))
    }

    fun testAConnectionWithNoPasswordHasNone() {
        connectionSettings.addConnection(local)
        assertNull(connectionSettings.getPassword(local.id))
    }

    /**
     * Connections and configset roots are separate surfaces with different storage. Adding one must
     * not touch the other — a connection leaking into the shared `solr.xml` is the failure this
     * split exists to prevent.
     */
    fun testConnectionsDoNotTouchTheSharedConfigsetSettings() {
        connectionSettings.addConnection(local)
        assertTrue(settings.manualRoots.isEmpty())
    }
}
