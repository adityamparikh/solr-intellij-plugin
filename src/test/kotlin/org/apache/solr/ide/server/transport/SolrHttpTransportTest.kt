package org.apache.solr.ide.server.transport

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transport's behaviour against a server that misbehaves on demand.
 *
 * The specification's second testing tier. A real Solr will not hang, refuse a credential, or return
 * a malformed body when asked, so the states this has to classify are produced by an embedded
 * `com.sun.net.httpserver.HttpServer` in this JVM — which needs no dependency and no Docker, and is
 * what keeps this tier separate from the contract tests against a real Solr.
 *
 * **Two of these states came from running a real Solr and would not have been invented here.** A
 * mistyped collection answers 404 with an HTML error page rather than JSON, and a query that hits a
 * limit answers 200 with `partialResults` and a status of zero. The first looks like it should parse;
 * the second looks like success.
 */
class SolrHttpTransportTest {

    private var server: HttpServer? = null

    private fun given(handler: (HttpExchange) -> Unit): String {
        val started = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        started.createContext("/") { exchange -> handler(exchange) }
        started.start()
        server = started
        return "http://127.0.0.1:${started.address.port}"
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String, contentType: String = "application/json") {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    @After
    fun stopServer() {
        server?.stop(0)
    }

    private fun get(baseUrl: String, username: String? = null, password: String? = null) =
        SolrHttpTransport(timeout = Duration.ofMillis(500))
            .get(baseUrl, "/solr/products/schema", username, password)
            .join()

    // --- the states a real server will not produce on demand ---------------------------------------

    @Test
    fun `a healthy response is a success carrying the parsed body`() {
        val url = given { respond(it, 200, """{"responseHeader":{"status":0},"schema":{"name":"example"}}""") }
        val result = get(url)

        assertTrue(result.toString(), result is SolrResponse.Success)
        assertEquals("example", (result as SolrResponse.Success).value.path("schema").path("name").asString(""))
    }

    /** Solr's own message reaches the caller verbatim; the plugin never rewrites it. */
    @Test
    fun `a Solr error carries Solr's own message and code`() {
        val url = given {
            respond(it, 400, """{"responseHeader":{"status":400},"error":{"msg":"undefined field categry","code":400}}""")
        }
        val result = get(url)

        assertTrue(result.toString(), result is SolrResponse.SolrError)
        assertEquals("undefined field categry", (result as SolrResponse.SolrError).message)
        assertEquals(400, result.code)
    }

    /** A hang is a transport failure described in the plugin's words, since Solr said nothing. */
    @Test
    fun `a server that never answers is a transport failure`() {
        val url = given { Thread.sleep(5_000) }
        val result = get(url)

        assertTrue(result.toString(), result is SolrResponse.TransportFailure)
    }

    /** Nothing is listening: the same outcome, and still not an exception. */
    @Test
    fun `a refused connection is a transport failure`() {
        val result = get("http://127.0.0.1:1")

        assertTrue(result.toString(), result is SolrResponse.TransportFailure)
    }

    /** A 200 whose body is not the shape expected is unrecognized rather than thrown. */
    @Test
    fun `a malformed body is unrecognized`() {
        val url = given { respond(it, 200, """{"responseHeader":""") }
        val result = get(url)

        assertTrue(result.toString(), result is SolrResponse.Unrecognized)
    }

    @Test
    fun `an authentication failure is a Solr error rather than a transport one`() {
        val url = given { respond(it, 401, """{"responseHeader":{"status":401},"error":{"msg":"require authentication","code":401}}""") }
        val result = get(url)

        assertTrue(result.toString(), result is SolrResponse.SolrError)
        assertEquals(401, (result as SolrResponse.SolrError).code)
    }

    // --- the two states the wire-format pass found -------------------------------------------------

    /**
     * A mistyped collection answers 404 with an HTML page, not JSON.
     *
     * The single most likely user mistake, and the one that breaks a transport parsing every body as
     * JSON. The status is what says it failed; the absence of a Solr message is reported as absent
     * rather than invented.
     */
    @Test
    fun `a 404 carrying html is an error without a message, not a parse failure`() {
        val url = given {
            respond(it, 404, "<html><body><p>Searching for Solr?</p></body></html>", contentType = "text/html")
        }
        val result = get(url)

        assertTrue(result.toString(), result is SolrResponse.SolrError)
        assertEquals(404, (result as SolrResponse.SolrError).code)
        assertNull("no Solr message exists here, and one must not be invented", result.message)
    }

    /**
     * A 200 with `partialResults` is not a success.
     *
     * Status zero, HTTP 200, and the data is incomplete. Treated as success, a drift comparison built
     * on it would report fields as missing from a server that merely stopped early.
     */
    @Test
    fun `a partial response is distinguished from a complete one`() {
        val url = given {
            respond(
                it,
                200,
                """{"responseHeader":{"partialResults":true,"partialResultsDetails":"Limits exceeded!","status":0},"schema":{}}""",
            )
        }
        val result = get(url)

        assertTrue(result.toString(), result is SolrResponse.Partial)
        assertEquals("Limits exceeded!", (result as SolrResponse.Partial).detail)
    }

    // --- what the request carries ------------------------------------------------------------------

    /**
     * Authentication is sent, not negotiated.
     *
     * The header is on the first request rather than after a 401. A transport waiting to be
     * challenged works against a Solr that challenges and fails against one configured to answer 401
     * without a `WWW-Authenticate` header — and costs a round trip in every case.
     */
    @Test
    fun `a credential is sent preemptively on the first request`() {
        val seen = authorizationSentFor(username = "solr", password = "SolrRocks")

        assertTrue("expected a Basic header on the first request, got $seen", seen?.startsWith("Basic ") == true)
    }

    /** No credential configured, no header — rather than an empty one. */
    @Test
    fun `no credential means no authorization header`() {
        assertNull(authorizationSentFor(username = null, password = null))
    }

    /** The header the server saw on the one request this makes. */
    private fun authorizationSentFor(username: String?, password: String?): String? {
        var seen: String? = null
        val url = given { exchange ->
            seen = exchange.requestHeaders.getFirst("Authorization")
            respond(exchange, 200, """{"responseHeader":{"status":0}}""")
        }
        get(url, username, password)
        return seen
    }
}
