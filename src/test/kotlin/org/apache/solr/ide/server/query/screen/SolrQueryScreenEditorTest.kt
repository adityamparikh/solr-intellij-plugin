package org.apache.solr.ide.server.query.screen

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.testFramework.PlatformTestUtil
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Job
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase
import org.apache.solr.ide.server.connection.SolrConnection

/**
 * The console tab against a server: what it sends, and what it shows of what came back.
 *
 * The server is embedded rather than a container, as in the query runner's suite: what is under
 * test is the request and the display of each kind of answer, and the contract tier owns Solr's own
 * wire format.
 */
class SolrQueryScreenEditorTest : SolrConfigsetTestCase() {

  private data class Received(val method: String, val path: String, val body: String)

  private var server: HttpServer? = null
  private val received = CopyOnWriteArrayList<Received>()
  private val release = CountDownLatch(1)

  private val oneDocument =
    """
    {"responseHeader":{"status":0,"QTime":7},
     "response":{"numFound":1,"start":0,"docs":[{"id":"1","category":"books"}]}}
    """
      .trimIndent()

  private fun givenServer(
    answer: (HttpExchange) -> Unit = { respond(it, 200, "application/json", oneDocument) }
  ): SolrConnection {
    val started = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    // A thread per request: the cancellation test holds one request open while sending another,
    // which the server's default single dispatcher thread would queue behind the first.
    started.executor = Executors.newCachedThreadPool()
    started.createContext("/") { exchange ->
      received +=
        Received(
          exchange.requestMethod,
          exchange.requestURI.path,
          exchange.requestBody.readBytes().decodeToString(),
        )
      answer(exchange)
    }
    started.start()
    server = started
    return SolrConnection("c1", "local", "http://127.0.0.1:${started.address.port}/solr").also {
      connectionSettings.addConnection(it)
    }
  }

  private fun respond(exchange: HttpExchange, status: Int, contentType: String, body: String) {
    val bytes = body.toByteArray()
    exchange.responseHeaders.add("Content-Type", contentType)
    exchange.sendResponseHeaders(status, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
  }

  override fun tearDown() {
    try {
      release.countDown()
      server?.stop(0)
    } finally {
      super.tearDown()
    }
  }

  private fun editorFor(
    connectionId: String,
    state: SolrQueryScreenState = SolrQueryScreenState(SolrQueryScreenSpec.bundled),
  ): SolrQueryScreenEditor {
    val editor =
      SolrQueryScreenEditorProvider()
        .createEditor(project, SolrQueryScreenFile(connectionId, "books", state))
        as SolrQueryScreenEditor
    Disposer.register(testRootDisposable, editor)
    return editor
  }

  private fun await(job: Job?) {
    checkNotNull(job) { "nothing was sent" }
    PlatformTestUtil.waitWithEventsDispatching("the query never finished", { job.isCompleted }, 10)
  }

  // --- what it sends
  // ------------------------------------------------------------------------------

  /** A form POST to the collection's handler, its values encoded one by one, `echoParams` last. */
  fun testTheQueryIsAFormPostWithEchoParamsLast() {
    val connection = givenServer()
    val editor =
      editorFor(
        connection.id,
        SolrQueryScreenState(SolrQueryScreenSpec.bundled).with("q", "title:\"dune & sons\""),
      )

    await(editor.execute())

    val request = received.single()
    assertEquals("POST", request.method)
    assertEquals("/solr/books/select", request.path)
    assertTrue(request.body, request.body.startsWith("q=title%3A%22dune+%26+sons%22&"))
    assertTrue(request.body, request.body.endsWith("&echoParams=all"))
  }

  /** The old `qt` form is refused with a reason, and nothing reaches the server. */
  fun testABareQtIsRefusedAndNothingIsSent() {
    val connection = givenServer()
    val editor =
      editorFor(
        connection.id,
        SolrQueryScreenState(SolrQueryScreenSpec.bundled).with("qt", "dismax"),
      )

    assertNull(editor.execute())

    assertEmpty(received)
    assertTrue(editor.results.statusText, editor.results.statusText.contains("handleSelect"))
  }

  fun testARemovedConnectionSendsNothing() {
    givenServer()
    val editor = editorFor("gone")

    assertNull(editor.execute())

    assertEmpty(received)
    assertTrue(editor.results.statusText, editor.results.statusText.contains("no longer exists"))
  }

  // --- what it shows
  // ------------------------------------------------------------------------------

  fun testAJsonAnswerIsSummarisedAndShownAsSent() {
    val connection = givenServer()
    val editor = editorFor(connection.id)

    await(editor.execute())

    assertTrue(
      editor.results.shownUrl,
      editor.results.shownUrl.startsWith("${connection.baseUrl}/books/select?q="),
    )
    assertTrue(
      editor.results.summaryText,
      editor.results.summaryText.contains("1 document matched"),
    )
    assertEquals(oneDocument, editor.results.responseText)
    assertFalse(editor.results.showsResponse)
  }

  /** An XML answer has no table; it is shown as sent, with the Response tab in front. */
  fun testAnXmlAnswerIsShownRaw() {
    val xml = "<response><lst name=\"responseHeader\"><int name=\"status\">0</int></lst></response>"
    val connection = givenServer { respond(it, 200, "application/xml", xml) }
    val editor = editorFor(connection.id)

    await(editor.execute())

    assertEquals(xml, editor.results.responseText)
    assertTrue(editor.results.showsResponse)
  }

  /**
   * The servlet's HTML 404 for a mistyped collection is reported with its status, page underneath.
   */
  fun testAnHtmlErrorPageIsReportedWithItsStatus() {
    val connection = givenServer {
      respond(it, 404, "text/html", "<html><body>Not Found</body></html>")
    }
    val editor = editorFor(connection.id)

    await(editor.execute())

    assertTrue(editor.results.statusText, editor.results.statusText.contains("404"))
    assertTrue(editor.results.responseText.contains("Not Found"))
  }

  /** A document refuses `\r`, and an error page arrives with `\r\n`. */
  fun testABodyWithCarriageReturnsIsShown() {
    val connection = givenServer { respond(it, 500, "text/plain", "line one\r\nline two") }
    val editor = editorFor(connection.id)

    await(editor.execute())

    assertEquals("line one\nline two", editor.results.responseText)
  }

  // --- one run per tab
  // ----------------------------------------------------------------------------

  fun testASecondExecuteCancelsTheFirst() {
    val calls = AtomicInteger()
    val connection = givenServer { exchange ->
      if (calls.incrementAndGet() == 1) release.await(10, TimeUnit.SECONDS)
      respond(exchange, 200, "application/json", oneDocument)
    }
    val editor = editorFor(connection.id)

    val first = checkNotNull(editor.execute())
    PlatformTestUtil.waitWithEventsDispatching(
      "the first request never arrived",
      { calls.get() == 1 },
      10,
    )
    val second = editor.execute()

    assertTrue("a second Execute must cancel the first", first.isCancelled)
    await(second)
    assertTrue(
      editor.results.summaryText,
      editor.results.summaryText.contains("1 document matched"),
    )
  }

  fun testClosingTheTabCancelsTheQuery() {
    val connection = givenServer { exchange ->
      release.await(10, TimeUnit.SECONDS)
      respond(exchange, 200, "application/json", oneDocument)
    }
    val editor = editorFor(connection.id)
    val run = checkNotNull(editor.execute())
    PlatformTestUtil.waitWithEventsDispatching(
      "the request never arrived",
      { received.isNotEmpty() },
      10,
    )

    Disposer.dispose(editor)

    assertTrue("closing the tab must cancel its query", run.isCancelled)
  }

  // --- the bridge to the HTTP Client
  // --------------------------------------------------------------

  fun testOpenAsHttpWritesTheTemplatedRequest() {
    val connection = givenServer()
    val editor =
      editorFor(connection.id, SolrQueryScreenState(SolrQueryScreenSpec.bundled).with("q", "a&b"))

    val scratch = checkNotNull(editor.openAsHttp()) { "no scratch file was opened" }
    try {
      val text = VfsUtilCore.loadText(scratch)
      assertTrue(text, text.contains("POST {{solrUrl}}/{{collection}}/select"))
      assertTrue(text, text.contains("q=a%26b"))
      assertFalse(text, text.contains("127.0.0.1"))
      assertEmpty("opening as .http sends nothing", received)
    } finally {
      FileEditorManager.getInstance(project).closeFile(scratch)
      WriteAction.run<Throwable> { scratch.delete(this) }
    }
  }
}
