package org.apache.solr.ide.server.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a connection's credential can be, including the state that is neither present nor absent.
 *
 * The specification requires a connection naming a user with no stored password to be reported
 * distinctly rather than sent as an empty-string password — most Solr Basic Auth configurations
 * reject an empty password as a *wrong* credential rather than *no* credential, which turns a
 * configuration mistake into a spurious authentication failure. Two nullable strings cannot express
 * that difference, which is why this type exists.
 */
class SolrCredentialTest {

    @Test
    fun `a connection with no user needs no header`() {
        assertNull(SolrCredential.None.authorizationHeader())
    }

    @Test
    fun `a resolved credential is Basic and base64 encoded`() {
        val header = SolrCredential.Resolved("solr", "SolrRocks").authorizationHeader()

        assertEquals("Basic c29scjpTb2xyUm9ja3M=", header)
    }

    /** The state two nullable strings collapse into "anonymous", which is the defect. */
    @Test
    fun `a user with no stored password is missing rather than anonymous`() {
        val credential = SolrCredential.Missing("solr")

        assertNull("a missing credential must not become a header", credential.authorizationHeader())
        assertEquals("solr", credential.username)
    }

    /**
     * A credential never renders itself.
     *
     * `data class` would synthesise a `toString` printing the password, and a result type, a log
     * line or an exception message that happens to interpolate a credential is exactly how a secret
     * escapes. The specification requires one to exist only in `PasswordSafe` and the in-flight
     * request.
     */
    @Test
    fun `a resolved credential does not print its password`() {
        val rendered = SolrCredential.Resolved("solr", "SolrRocks").toString()

        assertFalse("the password must not appear in toString, got: $rendered", rendered.contains("SolrRocks"))
    }

    /** Nor its username, since the pair is what identifies the account. */
    @Test
    fun `a missing credential does not print as its username`() {
        assertFalse(SolrCredential.Missing("solr").toString().contains("solr"))
    }

    // --- reading one off a connection ---------------------------------------------------------------

    @Test
    fun `a connection with no username resolves to none`() {
        assertEquals(SolrCredential.None, SolrCredential.of(username = null, password = null))
        assertEquals(SolrCredential.None, SolrCredential.of(username = "", password = null))
    }

    @Test
    fun `a connection with a username and a password resolves`() {
        val credential = SolrCredential.of(username = "solr", password = "SolrRocks")

        assertEquals(SolrCredential.Resolved("solr", "SolrRocks"), credential)
    }

    /** The case the type exists for: a username, and nothing in PasswordSafe behind it. */
    @Test
    fun `a username with no password resolves to missing`() {
        assertEquals(SolrCredential.Missing("solr"), SolrCredential.of(username = "solr", password = null))
    }

    /**
     * An empty stored password is missing too.
     *
     * PasswordSafe returns an empty string for some cleared entries rather than null, and sending
     * `user:` is the same wrong credential the requirement forbids.
     */
    @Test
    fun `an empty stored password is also missing`() {
        assertEquals(SolrCredential.Missing("solr"), SolrCredential.of(username = "solr", password = ""))
    }
}
