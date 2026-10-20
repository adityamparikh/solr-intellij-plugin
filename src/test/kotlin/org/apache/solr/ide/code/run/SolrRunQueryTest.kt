package org.apache.solr.ide.code.run

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.apache.solr.ide.SolrBundle
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase
import org.apache.solr.ide.server.connection.SolrConnection
import org.apache.solr.ide.server.connection.SolrConnectionSettings

/**
 * What clicking the gutter icon decides: which collections to offer, and what to say about the answer.
 *
 * **The popups are not under test, and the decisions beneath them are.** A headless fixture cannot
 * drive a list popup, which is why this click went untested for as long as it existed;
 * [SolrRunQuery.choicesFor] and [SolrRunQuery.answerFor] return what the popups would show, so a
 * test can hold it. [SolrRunQueryLineMarkerTest] owns where the icon appears.
 *
 * The server is embedded rather than a container, for the reason the runner's own test gives: the
 * contract tier owns Solr's wire format, and what is asserted here is what the plugin makes of it.
 */
class SolrRunQueryTest : SolrConfigsetTestCase() {

    private var server: HttpServer? = null

    private fun systemInfo(mode: String) = """{"responseHeader":{"status":0},"mode":"$mode"}"""

    private fun clusterStatus(vararg collections: String) = """
        {"responseHeader":{"status":0},
         "cluster":{"collections":{${collections.joinToString(",") { "\"$it\":{\"shards\":{}}" }}},
          "live_nodes":["127.0.0.1:8983_solr"]}}
    """.trimIndent()

    private val oneDocument = """
        {"responseHeader":{"status":0,"QTime":7},
         "response":{"numFound":1,"start":0,"docs":[{"id":"1","category":"books"}]}}
    """.trimIndent()

    private fun givenServer(
        systemInfo: (HttpExchange) -> Unit = { respond(it, 200, systemInfo("solrcloud")) },
        collections: (HttpExchange) -> Unit = { respond(it, 200, clusterStatus("shows", "books")) },
        cores: (HttpExchange) -> Unit = { respond(it, 200, """{"responseHeader":{"status":0},"status":{}}""") },
        select: (HttpExchange) -> Unit = { respond(it, 200, oneDocument) },
    ): String {
        val started = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        started.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            when {
                path.endsWith("/admin/collections") -> collections(exchange)
                path.endsWith("/admin/cores") -> cores(exchange)
                path.endsWith("/select") -> select(exchange)
                else -> systemInfo(exchange)
            }
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

    private fun connectedTo(baseUrl: String): SolrConnection {
        val connection = SolrConnection(id = "c1", displayName = "local", baseUrl = baseUrl)
        SolrConnectionSettings.getInstance(project).addConnection(connection)
        return connection
    }

    private fun choices() = runBlocking { SolrRunQuery.choicesFor(project) }

    private fun answer(connection: SolrConnection, collection: String = "books") = runBlocking {
        SolrRunQuery.answerFor(project, connection, collection, "category:books")
    }

    // --- what the chooser offers --------------------------------------------------------------------

    /** With no connection there is nothing to ask, and the sentence says where to add one. */
    fun testNoConnectionSaysWhereToAddOne() {
        assertEquals(SolrRunChoices.Refusal(SolrBundle.message("code.runQuery.noConnection")), choices())
    }

    /** A SolrCloud server offers its collections, sorted, on the connection that answered. */
    fun testCollectionsAreOfferedSorted() {
        val connection = connectedTo(givenServer())

        assertEquals(SolrRunChoices.Offer(connection, listOf("books", "shows")), choices())
    }

    /**
     * A standalone server offers its cores, since that is the vocabulary it answers in.
     *
     * A query runs against a core's name exactly as against a collection's, so the chooser need not
     * say which it is offering.
     */
    fun testAStandaloneServerOffersItsCores() {
        val connection = connectedTo(
            givenServer(
                systemInfo = { respond(it, 200, systemInfo("std")) },
                cores = {
                    respond(it, 200, """{"responseHeader":{"status":0},"status":{"techproducts":{"name":"techproducts"}}}""")
                },
            ),
        )

        assertEquals(SolrRunChoices.Offer(connection, listOf("techproducts")), choices())
    }

    /** A server that holds nothing says so, naming the connection that answered. */
    fun testAnEmptyServerSaysSoNamingTheConnection() {
        connectedTo(givenServer(collections = { respond(it, 200, clusterStatus()) }))

        assertEquals(
            SolrRunChoices.Refusal(SolrBundle.message("code.runQuery.noCollections", "local")),
            choices(),
        )
    }

    /**
     * A server that could not be reached is not reported as one with no collections.
     *
     * This was the defect that writing these tests found: the topology read's failure was folded
     * into an empty list, so a stopped server answered *local answered with no collections* — a
     * claim about a server that never answered, sending the reader to look for collections that may
     * well exist.
     */
    fun testAnUnreachableServerIsNotReportedAsEmpty() {
        val url = givenServer()
        server?.stop(0)
        server = null
        connectedTo(url)

        val choices = choices()

        assertTrue(choices.toString(), choices is SolrRunChoices.Refusal)
        assertFalse(
            choices.toString(),
            (choices as SolrRunChoices.Refusal).message == SolrBundle.message("code.runQuery.noCollections", "local"),
        )
    }

    /** Solr refusing the topology request is reported as Solr's answer, with its code. */
    fun testASolrRefusalIsReportedWithItsCode() {
        connectedTo(
            givenServer(
                collections = {
                    respond(it, 401, """{"responseHeader":{"status":401},"error":{"msg":"Unauthorized","code":401}}""")
                },
            ),
        )

        val choices = choices()

        assertTrue(choices.toString(), choices is SolrRunChoices.Refusal)
        assertTrue(choices.toString(), (choices as SolrRunChoices.Refusal).message.contains("401"))
    }

    // --- what the answer says -----------------------------------------------------------------------

    /** An answer reads as the summary the HTTP Client's responses get — the same renderer. */
    fun testAnAnswerIsSummarised() {
        val connection = connectedTo(givenServer())

        val text = answer(connection)

        assertTrue(text, text.startsWith("1 document matched"))
    }

    /** Solr's own error reaches the popup with its code and its message. */
    fun testASolrErrorReachesThePopupWithItsMessage() {
        val connection = connectedTo(
            givenServer(
                select = {
                    respond(it, 400, """{"responseHeader":{"status":400},"error":{"msg":"undefined field categry","code":400}}""")
                },
            ),
        )

        val text = answer(connection)

        assertEquals(SolrBundle.message("code.runQuery.solrError", 400, "undefined field categry"), text)
    }

    /** A response that is not a query result says so, rather than rendering nothing. */
    fun testAnAnswerThatIsNotAQueryResultSaysSo() {
        val connection = connectedTo(
            givenServer(select = { respond(it, 200, """{"responseHeader":{"status":0}}""") }),
        )

        val text = answer(connection)

        assertTrue(text, text.isNotBlank())
        assertFalse(text, text.contains("matched"))
    }
}
