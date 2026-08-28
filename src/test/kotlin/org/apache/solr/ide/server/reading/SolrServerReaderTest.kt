package org.apache.solr.ide.server.reading

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase
import org.apache.solr.ide.server.connection.SolrConnection
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

    private val systemBody = """
        {"responseHeader":{"status":0},"mode":"solrcloud",
         "lucene":{"solr-spec-version":"10.0.0","lucene-spec-version":"10.3.2"}}
    """.trimIndent()

    /** A server answering both endpoints the reader asks for, and recording what it was asked. */
    private fun givenServer(
        schema: (HttpExchange) -> Unit = { respond(it, 200, schemaBody) },
        systemInfo: (HttpExchange) -> Unit = { respond(it, 200, systemBody) },
    ): String {
        val started = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        started.createContext("/") { exchange ->
            requested += exchange.requestURI.path
            authorization = authorization ?: exchange.requestHeaders.getFirst("Authorization")
            if (exchange.requestURI.path.endsWith("/schema")) schema(exchange) else systemInfo(exchange)
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

        assertTrue("asked for: $requested", requested.any { it == "/solr/products/schema" })
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
}
