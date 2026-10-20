package org.apache.solr.ide.server.query.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the console's form holds, and the parameters it sends.
 *
 * **Most of what is asserted is what is *not* sent.** Every parameter here changes the answer, so a
 * hidden field's leftover, a parser choice nobody made, or a checkbox that only looks off would
 * each change what Solr runs with nothing on screen saying so.
 */
class SolrQueryScreenStateTest {

  private val blank = SolrQueryScreenState(SolrQueryScreenSpec.bundled)

  @Test
  fun `an untouched form sends the admin's defaults and nothing it leaves blank`() {
    assertEquals(listOf("q" to "*:*", "indent" to "true"), blank.parameters())
  }

  @Test
  fun `a value entered is sent, trimmed`() {
    assertEquals("title:dune", blank.with("q", "  title:dune ").parameters().toMap()["q"])
  }

  @Test
  fun `a choice left at the handler default is not sent`() {
    val sent = blank.parameters().map { it.first }

    assertFalse(sent.toString(), "defType" in sent)
    assertFalse(sent.toString(), "q.op" in sent)
  }

  @Test
  fun `a choice made is sent`() {
    assertEquals("edismax", blank.with("defType", "edismax").parameters().toMap()["defType"])
  }

  @Test
  fun `a checkbox on by default sends false when turned off`() {
    assertEquals("false", blank.with("indent", "false").parameters().toMap()["indent"])
  }

  @Test
  fun `a checkbox off by default sends nothing when off`() {
    val edismax = blank.with("defType", "edismax")

    assertFalse("lowercaseOperators" in edismax.parameters().map { it.first })
    assertEquals(
      "true",
      edismax.with("lowercaseOperators", "true").parameters().toMap()["lowercaseOperators"],
    )
  }

  @Test
  fun `edismax stopwords start on and can be turned off`() {
    val edismax = blank.with("defType", "edismax")

    assertTrue(edismax.isOn("stopwords"))
    assertEquals("false", edismax.with("stopwords", "false").parameters().toMap()["stopwords"])
  }

  @Test
  fun `dismax parameters are hidden until a dismax parser is chosen`() {
    val withQf = blank.with("qf", "title^2")

    assertFalse("qf" in withQf.parameters().map { it.first })
    assertEquals("title^2", withQf.with("defType", "dismax").parameters().toMap()["qf"])
  }

  @Test
  fun `a hidden field keeps its value and is not sent`() {
    val state = blank.with("defType", "edismax").with("pf2", "title").with("defType", "dismax")

    assertFalse("pf2" in state.parameters().map { it.first })
    assertEquals("title", state.value("pf2"))
    assertFalse(state.isVisible(state.spec.field("pf2")!!))
  }

  @Test
  fun `a toggle sends itself and opens its section`() {
    val structured = blank.with("debug.explain.structured", "true")

    assertFalse("debug.explain.structured" in structured.parameters().map { it.first })
    val on = structured.with("debugQuery", "true").parameters()
    assertTrue(
      on.toString(),
      on.containsAll(listOf("debugQuery" to "true", "debug.explain.structured" to "true")),
    )
    assertTrue(
      on.indexOf("debugQuery" to "true") < on.indexOf("debug.explain.structured" to "true")
    )
  }

  @Test
  fun `repeated filter queries are sent one per line, in order, skipping blanks`() {
    val state = blank.withLines("fq", listOf("category:books", "", "  inStock:true "))

    assertEquals(
      listOf("category:books", "inStock:true"),
      state.parameters().filter { it.first == "fq" }.map { it.second },
    )
  }

  @Test
  fun `raw parameters split on the first equals sign and come last`() {
    val state =
      blank.withLines(
        "raw",
        listOf("facet=true", "facet.query=price:[0 TO 10]", "json.facet={a:\"x=y\"}"),
      )
    val sent = state.parameters()

    assertEquals(
      listOf("facet" to "true", "facet.query" to "price:[0 TO 10]", "json.facet" to "{a:\"x=y\"}"),
      sent.takeLast(3),
    )
  }

  @Test
  fun `an ampersand in a raw line is part of its value`() {
    val sent = blank.withLines("raw", listOf("facet.query=a&b=c")).parameters()

    assertEquals("facet.query" to "a&b=c", sent.last())
  }

  @Test
  fun `a raw line with no name is reported, not sent`() {
    val state = blank.withLines("raw", listOf("facet=true", "nonsense", "=orphan", ""))

    assertEquals(listOf("nonsense", "=orphan"), state.rawProblems())
    assertEquals("facet" to "true", state.parameters().last())
  }

  @Test
  fun `the handler is never a parameter and defaults to select`() {
    assertFalse("qt" in blank.parameters().map { it.first })
    assertEquals("/select", blank.handler)
    assertEquals("/select", blank.with("qt", "  ").handler)
    assertEquals("/query", blank.with("qt", " /query ").handler)
  }
}
