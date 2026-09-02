package org.apache.solr.ide.server.indexing

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase
import org.apache.solr.ide.server.connection.SolrConnection
import org.apache.solr.ide.server.transport.SolrResponse

/**
 * What reaches the server when a document is indexed.
 *
 * The embedded server is not Solr: the wire format was settled by performing it against Solr 10.0.0.
 * What is under test is the request this plugin builds — which for a write is the one thing a user
 * cannot check by looking at the result.
 */
class SolrDocumentIndexerTest : SolrConfigsetTestCase() {

    private var server: HttpServer? = null
    private val requested = mutableListOf<String>()
    private var body: String? = null
    private var contentType: String? = null

    private fun givenServer(write: (HttpExchange) -> Unit = { respond(it, 200, OK) }): String {
        val started = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        started.createContext("/") { exchange ->
            requested += exchange.requestURI.toString()
            contentType = exchange.requestHeaders.getFirst("Content-Type")
            body = exchange.requestBody.readBytes().decodeToString()
            write(exchange)
        }
        started.start()
        server = started
        return "http://127.0.0.1:${started.address.port}"
    }

    private fun respond(exchange: HttpExchange, status: Int, text: String) {
        val bytes = text.toByteArray()
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

    private fun indexer() = SolrDocumentIndexer.getInstance(project)

    private fun index(
        url: String,
        document: String = """{"id":"1"}""",
        collection: String = "books",
        commit: SolrCommitMode = SolrCommitMode.WITHIN,
    ) = runBlocking {
        indexer().index(
            SolrConnection(id = "c1", displayName = "local", baseUrl = url),
            collection,
            document,
            commit,
        )
    }

    // --- where it goes ------------------------------------------------------------------------------

    fun testADocumentIsPostedToTheUpdateHandler() {
        val result = index(givenServer())

        assertTrue(result.toString(), result is SolrResponse.Success)
        assertTrue(requested.toString(), requested.single().startsWith("/books/update"))
        assertEquals("application/json", contentType)
    }

    fun testACollectionNameIsUrlEncoded() {
        index(givenServer(), collection = "books&action=DELETE")

        assertTrue(requested.single(), requested.single().contains("books%26action%3DDELETE"))
    }

    // --- when it becomes findable --------------------------------------------------------------------

    /**
     * The default sends `commitWithin`, and says so rather than defaulting silently.
     *
     * A hard commit on a shared server is somebody else's latency spike, and is the wrong thing to
     * make easiest.
     */
    fun testTheDefaultCommitsWithin() {
        index(givenServer())

        assertTrue(requested.single(), requested.single().contains("commitWithin="))
        assertFalse(requested.single(), requested.single().contains("commit=true"))
    }

    fun testAnImmediateCommitAsksForOne() {
        index(givenServer(), commit = SolrCommitMode.IMMEDIATE)

        assertTrue(requested.single(), requested.single().contains("commit=true"))
    }

    /** Leaving it to the server sends no commit parameter at all. */
    fun testNoCommitSendsNoParameter() {
        index(givenServer(), commit = SolrCommitMode.NONE)

        assertFalse(requested.single(), requested.single().contains("commit"))
    }

    /** Every mode says what it means, because the confirmation shows the label rather than the name. */
    fun testEveryCommitModeIsDescribed() {
        SolrCommitMode.entries.forEach { assertTrue(it.name, it.label.isNotBlank()) }
    }

    // --- what is sent ---------------------------------------------------------------------------------

    /** A single document is wrapped, because the update handler takes a list. */
    fun testASingleDocumentIsWrappedInAList() {
        index(givenServer(), document = """{"id":"1"}""")

        assertEquals("""[{"id":"1"}]""", body)
    }

    /** A document already in a list is not wrapped twice — double wrapping is a parse error. */
    fun testAListIsNotWrappedAgain() {
        index(givenServer(), document = """[{"id":"1"},{"id":"2"}]""")

        assertEquals("""[{"id":"1"},{"id":"2"}]""", body)
    }

    fun testSurroundingWhitespaceDoesNotChangeTheWrapping() {
        assertEquals("""[{"id":"1"}]""", indexer().asDocumentList("\n  {\"id\":\"1\"}  \n"))
        assertEquals("""[{"id":"1"}]""", indexer().asDocumentList("  [{\"id\":\"1\"}]  "))
    }

    // --- what comes back ------------------------------------------------------------------------------

    /** Solr's refusal is reported as Solr's. */
    fun testARefusedDocumentIsReported() {
        val url = givenServer { respond(it, 400, """{"error":{"msg":"Invalid Number: abc"}}""") }

        val result = index(url)

        assertTrue(result.toString(), result is SolrResponse.SolrError)
        assertEquals("Invalid Number: abc", (result as SolrResponse.SolrError).message)
    }

    /** A connection naming a user with nothing stored writes nothing. */
    fun testAConnectionWithNoStoredPasswordSendsNothing() {
        val url = givenServer()
        val authenticated = SolrConnection(id = "c1", displayName = "local", baseUrl = url, username = "solr")

        val result = runBlocking { indexer().index(authenticated, "books", """{"id":"1"}""") }

        assertTrue(result.toString(), result is SolrResponse.TransportFailure)
        assertEmpty(requested)
    }

    private companion object {
        const val OK = """{"responseHeader":{"status":0}}"""
    }
}
