package org.apache.solr.ide.server.query

import com.intellij.openapi.util.Disposer
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase
import org.apache.solr.ide.server.connection.SolrConnection
import org.apache.solr.ide.server.topology.SolrCollectionsPanel
import org.apache.solr.ide.server.transport.SolrResponse

/**
 * A collection's fields, read from the server once and then remembered.
 *
 * **What is asserted most is what is *not* sent.** Completion asks for these on every popup, so a
 * cache that forgot would put a request behind every keystroke of a session that had already paid for
 * one; and one that never forgot would keep offering a server's fields after the connection was
 * repointed somewhere else.
 */
class SolrCollectionFieldsTest : SolrConfigsetTestCase() {

    private var server: HttpServer? = null
    private val requested = CopyOnWriteArrayList<String>()

    private val schema = """
        {"responseHeader":{"status":0},
         "schema":{"name":"books","version":1.6,"uniqueKey":"id",
           "fieldTypes":[{"name":"string","class":"solr.StrField"}],
           "fields":[{"name":"id","type":"string"},{"name":"title","type":"string"}],
           "dynamicFields":[{"name":"*_s","type":"string"}]}}
    """.trimIndent()

    private fun givenServer(status: Int = 200, body: String = schema): SolrConnection {
        val started = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        started.createContext("/") { exchange ->
            requested += exchange.requestURI.path
            respond(exchange, status, body)
        }
        started.start()
        server = started
        return SolrConnection(id = "c1", displayName = "local", baseUrl = "http://127.0.0.1:${started.address.port}/solr")
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

    private val fields get() = SolrCollectionFields.getInstance(project)

    private fun fetch(connection: SolrConnection, collection: String = "books") =
        runBlocking { fields.fetch(connection, collection) }

    fun testAReadOffersTheCollectionsFieldsAndPatterns() {
        val connection = givenServer()

        val read = fetch(connection) as SolrResponse.Success

        assertEquals(listOf("id", "title", "*_s"), read.value.map { it.name })
        assertTrue("a pattern is marked as one", read.value.single { it.name == "*_s" }.dynamic)
    }

    /** Each entry says which collection on which server it came from, as a configset's entries do. */
    fun testEachFieldNamesTheCollectionAndServerItCameFrom() {
        val connection = givenServer()

        val read = fetch(connection) as SolrResponse.Success

        assertEquals("books · local", read.value.first().source)
    }

    /** One request, for the schema alone: completion has no use for the version beside it. */
    fun testAReadAsksForTheSchemaOnly() {
        fetch(givenServer())

        assertEquals(listOf("/solr/books/schema"), requested)
    }

    fun testAReadIsRememberedForThatConnectionAndCollection() {
        val connection = givenServer()
        fetch(connection)

        assertEquals(3, fields.cached(connection, "books")?.size)
        assertNull("another collection has not been read", fields.cached(connection, "films"))
    }

    /** A failure is not an answer, so it is not remembered: the next explicit ask tries again. */
    fun testAFailedReadIsNotRemembered() {
        val connection = givenServer(status = 500, body = """{"error":{"msg":"boom","code":500}}""")

        assertFalse(fetch(connection) is SolrResponse.Success)
        assertNull(fields.cached(connection, "books"))
    }

    /**
     * Editing the connection list forgets everything, because an edited connection keeps its id.
     *
     * Repointing `local` from one server to another is the ordinary case, and a cache keyed by the id
     * alone would go on offering the first server's fields as the second's.
     */
    fun testChangingTheConnectionsForgetsWhatWasRead() {
        val connection = givenServer()
        fetch(connection)

        connectionSettings.addConnection(connection.copy(baseUrl = "http://127.0.0.1:1/solr"))

        assertNull(fields.cached(connection, "books"))
    }

    /**
     * Refreshing the collections view forgets too.
     *
     * The one gesture a user has for saying "what you last read is no longer true" — after reloading
     * a collection by hand, say — and completion is one of the things that last read.
     */
    fun testRefreshingTheCollectionsViewForgetsWhatWasRead() {
        val connection = givenServer()
        connectionSettings.addConnection(connection)
        fetch(connection)
        val view = SolrCollectionsPanel(project).also { Disposer.register(testRootDisposable, it) }

        view.refresh()

        assertNull(fields.cached(connection, "books"))
    }
}
