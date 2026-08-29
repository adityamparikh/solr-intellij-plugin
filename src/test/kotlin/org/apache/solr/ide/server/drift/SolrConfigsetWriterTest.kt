package org.apache.solr.ide.server.drift

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase
import org.apache.solr.ide.server.connection.SolrConnection
import org.apache.solr.ide.server.transport.SolrResponse

/**
 * The two writes, against a server that records what it was asked.
 *
 * **What was sent matters as much as what came back.** A write is the one place where getting the
 * request wrong is not recoverable by the user noticing — an upload addressed to the wrong name
 * replaces the wrong configset, and a reload of the wrong collection reports success having done
 * nothing to the one they meant.
 *
 * The embedded server is deliberately not Solr: what is under test is the request this plugin
 * builds and the outcome it reports, and the wire format itself was settled by performing it
 * against Solr 10.0.0 in SolrCloud mode.
 */
class SolrConfigsetWriterTest : SolrConfigsetTestCase() {

    private var server: HttpServer? = null
    private val requested = mutableListOf<String>()
    private val bodies = mutableMapOf<String, ByteArray>()
    private var contentTypes = mutableListOf<String?>()
    private var authorization: String? = null

    private val cloudSystemInfo = """{"responseHeader":{"status":0},"mode":"solrcloud"}"""
    private val standaloneSystemInfo = """{"responseHeader":{"status":0},"mode":"std"}"""
    private val clusterStatus = """{"responseHeader":{"status":0},"cluster":{"collections":{}}}"""
    private val ok = """{"responseHeader":{"status":0}}"""

    private fun givenServer(
        systemInfo: String = cloudSystemInfo,
        write: (HttpExchange) -> Unit = { respond(it, 200, ok) },
    ): String {
        val started = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        started.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            requested += exchange.requestURI.toString()
            contentTypes += exchange.requestHeaders.getFirst("Content-Type")
            authorization = authorization ?: exchange.requestHeaders.getFirst("Authorization")
            bodies[path] = exchange.requestBody.readBytes()
            when {
                path.endsWith("/admin/info/system") -> respond(exchange, 200, systemInfo)
                path.endsWith("/admin/collections") && exchange.requestURI.query?.contains("CLUSTERSTATUS") == true ->
                    respond(exchange, 200, clusterStatus)
                else -> write(exchange)
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

    private fun connection(baseUrl: String) =
        SolrConnection(id = "c1", displayName = "local", baseUrl = baseUrl)

    private fun writer() = SolrConfigsetWriter.getInstance(project)

    private fun upload(url: String, name: String = "books", archive: ByteArray = "zip".toByteArray()) =
        runBlocking { writer().upload(connection(url), name, archive) }

    private fun reload(url: String, collection: String = "books") =
        runBlocking { writer().reload(connection(url), collection) }

    // --- uploading ---------------------------------------------------------------------------------

    fun testAnUploadPostsTheArchiveToTheConfigsetsApi() {
        val url = givenServer()

        val result = upload(url, name = "books", archive = "the-zip-bytes".toByteArray())

        assertTrue(result.toString(), result is SolrResponse.Success)
        val uploadRequest = requested.single { it.contains("action=UPLOAD") }
        assertTrue(uploadRequest, uploadRequest.contains("/solr/admin/configs"))
        assertTrue(uploadRequest, uploadRequest.contains("name=books"))
        assertEquals("the-zip-bytes", bodies["/solr/admin/configs"]?.decodeToString())
    }

    /** Raw, not multipart: both reach the same outcome and only one needs an encoder written. */
    fun testTheArchiveIsSentAsARawBody() {
        upload(givenServer())

        assertTrue(contentTypes.toString(), contentTypes.contains("application/octet-stream"))
        assertFalse(contentTypes.toString(), contentTypes.any { it?.contains("multipart") == true })
    }

    /**
     * A name carrying a `&` is encoded, not sent as written.
     *
     * Names come from a directory on disk and from a user, so they carry whatever those allow. An
     * unencoded `&` would truncate the request into a different one — and for a write, a different
     * request is a different write.
     */
    fun testAConfigsetNameIsUrlEncoded() {
        upload(givenServer(), name = "books&action=DELETE")

        val uploadRequest = requested.single { it.contains("action=UPLOAD") }
        assertTrue(uploadRequest, uploadRequest.contains("books%26action%3DDELETE"))
    }

    /**
     * A standalone server is not asked, and is told why.
     *
     * It answers every `/admin/configs` action with HTTP 400 and "Solr instance is not running in
     * SolrCloud mode" — so asking anyway would report a hard failure against a healthy server, and
     * catching that 400 would swallow the genuinely different one a malformed request produces.
     */
    fun testAStandaloneServerIsNotAskedToTakeAnUpload() {
        val url = givenServer(systemInfo = standaloneSystemInfo)

        val result = upload(url)

        assertTrue(result.toString(), result is SolrResponse.TransportFailure)
        assertEquals(SolrConfigsetWriter.NOT_SOLR_CLOUD, (result as SolrResponse.TransportFailure).description)
        assertFalse("nothing may be uploaded: $requested", requested.any { it.contains("action=UPLOAD") })
    }

    /** Solr's refusal is reported as Solr's, not rewritten. */
    fun testAnUploadSolrRefusesIsReported() {
        val url = givenServer(write = { respond(it, 400, """{"error":{"msg":"Configset already exists"}}""") })

        val result = upload(url)

        assertTrue(result.toString(), result is SolrResponse.SolrError)
        assertEquals(400, (result as SolrResponse.SolrError).code)
        assertEquals("Configset already exists", result.message)
    }

    // --- reloading ---------------------------------------------------------------------------------

    /**
     * The collection form, not the core form.
     *
     * A pairing names a collection. Reaching for the cores underneath it would mean resolving
     * replicas this plugin has no reason to know about, and getting that wrong on a multi-replica
     * collection would reload one replica and report the collection reloaded.
     */
    fun testAReloadUsesTheCollectionForm() {
        val url = givenServer()

        val result = reload(url, collection = "books")

        assertTrue(result.toString(), result is SolrResponse.Success)
        val reloadRequest = requested.single { it.contains("action=RELOAD") }
        assertTrue(reloadRequest, reloadRequest.contains("/solr/admin/collections"))
        assertTrue(reloadRequest, reloadRequest.contains("name=books"))
        assertFalse("the core form must not be used", reloadRequest.contains("/admin/cores"))
    }

    fun testACollectionNameIsUrlEncoded() {
        reload(givenServer(), collection = "books&action=DELETE")

        val reloadRequest = requested.single { it.contains("action=RELOAD") }
        assertTrue(reloadRequest, reloadRequest.contains("books%26action%3DDELETE"))
    }

    /** Reloading a collection that does not exist is Solr's 400, reported in Solr's words. */
    fun testAReloadOfAnUnknownCollectionIsReported() {
        val url = givenServer(write = { respond(it, 400, """{"error":{"msg":"Could not find collection : nope"}}""") })

        val result = reload(url, collection = "nope")

        assertTrue(result.toString(), result is SolrResponse.SolrError)
        assertTrue((result as SolrResponse.SolrError).message!!, result.message!!.contains("Could not find collection"))
    }

    /** A reload asks the server nothing about its mode — both shapes of server can reload. */
    fun testAReloadDoesNotAskAboutTheMode() {
        reload(givenServer())

        assertFalse("a reload needs no mode check: $requested", requested.any { it.contains("/admin/info/system") })
    }

    // --- the credential, and the branches around it ------------------------------------------------

    /** A connection naming no user sends no authorization at all. */
    fun testAnAnonymousConnectionSendsNoCredential() {
        val url = givenServer()

        upload(url)

        assertNull(authorization)
    }

    /**
     * A connection naming a user with nothing stored is refused before anything is sent.
     *
     * Solr reads `user:` as a *wrong* password rather than a missing one, so asking would turn a
     * cleared PasswordSafe entry into an authentication failure against a server never properly
     * asked. For a write that matters more than for a read.
     */
    fun testAConnectionWithNoStoredPasswordWritesNothing() {
        val url = givenServer()
        val authenticated = SolrConnection(id = "c1", displayName = "local", baseUrl = url, username = "solr")

        val result = runBlocking { writer().upload(authenticated, "books", "zip".toByteArray()) }

        assertTrue(result.toString(), result is SolrResponse.TransportFailure)
        assertFalse("nothing may be uploaded: $requested", requested.any { it.contains("action=UPLOAD") })
    }

    /** A stored password is sent, and the write proceeds. */
    fun testAStoredPasswordIsSentWithTheWrite() {
        val url = givenServer()
        val authenticated = SolrConnection(id = "c1", displayName = "local", baseUrl = url, username = "solr")
        connectionSettings.addConnection(authenticated)
        connectionSettings.setPassword("c1", "SolrRocks".toCharArray())

        runBlocking { writer().upload(authenticated, "books", "zip".toByteArray()) }

        assertTrue("expected a Basic header, got $authorization", authorization?.startsWith("Basic ") == true)
    }

    /**
     * A server that will not say which mode it is in is not written to.
     *
     * Both supported Solr lines always report a mode, so a server that does not is one this plugin
     * has not identified — and refusing to guess is the same rule the topology reader follows, with
     * more at stake. Reading the wrong thing shows a wrong list; writing to a server whose shape is
     * unknown is a write nobody can predict the effect of.
     */
    fun testAServerOfUnknownModeIsNotWrittenTo() {
        val url = givenServer(systemInfo = """{"responseHeader":{"status":0}}""")

        val result = upload(url)

        assertTrue(result.toString(), result is SolrResponse.TransportFailure)
        assertFalse("nothing may be uploaded: $requested", requested.any { it.contains("action=UPLOAD") })
    }

    /**
     * A server that could not be reached at all is attempted anyway.
     *
     * The mode is unknown for a different reason there — nothing answered — and the upload's own
     * failure will say so in terms of what actually happened, which is better than a refusal
     * blaming a mode nobody could read.
     */
    fun testAServerThatCouldNotBeReachedIsStillAttempted() {
        val url = givenServer(systemInfo = """<html>gateway timeout</html>""")

        upload(url)

        assertTrue("the upload must still be attempted: $requested", requested.any { it.contains("action=UPLOAD") })
    }

    /** A reload also resolves its credential, and an incomplete one stops it. */
    fun testAReloadWithNoStoredPasswordSendsNothing() {
        val url = givenServer()
        val authenticated = SolrConnection(id = "c1", displayName = "local", baseUrl = url, username = "solr")

        val result = runBlocking { writer().reload(authenticated, "books") }

        assertTrue(result.toString(), result is SolrResponse.TransportFailure)
        assertFalse("$requested", requested.any { it.contains("action=RELOAD") })
    }
}
