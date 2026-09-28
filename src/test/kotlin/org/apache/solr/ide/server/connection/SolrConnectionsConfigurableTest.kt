package org.apache.solr.ide.server.connection

import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import org.apache.solr.ide.code.SolrEndpointCandidate
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/**
 * The settings page driven through the contract the platform actually calls.
 *
 * The model beside this is tested on its own; what is left here is the binding, and the binding is
 * where a settings page usually goes wrong — a `reset` that does not repopulate the list, an
 * `isModified` wired to the wrong thing, an `apply` that writes and leaves the page still claiming
 * to be dirty. None of that needs the page on screen to catch.
 */
class SolrConnectionsConfigurableTest : SolrConfigsetTestCase() {

    private fun configurable() = SolrConnectionsConfigurable(project)

    private fun connection(id: String) = SolrConnection(
        id = id,
        displayName = "Solr $id",
        baseUrl = "http://localhost:8983/solr",
    )

    fun testThePageIsNamed() {
        assertEquals("Solr Connections", configurable().displayName)
    }

    /**
     * The panel builds.
     *
     * Thin, but it is the assertion that catches a missing bundle key or a renderer that throws —
     * both of which surface to a user as a Settings dialog that opens onto a red exception box, and
     * neither of which any other test here would notice.
     */
    fun testThePanelBuilds() {
        val page = configurable()
        page.reset()

        assertNotNull(page.createComponent())
    }

    fun testAFreshPageIsNotModified() {
        connectionSettings.addConnection(connection("a"))
        val page = configurable()

        page.reset()

        assertFalse(page.isModified)
    }

    /**
     * Reset repopulates from what is saved, rather than only clearing what was typed.
     *
     * A page that resets its model but not its list shows the user rows that no longer exist, and
     * every later edit is then indexed against the wrong ones.
     */
    fun testResetRepopulatesFromSavedState() {
        connectionSettings.addConnection(connection("a"))
        val page = configurable()

        page.reset()
        val component = page.createComponent()

        assertNotNull(component)
        assertFalse("nothing was edited, so nothing is modified", page.isModified)
    }

    /** Applying a page with nothing changed leaves the saved connections alone. */
    fun testApplyingAnUneditedPageChangesNothing() {
        connectionSettings.addConnection(connection("a"))
        val page = configurable()
        page.reset()

        page.apply()

        assertEquals(listOf("a"), connectionSettings.connections.map { it.id })
    }

    /**
     * Applying an unedited page does not disturb a stored credential.
     *
     * The regression this whole increment turned on: `addConnection` once wrote the password
     * unconditionally, so opening Settings and pressing OK was enough to forget every secret.
     */
    fun testApplyingAnUneditedPageLeavesStoredPasswordsAlone() {
        connectionSettings.addConnection(connection("a").copy(username = "solr"), "s3cret".toCharArray())
        val page = configurable()
        page.reset()

        page.apply()

        assertEquals("s3cret", connectionSettings.getPassword("a"))
    }

    // --- add, edit and remove, past the point the dialog is dismissed ------------------------------

    fun testAnAddedConnectionShowsInTheListAndIsSaved() {
        val page = configurable()
        page.reset()

        page.record(null, connection("new"), passwordEdited = false, password = null)

        assertEquals(listOf("new"), page.rows.map { it.id })
        assertTrue(page.isModified)
        page.apply()
        assertEquals(listOf("new"), connectionSettings.connections.map { it.id })
    }

    fun testAnAddedConnectionsPasswordIsStored() {
        val page = configurable()
        page.reset()

        page.record(null, connection("new").copy(username = "solr"), passwordEdited = true, password = "s3cret".toCharArray())
        page.apply()

        assertEquals("s3cret", connectionSettings.getPassword("new"))
        assertEquals("the credential must be filed under the user it will be sent as", "solr", connectionSettings.getStoredUsername("new"))
    }

    /** Editing replaces the row in place rather than appending a second one for the same server. */
    fun testAnEditedConnectionReplacesItsRow() {
        connectionSettings.addConnection(connection("a"))
        val page = configurable()
        page.reset()

        page.record(0, connection("a").copy(displayName = "Renamed"), passwordEdited = false, password = null)

        assertEquals(1, page.rows.size)
        assertEquals("Renamed", page.rows.single().displayName)
        page.apply()
        assertEquals("Renamed", connectionSettings.connections.single().displayName)
    }

    /**
     * Editing a connection without touching its password leaves the credential in place.
     *
     * The end-to-end form of the defect this increment fixed, driven through the page rather than
     * the model: rename a server, press OK, and the password must still be there.
     */
    fun testRenamingAConnectionKeepsItsPassword() {
        connectionSettings.addConnection(connection("a").copy(username = "solr"), "s3cret".toCharArray())
        val page = configurable()
        page.reset()

        page.record(0, connection("a").copy(displayName = "Renamed", username = "solr"), passwordEdited = false, password = null)
        page.apply()

        assertEquals("s3cret", connectionSettings.getPassword("a"))
    }

    /** Forgetting a password is an edit like any other, and reaches the store as one. */
    fun testForgettingAPasswordClearsIt() {
        connectionSettings.addConnection(connection("a").copy(username = "solr"), "s3cret".toCharArray())
        val page = configurable()
        page.reset()

        page.record(0, connection("a").copy(username = "solr"), passwordEdited = true, password = null)
        page.apply()

        assertNull(connectionSettings.getPassword("a"))
    }

    fun testARemovedConnectionLeavesTheListAndTheSettings() {
        connectionSettings.addConnection(connection("a"))
        connectionSettings.addConnection(connection("b"))
        val page = configurable()
        page.reset()

        page.removeAt(0)

        assertEquals(listOf("b"), page.rows.map { it.id })
        page.apply()
        assertEquals(listOf("b"), connectionSettings.connections.map { it.id })
    }

    /** Cancelling out of Settings discards edits, list included. */
    fun testResetDiscardsAnUnappliedEdit() {
        connectionSettings.addConnection(connection("a"))
        val page = configurable()
        page.reset()
        page.record(null, connection("b"), passwordEdited = false, password = null)

        page.reset()

        assertEquals(listOf("a"), page.rows.map { it.id })
        assertFalse(page.isModified)
    }

    // --- servers the project names ------------------------------------------------------------------

    private fun candidate(
        profile: String? = "dev",
        url: String = "http://localhost:8983/solr",
        username: String? = "dev-user",
        password: String? = null,
    ) = SolrEndpointCandidate(url, username, password, profile, active = profile == "dev", origin = "Spring Boot configuration")

    /** Discovered servers are listed beneath the connections, the active profile's first. */
    fun testDiscoveredServersAreListed() {
        val page = configurable()
        page.reset()

        page.showDiscovered(listOf(candidate(), candidate(profile = "staging", url = "http://staging:8983/solr")))

        assertEquals(listOf("dev", "staging"), page.discoveredRows.map { it.profile })
    }

    /**
     * A server already configured is not offered again.
     *
     * Matched on URL and user, and a trailing slash does not make a second server: offering a row the
     * user has already added would read as though adding it had not worked.
     */
    fun testAServerAlreadyConfiguredIsNotOffered() {
        connectionSettings.addConnection(
            SolrConnection("a", "Local", "http://localhost:8983/solr/", username = "dev-user"),
        )
        val page = configurable()
        page.reset()

        page.showDiscovered(listOf(candidate(), candidate(profile = "staging", url = "http://staging:8983/solr")))

        assertEquals(listOf("staging"), page.discoveredRows.map { it.profile })
    }

    /** Adding a discovered server makes it a connection and takes it out of the discovered list. */
    fun testAddingADiscoveredServerMakesItAConnection() {
        val page = configurable()
        page.reset()
        page.showDiscovered(listOf(candidate()))

        page.adopt(candidate(), page.prefillFor(candidate()), storePassword = false)
        page.apply()

        assertEquals(listOf("http://localhost:8983/solr"), connectionSettings.connections.map { it.baseUrl })
        assertEquals("dev-user", connectionSettings.connections.single().username)
        assertEmpty(page.discoveredRows)
    }

    /** The profile names the new connection, since two profiles' servers are otherwise told apart only by URL. */
    fun testADiscoveredServerIsNamedForItsProfile() {
        assertEquals("Solr (dev)", configurable().prefillFor(candidate()).displayName)
    }

    /** A password found beside the URL is not stored unless the user ticked the box to store it. */
    fun testADiscoveredPasswordIsNotStoredWithoutConfirmation() {
        val page = configurable()
        page.reset()
        val found = candidate(password = "SolrRocks")

        page.adopt(found, page.prefillFor(found), storePassword = false)
        page.apply()

        assertNull(connectionSettings.getPassword(connectionSettings.connections.single().id))
    }

    /**
     * Confirmed, the password goes to the password safe and nowhere else.
     *
     * The plan's criterion in full: it reaches the password safe, and it never reaches the file the
     * connections themselves are written to.
     */
    fun testAConfirmedPasswordReachesThePasswordSafeAndNoFile() {
        val page = configurable()
        page.reset()
        val found = candidate(password = "SolrRocks")

        page.adopt(found, page.prefillFor(found), storePassword = true)
        page.apply()

        val id = connectionSettings.connections.single().id
        assertEquals("SolrRocks", connectionSettings.getPassword(id))
        val written = JDOMUtil.write(XmlSerializer.serialize(connectionSettings.state))
        assertFalse(written, "SolrRocks" in written)
    }

    /** The page builds with its discovered section, and lets go of it when Settings closes. */
    fun testThePanelBuildsWithItsDiscoveredSection() {
        val page = configurable()
        page.reset()
        assertNotNull(page.createComponent())
        page.disposeUIResources()
    }
}
