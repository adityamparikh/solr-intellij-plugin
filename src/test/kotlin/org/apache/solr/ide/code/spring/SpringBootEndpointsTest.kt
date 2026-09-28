package org.apache.solr.ide.code.spring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Resolving a Spring Boot application's configuration into the Solr servers it would talk to.
 *
 * Plain JUnit, because nothing here reads a file: the sources arrive already parsed, which is what
 * lets every precedence rule be stated in a line and checked without booting an IDE.
 */
class SpringBootEndpointsTest {

    private fun source(profile: String?, vararg properties: Pair<String, String>) =
        SpringPropertySource(profile, properties.toMap(), "application.yml")

    /** The demo's shape: nothing in the default document, a URL and a user per profile. */
    private val demo = listOf(
        source(null, "spring.profiles.active" to "dev"),
        source("dev", "app.solr.url" to "http://localhost:8983/solr", "app.solr.username" to "dev-user"),
        source(
            "staging",
            "app.solr.url" to "http://solr-staging.internal:8983/solr",
            "app.solr.username" to "staging-user",
        ),
    )

    private val followsAppSolrUrl = listOf(SpelledEndpoint("\${app.solr.url}", null))

    // --- one row per profile --------------------------------------------------------------------------

    @Test
    fun `each profile offers its own URL and the username beside it`() {
        val found = SpringBootEndpoints.candidates(demo, followsAppSolrUrl, activeProfiles = null)

        assertEquals(
            listOf(
                Triple("dev", "http://localhost:8983/solr", "dev-user"),
                Triple("staging", "http://solr-staging.internal:8983/solr", "staging-user"),
            ),
            found.map { Triple(it.profile, it.url, it.username) },
        )
    }

    @Test
    fun `the profile the configuration activates is marked and offered first`() {
        val found = SpringBootEndpoints.candidates(demo, followsAppSolrUrl, activeProfiles = null)

        assertEquals("dev", found.first().profile)
        assertTrue(found.first().active)
        assertEquals(listOf(true, false), found.map { it.active })
    }

    /**
     * Switching the active profile changes the offered username as well as the URL.
     *
     * The plan's success criterion, stated on the demo fixture: a credential that stayed put while the
     * URL moved would connect to staging as the dev user.
     */
    @Test
    fun `switching the active profile changes the offered URL and username together`() {
        val offered = SpringBootEndpoints.candidates(demo, followsAppSolrUrl, activeProfiles = setOf("staging")).first()

        assertEquals("staging", offered.profile)
        assertEquals("http://solr-staging.internal:8983/solr", offered.url)
        assertEquals("staging-user", offered.username)
    }

    /** A profile that changes nothing about Solr is the default's row, not a second copy of it. */
    @Test
    fun `a profile that overrides nothing Solr-related does not repeat the default`() {
        val sources = listOf(
            source(null, "app.solr.url" to "http://localhost:8983/solr"),
            source("test", "logging.level.root" to "debug"),
        )

        val found = SpringBootEndpoints.candidates(sources, followsAppSolrUrl, activeProfiles = null)

        assertEquals(listOf<String?>(null), found.map { it.profile })
    }

    // --- what is followed ---------------------------------------------------------------------------

    /**
     * A key the code reaches is followed even when nothing about its name says Solr.
     *
     * The plan's criterion that the URL is found by following the client bean's property reference,
     * rather than by guessing from key names — so the key here is deliberately not a guessable one.
     */
    @Test
    fun `a key the client bean follows is resolved whatever it is called`() {
        val sources = listOf(source(null, "search.endpoint" to "http://search.internal:8983/solr"))

        val found = SpringBootEndpoints.candidates(sources, listOf(SpelledEndpoint("\${search.endpoint}", null)), null)

        assertEquals(listOf("http://search.internal:8983/solr"), found.map { it.url })
    }

    @Test
    fun `a key naming Solr with a URL value is offered even when no code follows it`() {
        val sources = listOf(source(null, "spring.data.solr.host" to "http://localhost:8983/solr"))

        val found = SpringBootEndpoints.candidates(sources, emptyList(), null)

        assertEquals(listOf("http://localhost:8983/solr"), found.map { it.url })
    }

    @Test
    fun `a key naming Solr whose value is not a URL is not offered`() {
        val sources = listOf(source(null, "app.solr.collection" to "books"))

        assertTrue(SpringBootEndpoints.candidates(sources, emptyList(), null).isEmpty())
    }

    @Test
    fun `a reference's default is used where no source sets the key`() {
        val found = SpringBootEndpoints.candidates(
            listOf(source(null)),
            listOf(SpelledEndpoint("\${app.solr.url:http://localhost:8983/solr}", null)),
            null,
        )

        assertEquals(listOf("http://localhost:8983/solr"), found.map { it.url })
    }

    @Test
    fun `a value built from another property is resolved through it`() {
        val sources = listOf(
            source(null, "solr.host" to "http://solr.internal:8983", "app.solr.url" to "\${solr.host}/solr"),
        )

        val found = SpringBootEndpoints.candidates(sources, followsAppSolrUrl, null)

        assertEquals(listOf("http://solr.internal:8983/solr"), found.map { it.url })
    }

    /**
     * A URL that cannot be resolved offers nothing, rather than a URL with a hole in it.
     *
     * `${SOLR_URL}` is an environment variable the IDE cannot see. Offering the reference, or the part
     * around it, would be offering a server that does not exist.
     */
    @Test
    fun `a URL left unresolved offers nothing`() {
        val sources = listOf(source(null, "app.solr.url" to "\${SOLR_URL}"))

        assertTrue(SpringBootEndpoints.candidates(sources, followsAppSolrUrl, null).isEmpty())
    }

    /** A username the code passes by reference resolves per profile, like the URL. */
    @Test
    fun `a username the code follows resolves per profile`() {
        val sources = listOf(
            source(null, "app.solr.url" to "http://localhost:8983/solr"),
            source("dev", "search.user" to "alice"),
        )
        val found = SpringBootEndpoints.candidates(
            sources,
            listOf(SpelledEndpoint("\${app.solr.url}", "\${search.user}")),
            activeProfiles = setOf("dev"),
        )

        assertEquals("alice", found.single { it.profile == "dev" }.username)
    }

    // --- the secret ---------------------------------------------------------------------------------

    @Test
    fun `a password written beside the URL travels with the candidate`() {
        val sources = listOf(
            source(
                null,
                "app.solr.url" to "http://localhost:8983/solr",
                "app.solr.username" to "solr",
                "app.solr.password" to "SolrRocks",
            ),
        )

        assertEquals("SolrRocks", SpringBootEndpoints.candidates(sources, followsAppSolrUrl, null).single().password)
    }

    /** A password held somewhere the IDE cannot see is left for the user to type, not guessed. */
    @Test
    fun `a password that is a reference to the environment is not carried`() {
        val sources = listOf(
            source(
                null,
                "app.solr.url" to "http://localhost:8983/solr",
                "app.solr.username" to "solr",
                "app.solr.password" to "\${SOLR_PASSWORD}",
            ),
        )

        assertNull(SpringBootEndpoints.candidates(sources, followsAppSolrUrl, null).single().password)
    }

    // --- what is not Spring -------------------------------------------------------------------------

    /**
     * Quarkus's inline `%dev.` prefix is not read as a key.
     *
     * The plan holds this step to shipping no Quarkus resolution. A `%dev.app.solr.url` read as a
     * plain key would be exactly that, done wrongly: offered with no profile and whatever the active
     * one is.
     */
    @Test
    fun `a Quarkus profile-prefixed key is not read`() {
        val sources = listOf(source(null, "%dev.app.solr.url" to "http://localhost:8983/solr"))

        assertTrue(SpringBootEndpoints.candidates(sources, emptyList(), null).isEmpty())
    }
}
