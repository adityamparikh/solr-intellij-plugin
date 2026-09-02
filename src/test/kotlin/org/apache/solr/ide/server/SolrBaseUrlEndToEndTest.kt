package org.apache.solr.ide.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase
import org.apache.solr.ide.server.connection.SolrConnection
import org.apache.solr.ide.server.drift.SolrConfigsetWriter
import org.apache.solr.ide.server.indexing.SolrCommitMode
import org.apache.solr.ide.server.indexing.SolrDocumentIndexer
import org.apache.solr.ide.server.reading.SolrServerReader
import org.apache.solr.ide.server.transport.SolrResponse

/**
 * The URL a user types reaching the wire unchanged.
 *
 * **Every other test on this path feeds the reader a base URL no user would enter.** The connection
 * dialog documents the base URL as the Solr root — `http://localhost:8983/solr` — and the manual
 * suite's first server check instructs saving exactly that. The unit and contract tiers both pass a
 * bare host instead, and then assert the `/solr` the production code adds for itself. Both halves
 * agreed with each other and neither agreed with the dialog, so a doubled `/solr/solr/...` reached
 * every real server and every test stayed green.
 *
 * That is the same shape as the gap `SolrServerVersionEndToEndTest` was written for: two correct
 * halves that never met, caught only by composing the user-facing input end to end. The fixture here
 * is deliberately strict for the same reason — it answers the paths a real Solr answers and 404s
 * everything else, so a mis-composed URL fails the way it failed a user rather than as a string
 * comparison nobody reads.
 */
class SolrBaseUrlEndToEndTest : SolrConfigsetTestCase() {

    private var server: HttpServer? = null
    private val requested = mutableListOf<String>()

    private val schemaBody = """
        {"responseHeader":{"status":0},
         "schema":{"uniqueKey":"id","fields":[{"name":"id","type":"string","indexed":true}],
                   "fieldTypes":[{"name":"string","class":"solr.StrField"}]}}
    """.trimIndent()

    private val systemBody = """
        {"responseHeader":{"status":0},"mode":"solrcloud",
         "lucene":{"solr-spec-version":"10.0.0","lucene-spec-version":"10.3.2"}}
    """.trimIndent()

    private val clusterStatusBody = """
        {"responseHeader":{"status":0},
         "cluster":{"collections":{"books":{"configName":"books_config","health":"GREEN","shards":{}}},
          "live_nodes":["127.0.0.1:8983_solr"]}}
    """.trimIndent()

    private val okBody = """{"responseHeader":{"status":0}}"""

    /**
     * A server rooted at `/solr`, the way a stock Solr is.
     *
     * It answers the exact paths Solr answers and 404s the rest. Routing on a suffix would accept a
     * doubled prefix — `/solr/solr/books/schema` still ends in `/schema` — which is precisely the
     * request this test exists to reject.
     */
    private fun givenSolrRootedAtSolr(): String {
        val answers = mapOf(
            "/solr/admin/info/system" to systemBody,
            "/solr/admin/collections" to clusterStatusBody,
            "/solr/admin/cores" to okBody,
            "/solr/books/schema" to schemaBody,
            "/solr/books/update" to okBody,
            "/solr/books/admin/luke" to okBody,
        )
        val started = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        started.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            requested += path
            answers[path]?.let { respond(exchange, 200, it) } ?: respond(exchange, 404, "")
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

    private fun connection(baseUrl: String) =
        SolrConnection(id = "c1", displayName = "local", baseUrl = baseUrl, username = null)

    private fun assertNothingDoubled() =
        assertTrue(
            "a path was composed onto the base URL's own Solr root: $requested",
            requested.none { it.startsWith("/solr/solr/") },
        )

    // --- the three surfaces that build a URL ------------------------------------------------------

    /** The reader asks the server root the connection names, and the schema arrives. */
    fun testTheReaderAsksTheServerRootTheConnectionNames() {
        val result = runBlocking {
            SolrServerReader.getInstance(project).read(connection(givenSolrRootedAtSolr()), "books")
        }

        assertNothingDoubled()
        assertTrue("asked for: $requested", requested.contains("/solr/books/schema"))
        assertTrue(result.toString(), result is SolrResponse.Success)
        assertEquals(listOf("id"), (result as SolrResponse.Success).value.facts.fields.map { it.name })
    }

    /** The same holds for the topology, which picks its endpoint from the mode it just read. */
    fun testTheTopologyAsksTheServerRootTheConnectionNames() {
        val result = runBlocking {
            SolrServerReader.getInstance(project).topology(connection(givenSolrRootedAtSolr()))
        }

        assertNothingDoubled()
        assertTrue("asked for: $requested", requested.contains("/solr/admin/info/system"))
        assertTrue("asked for: $requested", requested.contains("/solr/admin/collections"))
        assertTrue(result.toString(), result is SolrResponse.Success)
    }

    /** A reload is a write, and it is rooted the same way a read is. */
    fun testAReloadAsksTheServerRootTheConnectionNames() {
        runBlocking {
            SolrConfigsetWriter.getInstance(project).reload(connection(givenSolrRootedAtSolr()), "books")
        }

        assertNothingDoubled()
        assertTrue("asked for: $requested", requested.contains("/solr/admin/collections"))
    }

    /** So is indexing a document, which is the one write a user invokes per document. */
    fun testIndexingAsksTheServerRootTheConnectionNames() {
        runBlocking {
            SolrDocumentIndexer.getInstance(project).index(
                connection(givenSolrRootedAtSolr()),
                collection = "books",
                document = """{"id":"1"}""",
                commit = SolrCommitMode.WITHIN,
            )
        }

        assertNothingDoubled()
        assertTrue("asked for: $requested", requested.contains("/solr/books/update"))
    }
}
