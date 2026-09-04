package org.apache.solr.ide.server.reading

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase
import org.apache.solr.ide.server.connection.SolrConnection
import org.apache.solr.ide.model.SolrConfigsetFacts
import org.apache.solr.ide.model.SolrFieldModel
import org.apache.solr.ide.model.SolrVersionSelection
import org.apache.solr.ide.model.SolrVersionSource
import org.apache.solr.ide.server.transport.SolrResponse

/**
 * Reading a server the way a connection describes it.
 *
 * The layer the four steps above this one all reach for: given a connection and a collection, the
 * facts that collection reports and the version the server runs. Everything under it — transport,
 * schema reader, credential — exists already and is tested on its own; this is where they compose,
 * and where a connection's stored secret is resolved.
 *
 * The server is an embedded one rather than a container. What is under test is composition and
 * credential handling, not Solr's wire format, and the contract tests own that.
 */
class SolrServerReaderTest : SolrConfigsetTestCase() {

    private var server: HttpServer? = null
    private val requested = mutableListOf<String>()
    private var authorization: String? = null

    private val schemaBody = """
        {"responseHeader":{"status":0},
         "schema":{"uniqueKey":"id","fields":[{"name":"id","type":"string","indexed":true}],
                   "fieldTypes":[{"name":"string","class":"solr.StrField"}]}}
    """.trimIndent()

    private val systemBody = systemBodyFor("solrcloud")

    private fun systemBodyNaming(version: String) = """
        {"responseHeader":{"status":0},"mode":"solrcloud",
         "lucene":{"solr-spec-version":"$version","lucene-spec-version":"$version"}}
    """.trimIndent()

    private fun systemBodyFor(mode: String) = """
        {"responseHeader":{"status":0},"mode":"$mode",
         "lucene":{"solr-spec-version":"10.0.0","lucene-spec-version":"10.3.2"}}
    """.trimIndent()

    private val clusterStatusBody = """
        {"responseHeader":{"status":0},
         "cluster":{"collections":{"books":{"configName":"books_config","health":"GREEN",
           "shards":{"shard1":{"range":"80000000-7fffffff","state":"active","health":"GREEN",
             "replicas":{"core_node2":{"core":"books_shard1_replica_n1",
               "node_name":"127.0.0.1:8983_solr","state":"active","type":"NRT","leader":"true"}}}}}},
          "live_nodes":["127.0.0.1:8983_solr"]}}
    """.trimIndent()

    private val coresStatusBody = """
        {"responseHeader":{"status":0},"status":{"books":{"name":"books","configSet":"_default"}}}
    """.trimIndent()

    /**
     * A server answering the endpoints the reader asks for, and recording what it was asked.
     *
     * What it was asked matters as much as what it answered: the reader's contract is that it picks
     * the endpoint from the mode rather than trying one and catching the refusal, and the only way to
     * see that is to notice the request that was never made.
     */
    private fun givenServer(
        schema: (HttpExchange) -> Unit = { respond(it, 200, schemaBody) },
        systemInfo: (HttpExchange) -> Unit = { respond(it, 200, systemBody) },
        collections: (HttpExchange) -> Unit = { respond(it, 200, clusterStatusBody) },
        cores: (HttpExchange) -> Unit = { respond(it, 200, coresStatusBody) },
    ): String {
        val started = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        started.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            requested += path
            authorization = authorization ?: exchange.requestHeaders.getFirst("Authorization")
            when {
                path.endsWith("/schema") -> schema(exchange)
                path.endsWith("/admin/collections") -> collections(exchange)
                path.endsWith("/admin/cores") -> cores(exchange)
                else -> systemInfo(exchange)
            }
        }
        started.start()
        server = started
        return "http://127.0.0.1:${started.address.port}"
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    override fun tearDown() {
        try {
            server?.stop(0)
        } finally {
            super.tearDown()
        }
    }

    private fun read(connection: SolrConnection, collection: String = "books") = runBlocking {
        SolrServerReader.getInstance(project).read(connection, collection)
    }

    private fun topology(connection: SolrConnection) = runBlocking {
        SolrServerReader.getInstance(project).topology(connection)
    }

    private fun connection(baseUrl: String, username: String? = null) =
        SolrConnection(id = "c1", displayName = "local", baseUrl = baseUrl, username = username)

    // --- what it composes -------------------------------------------------------------------------

    fun testAServerReadCarriesItsFactsAndItsVersion() {
        val result = read(connection(givenServer()))

        assertTrue(result.toString(), result is SolrResponse.Success)
        val read = (result as SolrResponse.Success).value
        assertEquals(listOf("id"), read.facts.fields.map { it.name })
        assertEquals("10.0.0", read.solrVersion)
    }

    /** The collection is what the caller named, not something the reader works out. */
    fun testTheCollectionNamedIsTheCollectionAsked() {
        read(connection(givenServer()), collection = "products")

        assertTrue("asked for: $requested", requested.any { it == "/products/schema" })
    }

    /**
     * The version is best-effort, and a schema that arrived is not discarded because it did not.
     *
     * Two requests, and only one of them is the point. A server that answers its schema and refuses
     * its system info still told the caller everything the drift view needs; reporting a failure
     * there would trade a complete answer for an incomplete objection.
     */
    fun testAVersionThatCannotBeReadLeavesTheFactsIntact() {
        val result = read(connection(givenServer(systemInfo = { respond(it, 500, """{"error":{"msg":"nope"}}""") })))

        assertTrue(result.toString(), result is SolrResponse.Success)
        val read = (result as SolrResponse.Success).value
        assertEquals(listOf("id"), read.facts.fields.map { it.name })
        assertNull("no version was readable, and none must be invented", read.solrVersion)
    }

    /** A schema that cannot be read is a failure, since it is what the caller asked for. */
    fun testASchemaThatCannotBeReadIsAFailure() {
        val result = read(connection(givenServer(schema = { respond(it, 404, "<html>no</html>") })))

        assertTrue(result.toString(), result is SolrResponse.SolrError)
        assertEquals(404, (result as SolrResponse.SolrError).code)
    }

    // --- which endpoint the mode chooses ----------------------------------------------------------

    /**
     * A SolrCloud server is asked for its cluster status, and never for its cores.
     *
     * The negative half is the point. Both endpoints would answer *something* on a cloud server, so a
     * test that only checked the collections came back would pass just as well for a reader that
     * asked both and merged them.
     */
    fun testACloudServerIsAskedForItsCollections() {
        val result = topology(connection(givenServer()))

        assertTrue(result.toString(), result is SolrResponse.Success)
        val found = (result as SolrResponse.Success).value
        assertEquals(SolrServerMode.SOLR_CLOUD, found.mode)
        assertEquals(listOf("books"), found.collections.map { it.name })
        assertEquals(listOf("127.0.0.1:8983_solr"), found.liveNodes)
        assertTrue("asked for: $requested", requested.any { it.endsWith("/admin/collections") })
        assertFalse("a cloud server must not be asked for cores: $requested", requested.any { it.endsWith("/admin/cores") })
    }

    /**
     * A standalone server is asked for its cores, and never for its collections.
     *
     * The case the mode check exists for: a standalone Solr answers every `/admin/collections` action
     * with HTTP 400, so a reader that asked anyway would report a hard failure against a server that
     * is working perfectly.
     */
    fun testAStandaloneServerIsAskedForItsCores() {
        val result = topology(connection(givenServer(systemInfo = { respond(it, 200, systemBodyFor("std")) })))

        assertTrue(result.toString(), result is SolrResponse.Success)
        val found = (result as SolrResponse.Success).value
        assertEquals(SolrServerMode.STANDALONE, found.mode)
        assertEquals(listOf("books"), found.cores.map { it.name })
        assertTrue("asked for: $requested", requested.any { it.endsWith("/admin/cores") })
        assertFalse(
            "a standalone server must not be asked for collections: $requested",
            requested.any { it.endsWith("/admin/collections") },
        )
    }

    /**
     * A server that will not say which mode it is in is asked for neither.
     *
     * Reported as a successful read of an unknown server rather than as a failure: nothing went
     * wrong, and there is simply nothing that can be said about what it holds. Guessing either
     * vocabulary would produce a list the caller cannot interpret.
     */
    fun testAServerThatNamesNoModeIsAskedForNeitherVocabulary() {
        val result = topology(connection(givenServer(systemInfo = { respond(it, 200, """{"responseHeader":{"status":0}}""") })))

        assertTrue(result.toString(), result is SolrResponse.Success)
        assertEquals(SolrServerMode.UNKNOWN, (result as SolrResponse.Success).value.mode)
        assertFalse("neither endpoint may be asked: $requested", requested.any { it.contains("/admin/collections") })
        assertFalse("neither endpoint may be asked: $requested", requested.any { it.contains("/admin/cores") })
    }

    /** A system-info call that fails is the answer, and stops the reader asking anything further. */
    fun testAFailureReadingTheModeIsReportedRatherThanGuessedAround() {
        val result = topology(
            connection(givenServer(systemInfo = { respond(it, 500, """{"error":{"msg":"nope"}}""") })),
        )

        assertTrue(result.toString(), result is SolrResponse.SolrError)
        assertEquals(500, (result as SolrResponse.SolrError).code)
        assertEquals("only the system-info call should have been made, got: $requested", 1, requested.size)
    }

    // --- the credential, resolved here and nowhere else --------------------------------------------

    /** A connection naming no user sends no header. */
    fun testAnAnonymousConnectionSendsNoCredential() {
        read(connection(givenServer()))

        assertNull(authorization)
    }

    /**
     * A stored password is resolved at the point of use and sent on the first request.
     *
     * The credential is read from `PasswordSafe` here rather than carried on the connection, which is
     * what keeps it out of the persisted state and out of every caller above this one.
     */
    fun testAStoredPasswordIsResolvedAndSent() {
        val url = givenServer()
        connectionSettings.addConnection(connection(url, username = "solr"))
        connectionSettings.setPassword("c1", "SolrRocks".toCharArray())

        read(connection(url, username = "solr"))

        assertTrue("expected a Basic header, got $authorization", authorization?.startsWith("Basic ") == true)
    }

    /**
     * An edited username is what authenticates, even while the old one is still filed beside the
     * secret.
     *
     * **Editing a username and editing a password are separate gestures, and the dialog makes them
     * so.** Its password field opens empty on a connection that has a secret, so changing only the
     * username leaves `passwordEdited` false and `setPassword` uncalled — `PasswordSafe` keeps the
     * pair it was given, under the user it was given with. The connection is the half the user just
     * edited; the stored username is the half nobody touched.
     *
     * Preferring the stored one makes the username field a no-op for as long as a secret exists: the
     * row shows the new user, and every request authenticates as the old one, with nothing on screen
     * saying so. A connection naming a user the secret was not filed under should fail against the
     * server, visibly, rather than succeed as somebody else.
     */
    fun testAnEditedUsernameIsSentRatherThanTheOneFiledWithTheSecret() {
        val url = givenServer()
        connectionSettings.addConnection(connection(url, username = "solr"), "SolrRocks".toCharArray())

        // What the dialog does when only the display name or username changed: the connection is
        // re-saved and the secret is left exactly as it was.
        connectionSettings.addConnection(connection(url, username = "admin"))

        read(connection(url, username = "admin"))

        assertEquals("admin:SolrRocks", decodedAuthorization())
    }

    private fun decodedAuthorization(): String {
        val header = authorization ?: fail("no Authorization header was sent")
        return String(java.util.Base64.getDecoder().decode(header.toString().removePrefix("Basic ")))
    }

    /**
     * A connection naming a user with nothing stored for it is reported, and nothing is sent.
     *
     * The case the specification singles out: sending an empty password would be rejected by most
     * Solr Basic Auth configurations as a *wrong* credential rather than *no* credential, turning a
     * cleared entry into a spurious authentication failure. The failure names the user so the
     * incomplete connection is identifiable.
     */
    fun testAConnectionWithNoStoredPasswordIsReportedRatherThanSent() {
        val result = read(connection(givenServer(), username = "solr"))

        assertEmpty(requested)
        assertTrue(result.toString(), result is SolrResponse.TransportFailure)
        assertTrue(
            "the failure should name the user, got: ${(result as SolrResponse.TransportFailure).description}",
            result.description.contains("solr"),
        )
    }

    // --- a version this build has no catalog for ----------------------------------------------------

    /**
     * A server running a line this build ships no catalog for is read, not refused.
     *
     * The fifth failure mode, and the one that reads least like a failure. The other four announce
     * themselves — nothing answers, the credential is wrong, the body is not JSON. This one arrives
     * as a perfectly good response naming a release that did not exist when the plugin was built,
     * which is the ordinary consequence of Solr shipping again. Refusing it would make every future
     * Solr an outage; guessing a guide URL for it would invent a page that may not be published.
     */
    fun testAServerOnAnUnrecognizedLineIsReadRatherThanRefused() {
        val url = givenServer(systemInfo = { respond(it, 200, systemBodyNaming("99.0.0")) })

        val result = read(connection(url))

        assertTrue(result.toString(), result is SolrResponse.Success)
        val read = (result as SolrResponse.Success).value
        assertEquals("the version is carried as the server said it", "99.0.0", read.solrVersion)
        assertEquals("the facts are unaffected", listOf("id"), read.facts.fields.map { it.name })
    }

    /**
     * And the model still sources it to the server.
     *
     * The alternative — falling back to the default selection — would discard the fact that a server
     * answered at all, which is exactly what the version is read for.
     */
    fun testAnUnrecognizedLineStillSourcesToTheServer() {
        val url = givenServer(systemInfo = { respond(it, 200, systemBodyNaming("99.0.0")) })

        val read = (read(connection(url)) as SolrResponse.Success).value
        val model = SolrFieldModel.of(SolrConfigsetFacts(), read.facts, read.solrVersion)

        assertEquals(SolrVersionSource.SERVER, model.solrVersion.source)
        assertEquals(
            "an unpublished guide must not be invented for it",
            SolrVersionSelection.DEFAULT.guidePathSegment,
            model.solrVersion.guidePathSegment,
        )
    }

    /** A supported line resolves to its own guide rather than the fallback. */
    fun testASupportedLineNamesItsOwnGuide() {
        val url = givenServer(systemInfo = { respond(it, 200, systemBodyNaming("9.10.1")) })

        val read = (read(connection(url)) as SolrResponse.Success).value
        val model = SolrFieldModel.of(SolrConfigsetFacts(), read.facts, read.solrVersion)

        assertEquals(SolrVersionSource.SERVER, model.solrVersion.source)
        assertFalse(
            "a line with a catalog must not fall back: ${model.solrVersion.guidePathSegment}",
            model.solrVersion.guidePathSegment == SolrVersionSelection.DEFAULT.guidePathSegment,
        )
    }
}
