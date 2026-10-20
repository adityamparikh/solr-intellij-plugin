package org.apache.solr.ide.server.query.screen

import com.intellij.openapi.ui.ComboBox
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.ui.EditorTextField
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/**
 * The form, driven the way a user drives it: typing, choosing, ticking.
 *
 * What is pinned is that every input writes the tab's state, that a choice changing what shows
 * redraws the form, and that the state is the tab's rather than the form's — a second form on the
 * same tab, or a fill from elsewhere, shows what the tab holds.
 */
class SolrQueryScreenFormTest : SolrConfigsetTestCase() {

  private fun tab() =
    SolrQueryScreenFile("a", "books", SolrQueryScreenState(SolrQueryScreenSpec.bundled))

  private fun formFor(file: SolrQueryScreenFile) =
    SolrQueryScreenForm(project, file, testRootDisposable)

  private fun textOf(form: SolrQueryScreenForm, name: String): String =
    when (val input = form.input(name)) {
      is JBTextField -> input.text
      is EditorTextField -> input.text
      is JBTextArea -> input.text
      else -> error("$name is not a text input: $input")
    }

  fun testTheFormStartsWithTheAdminsDefaults() {
    val form = formFor(tab())

    assertEquals("*:*", textOf(form, "q"))
    assertEquals("/select", textOf(form, "qt"))
    assertTrue((form.input("indent") as JBCheckBox).isSelected)
  }

  fun testTypingIntoAnInputWritesTheTab() {
    val file = tab()
    val form = formFor(file)

    (form.input("q") as JBTextField).text = "title:dune"

    assertEquals("title:dune", file.state.value("q"))
  }

  /** A field-naming input is a completing one, and writes the tab the same way. */
  fun testAFieldListCompletesAndWritesTheTab() {
    val file = tab()
    val form = formFor(file)
    val fl = form.input("fl")

    assertTrue("fl should complete field names: $fl", fl is EditorTextField)
    (fl as EditorTextField).text = "id,title"
    assertEquals("id,title", file.state.value("fl"))
  }

  fun testFilterQueriesAreOnePerLine() {
    val file = tab()
    val form = formFor(file)

    (form.input("fq") as JBTextArea).text = "category:books\ninStock:true"

    assertEquals(listOf("category:books", "inStock:true"), file.state.lines("fq"))
  }

  /** Choosing a dismax parser shows its parameters, and only edismax shows edismax's. */
  fun testChoosingAParserShowsItsParameters() {
    val form = formFor(tab())
    assertFalse(form.shownInputs.toString(), "qf" in form.shownInputs)

    @Suppress("UNCHECKED_CAST")
    (form.input("defType") as ComboBox<String>).selectedItem = "dismax"
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    assertTrue(form.shownInputs.toString(), "qf" in form.shownInputs)
    assertFalse(form.shownInputs.toString(), "pf2" in form.shownInputs)
  }

  fun testTheDebugSectionOpensWithItsToggle() {
    val form = formFor(tab())
    assertFalse("debug.explain.structured" in form.shownInputs)

    (form.input("debugQuery") as JBCheckBox).isSelected = true
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    assertTrue(form.shownInputs.toString(), "debug.explain.structured" in form.shownInputs)
  }

  /** Split Right makes a second editor; it shows what the tab holds, not a fresh form. */
  fun testASecondFormShowsTheSameTab() {
    val file = tab()
    (formFor(file).input("q") as JBTextField).text = "author:herbert"

    assertEquals("author:herbert", textOf(formFor(file), "q"))
  }

  /** A fill from the collections tree reaches a form that is already drawn. */
  fun testAFillFromElsewhereRedrawsTheForm() {
    val file = tab()
    val form = formFor(file)

    file.update(file.state.with("q", "title:dune"), source = "the tree")

    assertEquals("title:dune", textOf(form, "q"))
  }
}
