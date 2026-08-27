package org.apache.solr.ide.server.transport

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.sun.net.httpserver.HttpExchange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.system.measureTimeMillis
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun get(baseUrl: String, credential: SolrCredential = SolrCredential.None) = runBlocking {
        SolrHttpTransport(timeout = Duration.ofMillis(500))
            .get(baseUrl, "/solr/products/schema", credential)
    }

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

    /**
     * A caller that goes away takes its request with it.
     *
     * The assertion a `CompletableFuture` could not support, and the reason this is a suspending
     * function: cancelling the scope interrupts the blocking send rather than detaching a caller from
     * a request that carries on running. A request nobody is waiting for is a leak even when nothing
     * notices it.
     */
    @Test
    fun `cancelling the caller cancels the request`() {
        val url = given { Thread.sleep(30_000) }
        var completed = false

        val elapsed = measureTimeMillis {
            runBlocking {
                val job = launch(Dispatchers.IO) {
                    SolrHttpTransport(timeout = Duration.ofSeconds(30)).get(url, "/solr/products/schema")
                    completed = true
                }
                // Long enough for the request to be in flight, far short of the server's sleep.
                delay(300)
                job.cancelAndJoin()
            }
        }

        assertFalse("the request should have been cancelled, not completed", completed)
        // **The assertion that makes this test mean anything.** Without it the test passes on a
        // transport that lets the blocking send run to completion and only then notices it was
        // cancelled — which is what a plain `withContext` does, and which took the full thirty
        // seconds. Cancellation that arrives after the work finishes is not cancellation.
        assertTrue(
            "cancelling must interrupt the request, not wait for it; took ${elapsed}ms",
            elapsed < 5_000,
        )
    }

    // --- one client per project, released with it ---------------------------------------------------

    /**
     * The transport is a project service, so the platform owns exactly one and disposes it.
     *
     * An `HttpClient` owns a selector thread, a worker pool and a connection pool. Built per
     * request it would leak threads into a long-running IDE and defeat keep-alive; never closed, its
     * threads hold the plugin's classloader and the plugin cannot be unloaded. Both are the
     * platform's problem once this is a service, which is what this asserts rather than assumes.
     */
    @Test
    fun `the transport is one disposable service per project`() {
        assertTrue(
            "SolrHttpTransport must be a project-level service",
            SolrHttpTransport::class.java.getAnnotation(Service::class.java)
                ?.value?.contains(Service.Level.PROJECT) == true,
        )
        assertTrue(
            "a transport owning an HttpClient must be Disposable",
            Disposable::class.java.isAssignableFrom(SolrHttpTransport::class.java),
        )
    }

    /** Disposing releases the client rather than leaving its threads behind. */
    @Test
    fun `disposing the transport closes its client`() {
        val transport = SolrHttpTransport(timeout = Duration.ofMillis(500))
        val url = given { respond(it, 200, """{"responseHeader":{"status":0}}""") }
        runBlocking { transport.get(url, "/solr/products/schema") }

        transport.dispose()

        // A closed client refuses further work rather than silently continuing to hold its pool.
        val afterClose = runBlocking { transport.get(url, "/solr/products/schema") }
        assertTrue(afterClose.toString(), afterClose is SolrResponse.TransportFailure)
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
        val seen = authorizationSentFor(SolrCredential.Resolved("solr", "SolrRocks"))

        assertTrue("expected a Basic header on the first request, got $seen", seen?.startsWith("Basic ") == true)
    }

    /** No credential configured, no header — rather than an empty one. */
    @Test
    fun `no credential means no authorization header`() {
        assertNull(authorizationSentFor(SolrCredential.None))
    }

    /**
     * A connection naming a user with no stored password is reported, not sent.
     *
     * The case two nullable strings could not express. Sending `user:` would be rejected by most
     * Solr Basic Auth configurations as a *wrong* credential rather than *no* credential, turning a
     * cleared PasswordSafe entry into a spurious authentication failure against a server that was
     * never asked properly. Nothing reaches the wire, and the failure names the user so the
     * incomplete connection is identifiable.
     */
    @Test
    fun `a credential with no stored password never reaches the wire`() {
        var reached = false
        val url = given { exchange ->
            reached = true
            respond(exchange, 200, """{"responseHeader":{"status":0}}""")
        }
        val result = get(url, SolrCredential.Missing("solr"))

        assertFalse("no request may be sent for an incomplete credential", reached)
        assertTrue(result.toString(), result is SolrResponse.TransportFailure)
        assertTrue(
            "the failure should name the user, got: ${(result as SolrResponse.TransportFailure).description}",
            result.description.contains("solr"),
        )
    }

    /** The header the server saw on the one request this makes. */
    private fun authorizationSentFor(credential: SolrCredential): String? {
        var seen: String? = null
        val url = given { exchange ->
            seen = exchange.requestHeaders.getFirst("Authorization")
            respond(exchange, 200, """{"responseHeader":{"status":0}}""")
        }
        get(url, credential)
        return seen
    }
}
