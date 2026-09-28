package org.apache.solr.ide.server.connection

import com.intellij.openapi.ui.ValidationInfo
import org.apache.solr.ide.SolrBundle
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/**
 * The connection form, driven without showing it.
 *
 * A [com.intellij.openapi.ui.DialogWrapper] builds and validates perfectly well headlessly — only
 * `show` needs a screen — so the form's behaviour is ordinary test material rather than something
 * that has to be taken on trust until someone opens Settings.
 *
 * What the tests cannot reach is typing: the fields are private, and the values they start with are
 * what the dialog was constructed with. That is enough for every branch that matters, because the
 * interesting states — a bad URL, a stored secret, a secret with no user to send it as — are all
 * states a dialog can be *opened* in.
 */
class SolrConnectionDialogTest : SolrConfigsetTestCase() {

    private fun <T> withDialog(
        initial: SolrConnection?,
        hasStoredPassword: Boolean = false,
        body: (SolrConnectionDialog) -> T,
    ): T {
        val dialog = SolrConnectionDialog(project, initial, hasStoredPassword)
        return try {
            body(dialog)
        } finally {
            dialog.close(0)
        }
    }

    private fun connection(
        baseUrl: String = "http://localhost:8983/solr",
        displayName: String = "Local Solr",
        username: String? = null,
    ) = SolrConnection(id = "a", displayName = displayName, baseUrl = baseUrl, username = username)

    private fun problemOn(dialog: SolrConnectionDialog): ValidationInfo? =
        dialog.performValidateAll().firstOrNull()

    // --- what it reads back -----------------------------------------------------------------------

    fun testAnEditedConnectionKeepsItsIdentifier() {
        val kept = withDialog(connection()) { it.connection }

        assertEquals("the id keys the stored secret, so changing it would orphan the credential", "a", kept.id)
    }

    fun testANewConnectionIsGivenAnIdentifier() {
        val fresh = withDialog(initial = null) { it.connection }

        assertTrue("a new connection needs an id to file its secret under", fresh.id.isNotEmpty())
    }

    fun testTwoNewConnectionsDoNotShareAnIdentifier() {
        val first = withDialog(initial = null) { it.connection }
        val second = withDialog(initial = null) { it.connection }

        assertFalse("two connections sharing an id would share a credential", first.id == second.id)
    }

    /** A row with no label reads as empty rather than as itself, so the URL stands in. */
    fun testABlankNameFallsBackToTheUrl() {
        val saved = withDialog(connection(displayName = "")) { it.connection }

        assertEquals("http://localhost:8983/solr", saved.displayName)
    }

    /** A blank username is no username, not a username that happens to be empty. */
    fun testABlankUsernameIsNoUsername() {
        val saved = withDialog(connection(username = "")) { it.connection }

        assertNull(saved.username)
    }

    fun testAUsernameIsCarriedThrough() {
        val saved = withDialog(connection(username = "solr")) { it.connection }

        assertEquals("solr", saved.username)
    }

    // --- what it refuses to save ------------------------------------------------------------------

    fun testAGoodConnectionValidates() {
        assertNull(withDialog(connection()) { problemOn(it) })
    }

    fun testABlankUrlIsReported() {
        val problem = withDialog(connection(baseUrl = "")) { problemOn(it) }

        assertEquals(SolrBundle.message("connection.problem.urlMissing"), problem?.message)
    }

    fun testAUrlWithNoSchemeIsReported() {
        val problem = withDialog(connection(baseUrl = "localhost:8983/solr")) { problemOn(it) }

        assertEquals(SolrBundle.message("connection.problem.urlNotHttp"), problem?.message)
    }

    fun testAMalformedUrlIsReported() {
        val problem = withDialog(connection(baseUrl = "http://loc alhost:8983/solr")) { problemOn(it) }

        assertEquals(SolrBundle.message("connection.problem.urlMalformed"), problem?.message)
    }

    /**
     * A stored secret with the username cleared out from under it is caught.
     *
     * The same mistake as typing a password with no user, reached the other way round — and the one
     * the dialog can only see because it is told *whether* a secret is stored.
     */
    fun testAStoredPasswordWithNoUsernameIsReported() {
        val problem = withDialog(connection(username = null), hasStoredPassword = true) { problemOn(it) }

        assertEquals(SolrBundle.message("connection.problem.passwordWithoutUsername"), problem?.message)
    }

    fun testAStoredPasswordWithAUsernameIsFine() {
        val problem = withDialog(connection(username = "solr"), hasStoredPassword = true) { problemOn(it) }

        assertNull(problem)
    }

    // --- what it does to the secret ---------------------------------------------------------------

    /**
     * Opening the form and changing nothing leaves the stored secret alone.
     *
     * The property the empty password field exists to provide: a user editing a display name has not
     * asked to touch a credential, and an empty field must not be read as "no password".
     */
    fun testAnUntouchedFormDoesNotRewriteTheSecret() {
        val edited = withDialog(connection(username = "solr"), hasStoredPassword = true) { it.passwordEdited }

        assertFalse(edited)
    }

    fun testAnUntouchedFormOnAConnectionWithNoSecretRewritesNothingEither() {
        val edited = withDialog(connection()) { it.passwordEdited }

        assertFalse(edited)
    }

    // --- opened from a server the project names ---------------------------------------------------

    private fun <T> withDiscoveredDialog(passwordSource: String?, body: (SolrConnectionDialog) -> T): T {
        val dialog = SolrConnectionDialog(
            project,
            initial = null,
            hasStoredPassword = false,
            prefill = connection(username = "dev-user"),
            discoveredPasswordSource = passwordSource,
        )
        return try {
            body(dialog)
        } finally {
            dialog.close(0)
        }
    }

    /** A form prefilled from a discovered server is still an add: its own identifier, the add title. */
    fun testAPrefilledFormIsAnAddNotAnEdit() {
        withDiscoveredDialog(passwordSource = null) { dialog ->
            assertEquals(SolrBundle.message("connection.dialog.addTitle"), dialog.title)
            assertNotSame("a", dialog.connection.id)
            assertEquals("http://localhost:8983/solr", dialog.connection.baseUrl)
            assertEquals("dev-user", dialog.connection.username)
        }
    }

    /**
     * A password found in the project's configuration is not stored unless the user says so.
     *
     * The plan's rule: a secret from a configuration file reaches the password safe only after the
     * user confirms. Pressing *Add* opens the form; ticking the box is the confirmation.
     */
    fun testADiscoveredPasswordIsNotStoredUnlessAsked() {
        withDiscoveredDialog(passwordSource = "Spring Boot configuration (dev)") { dialog ->
            assertFalse(dialog.storeDiscoveredPassword)
            assertFalse(dialog.passwordEdited)
        }
    }

    /** Asking for it counts as a password for validation, so it cannot be stored with no user. */
    fun testStoringADiscoveredPasswordNeedsAUsername() {
        val dialog = SolrConnectionDialog(
            project,
            initial = null,
            hasStoredPassword = false,
            prefill = connection(username = null),
            discoveredPasswordSource = "Spring Boot configuration",
        )
        try {
            dialog.storeDiscoveredPassword = true
            assertEquals(
                SolrBundle.message("connection.problem.passwordWithoutUsername"),
                problemOn(dialog)?.message,
            )
        } finally {
            dialog.close(0)
        }
    }
}
