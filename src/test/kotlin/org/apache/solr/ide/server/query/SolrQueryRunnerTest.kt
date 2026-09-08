package org.apache.solr.ide.server.query

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase
import org.apache.solr.ide.server.connection.SolrConnection
import org.apache.solr.ide.server.transport.SolrResponse

/**
 * Sending one query and reading the answer.
 *
 * **The first thing this plugin sends that is not a question about a server's shape.** Everything
 * else the server surface does reads a schema, a topology or an index's fields, or writes a
 * configset. Queries have been run by the HTTP Client, with this plugin only contributing the
 * request text and reading the response back — so what is asserted here is mostly what the request
 * *is*, because that has never been this plugin's to get right before.
 *
 * The server is embedded rather than a container: what is under test is the request and the
 * classification of the answer, and the contract tier owns Solr's own wire format.
 */
class SolrQueryRunnerTest : SolrConfigsetTestCase() {

    private var server: HttpServer? = null
    private var requested: String? = null

    private val oneDocument = """
        {"responseHeader":{"status":0,"QTime":7},
         "response":{"numFound":1,"start":0,"docs":[{"id":"1","category":"books"}]}}
    """.trimIndent()

    private fun givenServer(answer: (HttpExchange) -> Unit = { respond(it, 200, oneDocument) }): String {
        val started = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        started.createContext("/") { exchange ->
            requested = exchange.requestURI.toString()
            answer(exchange)
        }
        started.start()
        server = started
        return "http://127.0.0.1:${started.address.port}/solr"
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

    private fun ask(url: String, collection: String = "books", query: String = "category:books") = runBlocking {
        SolrQueryRunner.getInstance(project).execute(
            SolrConnection(id = "c1", displayName = "local", baseUrl = url),
            collection,
            query,
        )
    }

    // --- what it sends ------------------------------------------------------------------------------

    /**
     * The query reaches `select` under the collection, relative to the connection's root.
     *
     * The path is composed the way every other request in this plugin composes one — relative to the
     * root the connection names — because a `/solr` prefix added here would reach the wire as
     * `/solr/solr/…`, which is a defect this codebase has already shipped once.
     */
    fun testTheQueryGoesToSelectUnderTheCollection() {
        ask(givenServer())

        assertEquals("/solr/books/select?q=category%3Abooks", requested)
    }

    /**
     * Only `q` is sent.
     *
     * Every parameter added here is one the code did not ask for. A `rows` or an `fl` would change
     * what comes back from what the source says, and the reader would have no way to say so.
     */
    fun testNothingButTheQueryIsSent() {
        ask(givenServer())

        assertFalse(requested.orEmpty(), requested.orEmpty().contains("rows"))
        assertFalse(requested.orEmpty(), requested.orEmpty().contains("&fl="))
    }

    /** A query carrying characters a URL reads differently is encoded, not truncated. */
    fun testAQueryWithUrlSyntaxIsEncoded() {
        ask(givenServer(), query = "category:books&rows=99")

        assertFalse("the query must not become a second parameter", requested.orEmpty().contains("&rows=99"))
    }

    /** A collection name is encoded too, for the same reason. */
    fun testACollectionNameIsEncoded() {
        ask(givenServer(), collection = "my books")

        assertEquals("/solr/my+books/select?q=category%3Abooks", requested)
    }

    // --- what it makes of the answer ----------------------------------------------------------------

    fun testAQueryAnswerIsRead() {
        val result = ask(givenServer())

        assertTrue(result.toString(), result is SolrResponse.Success<*>)
        assertEquals(1L, (result as SolrResponse.Success<SolrQueryResult>).value.numFound)
    }

    /**
     * A healthy answer that is not a query result is reported rather than shown as an empty one.
     *
     * A reader meeting an empty table takes it to mean their query matched nothing. That is a
     * different fact from the server having answered a different question, and conflating them
     * would send someone looking for a bug in a query that was never run.
     */
    fun testAnAnswerThatIsNotAQueryResultIsReported() {
        val result = ask(givenServer { respond(it, 200, """{"responseHeader":{"status":0},"cluster":{}}""") })

        assertTrue(result.toString(), result is SolrResponse.TransportFailure)
    }

    /** Solr's own error is carried through, with the code it answered. */
    fun testASolrErrorIsCarried() {
        val result = ask(
            givenServer {
                respond(it, 400, """{"responseHeader":{"status":400},"error":{"msg":"undefined field categry","code":400}}""")
            },
        )

        assertTrue(result.toString(), result is SolrResponse.SolrError)
    }
}
