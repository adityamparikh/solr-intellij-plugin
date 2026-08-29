package org.apache.solr.ide.server.drift

import com.intellij.openapi.util.Disposer
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase
import org.apache.solr.ide.model.SolrConfigsetFacts
import org.apache.solr.ide.model.schema.SolrField
import org.apache.solr.ide.model.schema.SolrFieldType
import org.apache.solr.ide.server.connection.SolrConnection

/**
 * Writing, then finding out what the server actually holds.
 *
 * **The rule under test cannot be checked by looking.** A configset upload returning `status: 0` is
 * not proof the server reflects it — verified against Solr 10.0.0, where an archive lacking
 * `_version_` uploads cleanly, appears in `action=LIST`, and is then refused when a collection is
 * built from it. A view that cleared its diff on the write's own answer would report a deployment
 * that had not happened, and would look exactly like one that had.
 *
 * So the sequence is run against a server that accepts the write and then answers the read with a
 * schema that still differs. Only running the whole chain shows the difference.
 */
class SolrDriftWriteTest : SolrConfigsetTestCase() {

    private var server: HttpServer? = null
    private val requested = mutableListOf<String>()

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

    private fun schemaWith(vararg fields: String) = """
        {"responseHeader":{"status":0},"schema":{"uniqueKey":"id",
         "fields":[${fields.joinToString(",") { """{"name":"$it","type":"string"}""" }}],
         "fieldTypes":[{"name":"string","class":"solr.StrField"}]}}
    """.trimIndent()

    /**
     * A server that takes writes and answers reads with [schemaAfterWrite].
     *
     * @param uploadStatus what the upload answers with
     */
    private fun givenServer(schemaAfterWrite: String, uploadStatus: Int = 200): String {
        val started = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        started.createContext("/") { exchange ->
            val uri = exchange.requestURI
            requested += uri.toString()
            exchange.requestBody.readBytes()
            when {
                uri.path.endsWith("/admin/info/system") ->
                    respond(exchange, 200, """{"responseHeader":{"status":0},"mode":"solrcloud"}""")
                uri.path.endsWith("/admin/collections") && uri.query?.contains("CLUSTERSTATUS") == true ->
                    respond(exchange, 200, """{"responseHeader":{"status":0},"cluster":{"collections":{}}}""")
                uri.path.endsWith("/admin/configs") ->
                    if (uploadStatus == 200) respond(exchange, 200, """{"responseHeader":{"status":0}}""")
                    else respond(exchange, uploadStatus, """{"error":{"msg":"Configset upload refused"}}""")
                uri.path.endsWith("/schema") -> respond(exchange, 200, schemaAfterWrite)
                else -> respond(exchange, 200, """{"responseHeader":{"status":0}}""")
            }
        }
        started.start()
        server = started
        return "http://127.0.0.1:${started.address.port}"
    }

    override fun tearDown() {
        try {
            server?.stop(0)
        } finally {
            super.tearDown()
        }
    }

    private fun connection(url: String) =
        SolrConnection(id = "c1", displayName = "local", baseUrl = url)

    // Carries the field type and unique key the served schema also carries, so the comparison
    // isolates the fields under test rather than reporting the fixture's own omissions as drift.
    private fun repositoryDeclaring(vararg names: String) = SolrConfigsetFacts(
        fields = names.map { SolrField(name = it, type = "string") },
        fieldTypes = listOf(SolrFieldType(name = "string", className = "solr.StrField")),
        uniqueKey = "id",
    )

    private fun writeThenCompare(url: String, repository: SolrConfigsetFacts) = runBlocking {
        panel().writeThenCompare(
            connection(url),
            configsetName = "books",
            collection = "books_prod",
            archive = "zip".toByteArray(),
            repository = repository,
        )
    }

    // --- the rule this whole path exists for --------------------------------------------------------

    /**
     * A write Solr accepted, followed by a read showing the server still differs.
     *
     * The case that must not clear the table. Solr said `status: 0` to both the upload and the
     * reload; the schema it then serves is missing a field the configset declares.
     */
    fun testASuccessfulWriteThatChangedNothingStillReportsDrift() {
        val url = givenServer(schemaAfterWrite = schemaWith("id"))

        val view = writeThenCompare(url, repositoryDeclaring("id", "title"))

        assertTrue(view.toString(), view is SolrDriftView.Compared)
        val compared = view as SolrDriftView.Compared
        assertFalse("the server did not take it, and the view must say so", compared.drift.isClean)
        assertEquals(listOf("title"), compared.drift.entries.map { it.name })
    }

    /** A write the server did take reports clean — from the read, not from the write. */
    fun testAWriteTheServerTookReportsClean() {
        val url = givenServer(schemaAfterWrite = schemaWith("id", "title"))

        val view = writeThenCompare(url, repositoryDeclaring("id", "title"))

        assertTrue(view.toString(), view is SolrDriftView.Compared)
        assertTrue((view as SolrDriftView.Compared).drift.isClean)
    }

    /** The comparison comes from a read that happened, and the read is visible in the traffic. */
    fun testTheComparisonFollowsAFreshRead() {
        val url = givenServer(schemaAfterWrite = schemaWith("id"))

        writeThenCompare(url, repositoryDeclaring("id"))

        assertTrue("expected an upload: $requested", requested.any { it.contains("action=UPLOAD") })
        assertTrue("expected a reload: $requested", requested.any { it.contains("action=RELOAD") })
        assertTrue("expected a schema read after the writes: $requested", requested.any { it.endsWith("/schema") })
    }

    // --- when the write does not land ---------------------------------------------------------------

    /**
     * A refused upload is reported, and nothing is compared.
     *
     * Comparing after a failed write would report the state the server was already in as though the
     * write had produced it.
     */
    fun testARefusedUploadIsReportedAndNothingIsCompared() {
        val url = givenServer(schemaAfterWrite = schemaWith("id"), uploadStatus = 400)

        val view = writeThenCompare(url, repositoryDeclaring("id", "title"))

        assertTrue(view.toString(), view is SolrDriftView.Failed)
        assertTrue((view as SolrDriftView.Failed).message, view.message.contains("Configset upload refused"))
        assertFalse("no read may follow a failed write: $requested", requested.any { it.endsWith("/schema") })
    }

    /** A refused upload stops before the reload, so the failure named is the one that happened. */
    fun testARefusedUploadDoesNotReload() {
        val url = givenServer(schemaAfterWrite = schemaWith("id"), uploadStatus = 400)

        writeThenCompare(url, repositoryDeclaring("id"))

        assertFalse("the reload must not run: $requested", requested.any { it.contains("action=RELOAD") })
    }
}
