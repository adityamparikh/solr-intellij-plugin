package org.apache.solr.ide.server.connection

import com.intellij.configurationStore.deserializeInto
import com.intellij.configurationStore.serialize
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

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

    /**
     * A saved connection is in the state that gets written, not only in the one held in memory.
     *
     * **This is the assertion every other test here was standing next to and not making.** They read
     * `connectionSettings.connections` back, which answers from the live object and passes whether
     * or not a single byte would ever reach the workspace file. Serialization is a separate
     * question, and it had a separate answer: the list was skipped and `selectedConnectionId` beside
     * it was not, so the component was written on every save, looked entirely healthy, and carried
     * no connections. Restarting the IDE lost every server the user had configured, and the tool
     * window reported it as none ever having been configured.
     */
    fun testASavedConnectionSurvivesSerialization() {
        connectionSettings.addConnection(local)

        val written = serialize(connectionSettings.state)
        val reloaded = SolrConnectionSettings.State().also { checkNotNull(written).deserializeInto(it) }

        assertEquals(listOf(local.baseUrl), reloaded.connections.map { it.baseUrl })
        assertEquals(local.displayName, reloaded.connections.single().displayName)
    }

    /**
     * Adding a connection marks the state for writing.
     *
     * **In-memory correctness is not persistence, and every other test here checks only the first.**
     * A `BaseState` reaches the workspace file when its modification count moves. A property
     * assigned through `by string()` moves it plainly; a list mutated in place through `add` relies
     * on the collection tracking that itself, which is a property of the platform rather than of
     * this class. Were it ever not to hold, the connection list would work perfectly for a whole
     * session and be gone on the next start, leaving a `selectedConnectionId` naming a connection
     * that no longer exists -- so it is worth an assertion rather than an assumption.
     */
    fun testAddingAConnectionMarksTheStateForWriting() {
        val before = connectionSettings.state.modificationCount

        connectionSettings.addConnection(local)

        assertTrue(
            "the workspace file is only rewritten when the modification count moves",
            connectionSettings.state.modificationCount > before,
        )
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
     * Saving a connection again to change a non-secret field leaves its credential alone.
     *
     * The password argument once defaulted to null and was written unconditionally, which made "save
     * this connection" and "forget this connection's password" the same call. Every caller at the
     * time was a test that either passed a password or worked on a connection that had none, so
     * nothing could tell the two apart. The first caller that saves an *existing* connection — a
     * settings page writing an edited display name — is where it would have shown up, as every
     * credential the user did not retype silently disappearing.
     */
    fun testSavingAConnectionAgainLeavesItsPasswordAlone() {
        connectionSettings.addConnection(local, "s3cret".toCharArray())
        connectionSettings.addConnection(local.copy(displayName = "Renamed"))
        assertEquals("s3cret", connectionSettings.getPassword(local.id))
    }

    /**
     * Passing null explicitly still forgets it: the argument's *presence* is what decides whether
     * the secret is touched, and its value decides what to do with it.
     */
    fun testSavingWithAnExplicitNullPasswordForgetsTheStoredOne() {
        connectionSettings.addConnection(local, "s3cret".toCharArray())
        connectionSettings.addConnection(local, null)
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

    // --- which connection a surface uses ----------------------------------------------------------

    /**
     * With nothing chosen and one connection configured, that connection is the one.
     *
     * A user who has configured exactly one server has expressed a preference by doing so, and
     * making them also pick it from a list would be the plugin asking a question it can answer.
     */
    fun testTheOnlyConnectionIsSelectedWithoutBeingChosen() {
        connectionSettings.addConnection(local)

        assertEquals("local", connectionSettings.selectedConnection?.id)
    }

    fun testNoConnectionsMeansNoSelection() {
        assertNull(connectionSettings.selectedConnection)
    }

    fun testAChosenConnectionIsTheSelectedOne() {
        connectionSettings.addConnection(local)
        connectionSettings.addConnection(local.copy(id = "other", baseUrl = "http://elsewhere:8983/solr"))

        connectionSettings.selectedConnectionId = "other"

        assertEquals("other", connectionSettings.selectedConnection?.id)
    }

    /**
     * A selection naming a connection that no longer exists falls back rather than resolving to
     * nothing.
     *
     * The workspace file outlives any particular connection list — it is hand-editable, and a
     * connection can be removed from a second IDE window. Resolving to nothing there would show an
     * empty tool window on a project that has servers configured, which reads as broken.
     */
    fun testASelectionNamingNothingFallsBackToTheFirstConnection() {
        connectionSettings.addConnection(local)
        connectionSettings.selectedConnectionId = "deleted-long-ago"

        assertEquals("local", connectionSettings.selectedConnection?.id)
    }

    /** Removing the selected connection drops the selection with it. */
    fun testRemovingTheSelectedConnectionClearsTheSelection() {
        connectionSettings.addConnection(local)
        connectionSettings.selectedConnectionId = "local"

        connectionSettings.removeConnection("local")

        assertNull(connectionSettings.selectedConnectionId)
        assertNull(connectionSettings.selectedConnection)
    }

    /** Removing a connection that was not selected leaves the selection alone. */
    fun testRemovingAnotherConnectionKeepsTheSelection() {
        connectionSettings.addConnection(local)
        connectionSettings.addConnection(local.copy(id = "other"))
        connectionSettings.selectedConnectionId = "local"

        connectionSettings.removeConnection("other")

        assertEquals("local", connectionSettings.selectedConnectionId)
    }
}
