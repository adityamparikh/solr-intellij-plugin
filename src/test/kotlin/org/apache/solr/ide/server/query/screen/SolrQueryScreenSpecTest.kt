package org.apache.solr.ide.server.query.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The form's definition, read from the file transcribed from Solr's own query page.
 *
 * **What is pinned is what the Admin UI shows, in its order**, because the console is the Admin's
 * form and a user moving between the two should not have to hunt for a parameter. The refusals are
 * pinned too: the file is hand-edited, and a typo there should fail a build naming the entry rather
 * than draw a form that quietly lacks a field.
 */
class SolrQueryScreenSpecTest {

  private val spec = SolrQueryScreenSpec.bundled

  @Test
  fun `the four sections arrive in the admin's order`() {
    assertEquals(listOf("common", "debugQuery", "defType", "raw"), spec.sections.map { it.id })
  }

  @Test
  fun `common holds the admin's parameters in the admin's order`() {
    assertEquals(
      listOf(
        "qt",
        "q",
        "q.op",
        "fq",
        "sort",
        "start",
        "rows",
        "fl",
        "df",
        "useParams",
        "wt",
        "indent",
      ),
      spec.sections.first().fields.map { it.name },
    )
  }

  @Test
  fun `the handler is a path, defaulting to select`() {
    val handler = spec.field("qt")!!
    assertEquals(SolrQueryScreenFieldKind.HANDLER, handler.kind)
    assertEquals("/select", handler.default)
  }

  @Test
  fun `the debug section opens with its toggle`() {
    assertEquals("debugQuery", spec.sections.single { it.id == "debugQuery" }.toggle)
  }

  @Test
  fun `edismax-only parameters show only for edismax`() {
    listOf("uf", "pf2", "pf3", "ps2", "ps3", "boost", "stopwords", "lowercaseOperators").forEach {
      assertEquals(it, mapOf("defType" to listOf("edismax")), spec.field(it)!!.showWhen)
    }
    assertEquals(mapOf("defType" to listOf("dismax", "edismax")), spec.field("qf")!!.showWhen)
  }

  /**
   * A checkbox's starting state must be what Solr does when the parameter is absent, or the box
   * misstates the request: one shown off while Solr treats it as on can never be turned off.
   * `indent` is the exception the admin form makes, and the console copies it.
   */
  @Test
  fun `every checkbox starts where Solr's own default is`() {
    val checkboxes =
      spec.fields
        .filter { it.kind == SolrQueryScreenFieldKind.CHECKBOX }
        .associate { it.name to (it.default == "true") }

    assertEquals(
      mapOf(
        "indent" to true,
        "debug.explain.structured" to false,
        "stopwords" to true,
        "lowercaseOperators" to false,
      ),
      checkboxes,
    )
  }

  @Test
  fun `a parser choice defaults to the handler's own`() {
    assertEquals("", spec.field("defType")!!.default)
    assertEquals("", spec.field("q.op")!!.default)
  }

  @Test
  fun `the inputs that name fields complete them`() {
    assertEquals(
      listOf("sort", "fl", "df", "qf", "pf", "pf2", "pf3"),
      spec.fields.filter { it.completesFields }.map { it.name },
    )
    assertEquals(listOf("*", "score", "[docid]"), spec.field("fl")!!.alsoComplete)
    assertFalse(spec.field("q")!!.completesFields)
  }

  @Test
  fun `every section is found from a field it holds`() {
    assertEquals("defType", spec.sectionOf("pf2")!!.id)
    assertEquals("debugQuery", spec.sectionOf("debugQuery")!!.id)
  }

  @Test
  fun `an unknown kind is refused naming the field`() {
    val failure =
      assertThrows(IllegalArgumentException::class.java) {
        SolrQueryScreenSpec.parse(definition("""{"name":"q","kind":"slider"}"""))
      }
    assertTrue(failure.message, failure.message!!.contains("q"))
  }

  @Test
  fun `a choice with nothing to choose is refused`() {
    assertThrows(IllegalArgumentException::class.java) {
      SolrQueryScreenSpec.parse(definition("""{"name":"wt","kind":"choice"}"""))
    }
  }

  @Test
  fun `a name defined twice is refused`() {
    val failure =
      assertThrows(IllegalArgumentException::class.java) {
        SolrQueryScreenSpec.parse(
          definition("""{"name":"q","kind":"text"},{"name":"q","kind":"text"}""")
        )
      }
    assertTrue(failure.message, failure.message!!.contains("q"))
  }

  @Test
  fun `a condition on a parameter the form lacks is refused`() {
    assertThrows(IllegalArgumentException::class.java) {
      SolrQueryScreenSpec.parse(
        definition("""{"name":"qf","kind":"text","showWhen":{"defType":["dismax"]}}""")
      )
    }
  }

  @Test
  fun `text that is not json is refused`() {
    assertThrows(IllegalArgumentException::class.java) { SolrQueryScreenSpec.parse("not json") }
  }

  private fun definition(fields: String) =
    """{"source":"test","sections":[{"id":"s","label":"s","fields":[$fields]}]}"""
}
