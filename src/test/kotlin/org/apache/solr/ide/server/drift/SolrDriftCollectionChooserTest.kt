package org.apache.solr.ide.server.drift

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase
import org.apache.solr.ide.server.connection.SolrConnection

/**
 * The drift view's collection chooser, which offers what the selected server holds.
 *
 * **Typing a collection name nobody showed you is where the view felt broken.** The field was a bare
 * text box: Compare stayed disabled until something was typed, nothing said what to type, and the
 * list of collections lived one tab over. A user pointed at a server holding one collection was left
 * to guess its name. The chooser now asks the server when it is opened — on request, never otherwise —
 * and still accepts a typed name, because a collection the listing missed is still worth comparing.
 */
class SolrDriftCollectionChooserTest : SolrConfigsetTestCase() {

    private var server: HttpServer? = null
    private val requested = mutableListOf<String>()

    override fun tearDown() {
        try {
            server?.stop(0)
        } finally {
            super.tearDown()
        }
    }

    private fun panel(): SolrDriftPanel {
        val created = SolrDriftPanel(project)
        Disposer.register(testRootDisposable, created)
        return created
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    /** A SolrCloud server holding `shows`, built from `_default`, and `books`, built from `books`. */
    private fun givenCloudServer(): String {
        val started = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        started.createContext("/") { exchange ->
            requested += "${exchange.requestMethod} ${exchange.requestURI}"
            val path = exchange.requestURI.path
            when {
                path.endsWith("/admin/info/system") ->
                    respond(exchange, 200, """{"responseHeader":{"status":0},"mode":"solrcloud"}""")
                path.endsWith("/admin/collections") -> respond(
                    exchange,
                    200,
                    """{"responseHeader":{"status":0},"cluster":{"collections":{
                        "shows":{"configName":"_default","shards":{}},
                        "books":{"configName":"books","shards":{}}}}}""",
                )
                else -> respond(exchange, 404, """{"error":{"msg":"not here"}}""")
            }
        }
        started.start()
        server = started
        return "http://127.0.0.1:${started.address.port}/solr"
    }

    private fun connectTo(url: String) {
        connectionSettings.addConnection(SolrConnection("c1", "local", url))
    }

    private fun SolrDriftPanel.openCollectionChooser() {
        collectionChooser.firePopupMenuWillBecomeVisible()
        val load = collectionLoad
        PlatformTestUtil.waitWithEventsDispatching(
            "the collection list was never read",
            { load == null || load.isCompleted },
            10,
        )
    }

    /** Opening the chooser lists the server's collections, in order. */
    fun testOpeningTheChooserOffersTheServersCollections() {
        connectTo(givenCloudServer())
        val page = panel()

        page.openCollectionChooser()

        assertEquals(listOf("books", "shows"), page.offeredCollections)
    }

    /**
     * Each collection says which configset the server built it from.
     *
     * The comparison a user most often runs by mistake is against a collection that was never
     * created from the configset they chose — `shows` from `_default`, compared against `products`,
     * reports a hundred and fifty declarations only the server has. Saying so beside the name is the
     * cheapest way to make that visible before pressing Compare.
     */
    fun testEachCollectionNamesTheConfigsetItWasBuiltFrom() {
        connectTo(givenCloudServer())
        val page = panel()

        page.openCollectionChooser()

        assertTrue(page.renderedCollection("shows"), page.renderedCollection("shows").contains("_default"))
    }

    /** Opening the list does not throw away what was typed. */
    fun testOpeningTheChooserKeepsATypedName() {
        connectTo(givenCloudServer())
        val page = panel()
        page.setCollection("shows_alias")

        page.openCollectionChooser()

        assertEquals("shows_alias", page.collectionText())
    }

    /** Choosing a collection from the list is enough to compare it; nothing need be typed. */
    fun testAChosenCollectionIsTheOneCompared() {
        connectTo(givenCloudServer())
        myFixture.addFileToProject("books/conf/managed-schema.xml", "<schema name=\"books\"/>")
        myFixture.addFileToProject("books/conf/solrconfig.xml", "<config/>")
        val page = panel()
        val configsets = page.configsetLoad
        PlatformTestUtil.waitWithEventsDispatching("configsets never read", { configsets?.isCompleted != false }, 10)
        page.openCollectionChooser()

        page.collectionChooser.selectedItem = "shows"

        assertEquals("shows", page.collectionText())
        assertTrue("a configset, a chosen collection and a connection are everything", page.canAct())
    }

    /**
     * Opening the chooser reads the topology and nothing else — and never writes.
     *
     * The chooser is a question asked of the server, so it may only ask.
     */
    fun testOpeningTheChooserOnlyReads() {
        connectTo(givenCloudServer())
        val page = panel()

        page.openCollectionChooser()

        assertTrue(requested.toString(), requested.isNotEmpty())
        assertTrue(requested.toString(), requested.all { it.startsWith("GET ") })
    }

    /** With no connection there is nobody to ask, and nothing is asked. */
    fun testWithNoConnectionTheChooserAsksNothing() {
        val page = panel()

        page.collectionChooser.firePopupMenuWillBecomeVisible()

        assertNull(page.collectionLoad)
        assertEmpty(page.offeredCollections)
    }

    /**
     * A server that cannot be reached leaves the chooser as it was.
     *
     * The failure is Compare's to report, in the banner, where it names what went wrong. A chooser
     * that emptied itself or raised an error would be reporting the same thing in a worse place.
     */
    fun testAnUnreachableServerLeavesTheChooserAsItWas() {
        connectTo("http://127.0.0.1:1/solr")
        val page = panel()
        page.setCollection("shows")

        page.openCollectionChooser()

        assertEmpty(page.offeredCollections)
        assertEquals("shows", page.collectionText())
        assertNull(page.bannerMessage)
    }
}
