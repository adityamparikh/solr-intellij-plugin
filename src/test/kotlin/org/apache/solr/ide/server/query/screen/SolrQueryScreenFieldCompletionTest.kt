package org.apache.solr.ide.server.query.screen

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase
import org.apache.solr.ide.server.connection.SolrConnection

/**
 * Field names in the console's field inputs, and when the server is asked for them.
 *
 * **What is asserted most is what is not sent**, for the reason the `.http` completion's suite
 * gives: a popup opens while typing, and a read behind it would put a request behind a keystroke.
 */
class SolrQueryScreenFieldCompletionTest : SolrConfigsetTestCase() {

  private var server: HttpServer? = null
  private val requested = CopyOnWriteArrayList<String>()

  private val schema =
    """
    {"responseHeader":{"status":0},
     "schema":{"name":"books","version":1.6,"uniqueKey":"id",
       "fieldTypes":[{"name":"string","class":"solr.StrField"}],
       "fields":[{"name":"id","type":"string"},{"name":"title","type":"string"}],
       "dynamicFields":[{"name":"*_s","type":"string"}]}}
    """
      .trimIndent()

  private fun givenServer(): SolrConnection {
    val started = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    started.createContext("/") { exchange ->
      requested += exchange.requestURI.path
      val bytes = schema.toByteArray()
      exchange.responseHeaders.add("Content-Type", "application/json")
      exchange.sendResponseHeaders(200, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
    }
    started.start()
    server = started
    return SolrConnection("c1", "local", "http://127.0.0.1:${started.address.port}/solr").also {
      connectionSettings.addConnection(it)
    }
  }

  override fun tearDown() {
    try {
      server?.stop(0)
    } finally {
      super.tearDown()
    }
  }

  private fun completionFor(connection: SolrConnection) =
    SolrQueryScreenFieldCompletion(
      project,
      SolrQueryScreenFile(
        connection.id,
        "books",
        SolrQueryScreenState(SolrQueryScreenSpec.bundled),
      ),
      listOf("score"),
    )

  /** A popup that opened by itself asks nothing, and answers from the configsets. */
  fun testAPopupWhileTypingAsksNothing() {
    val completion = completionFor(givenServer())

    completion.known()

    assertEmpty(requested)
  }

  /** An explicit ask reads the collection's fields, once, and remembers them. */
  fun testAnExplicitAskReadsTheCollectionOnce() {
    val completion = completionFor(givenServer())

    val read = runBlocking { completion.read() }
    val again = runBlocking { completion.read() }

    assertEquals(listOf("id", "title", "*_s"), read.map { it.name })
    assertEquals(read, again)
    assertEquals(1, requested.size)
    assertEquals("what was read answers the next popup without asking", read, completion.known())
  }

  /** What a field list takes besides fields is offered with them. */
  fun testSyntaxIsOfferedBesideTheFields() {
    val completion = completionFor(givenServer())

    val offered = completion.getItems("", false, null).map { it.name }

    assertTrue(offered.toString(), "score" in offered)
    assertEmpty(requested)
  }
}
