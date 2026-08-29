package org.apache.solr.ide.server.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Plain JUnit 4: validating a form field is a pure function, and booting an IDE to exercise one
 * costs a second of wall-clock for nothing.
 */
class SolrConnectionValidationTest {

    private fun problemIn(baseUrl: String, username: String = "", hasPassword: Boolean = false) =
        SolrConnectionValidation.problemIn(baseUrl, username, hasPassword)

    // --- what must be rejected --------------------------------------------------------------------

    @Test
    fun `a blank base url is rejected`() {
        assertEquals(SolrConnectionProblem.BASE_URL_MISSING, problemIn(""))
        assertEquals(SolrConnectionProblem.BASE_URL_MISSING, problemIn("   "))
    }

    @Test
    fun `a base url with no scheme is rejected`() {
        assertEquals(SolrConnectionProblem.BASE_URL_NOT_HTTP, problemIn("localhost:8983/solr"))
    }

    @Test
    fun `a non-http scheme is rejected`() {
        assertEquals(SolrConnectionProblem.BASE_URL_NOT_HTTP, problemIn("ftp://localhost:8983/solr"))
        assertEquals(SolrConnectionProblem.BASE_URL_NOT_HTTP, problemIn("file:///var/solr"))
    }

    @Test
    fun `a url with no host is rejected`() {
        assertEquals(SolrConnectionProblem.BASE_URL_NOT_HTTP, problemIn("http:///solr"))
    }

    @Test
    fun `a malformed url is rejected as malformed rather than crashing`() {
        assertEquals(SolrConnectionProblem.BASE_URL_MALFORMED, problemIn("http://loc alhost:8983/solr"))
    }

    /**
     * A password with nobody to send it as cannot authenticate anything.
     *
     * The mirror of the rule the spec states for the other direction — a username with no stored
     * password is a broken connection rather than one to send with an empty password — and it is
     * caught here, where the user can still fix it, rather than at the first request.
     */
    @Test
    fun `a password with no username is rejected`() {
        assertEquals(
            SolrConnectionProblem.PASSWORD_WITHOUT_USERNAME,
            problemIn("http://localhost:8983/solr", username = "", hasPassword = true),
        )
    }

    // --- what must be accepted, which is the half that gets a form wrong ---------------------------

    @Test
    fun `an ordinary local solr is accepted`() {
        assertNull(problemIn("http://localhost:8983/solr"))
    }

    @Test
    fun `https is accepted`() {
        assertNull(problemIn("https://solr.example.com:8983/solr"))
    }

    /**
     * No `/solr` suffix is required.
     *
     * A reverse proxy or a servlet context path can put Solr anywhere, and rejecting those would be
     * the form manufacturing a problem out of a deployment it has no business having an opinion on.
     */
    @Test
    fun `a solr behind a reverse proxy at another path is accepted`() {
        assertNull(problemIn("https://internal.example.com/search-backend"))
        assertNull(problemIn("http://localhost:8080"))
    }

    /** A trailing slash is how half the world writes a base URL, and means the same thing. */
    @Test
    fun `a trailing slash is accepted`() {
        assertNull(problemIn("http://localhost:8983/solr/"))
    }

    /** An IPv6 literal is a legal host and a real way to reach a container. */
    @Test
    fun `an ipv6 literal host is accepted`() {
        assertNull(problemIn("http://[::1]:8983/solr"))
    }

    /** Surrounding whitespace is a paste artefact, not a decision the user made. */
    @Test
    fun `surrounding whitespace does not make a good url bad`() {
        assertNull(problemIn("  http://localhost:8983/solr  "))
    }

    @Test
    fun `an unauthenticated connection is accepted`() {
        assertNull(problemIn("http://localhost:8983/solr", username = "", hasPassword = false))
    }

    /**
     * A username with no password is accepted *here* on purpose.
     *
     * It is a connection that will fail to authenticate, but the user may be part-way through typing
     * one, or may intend to supply the secret later. The spec's rule is that such a connection is
     * reported as broken when it is *used* — refusing to save it would be a form deciding that a
     * half-filled row cannot exist.
     */
    @Test
    fun `a username with no password is accepted at the form`() {
        assertNull(problemIn("http://localhost:8983/solr", username = "solr", hasPassword = false))
    }
}
