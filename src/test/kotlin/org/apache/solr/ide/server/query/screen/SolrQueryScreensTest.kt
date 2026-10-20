package org.apache.solr.ide.server.query.screen

import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase
import org.apache.solr.ide.server.connection.SolrConnection

/**
 * The console's tabs: one per connection and collection, closed with their connection.
 *
 * Only which files are open is asserted here. What an editor of one draws is the form's and the
 * editor's own suites, built through the provider so the test does not depend on the light fixture's
 * editor manager creating custom editors.
 */
class SolrQueryScreensTest : SolrConfigsetTestCase() {

    private val local = SolrConnection("a", "Local Solr", "http://127.0.0.1:1/solr")
    private val other = SolrConnection("b", "Other Solr", "http://127.0.0.1:2/solr")

    private val screens get() = SolrQueryScreens.getInstance(project)

    override fun tearDown() {
        try {
            FileEditorManagerEx.getInstanceEx(project).closeAllFiles()
        } finally {
            super.tearDown()
        }
    }

    fun testTheTabIsNamedForItsCollection() {
        connectionSettings.addConnection(local)

        assertEquals("Query: books", screens.open(local, "books").name)
    }

    /** Opening the same collection again focuses the open tab rather than adding a second. */
    fun testOpeningTwiceKeepsOneTab() {
        connectionSettings.addConnection(local)

        val first = screens.open(local, "books")
        val second = screens.open(local, "books")

        assertSame(first, second)
        assertEquals(1, screens.openFiles().size)
    }

    /** Two servers can hold a collection of the same name, and each is its own tab. */
    fun testEachConnectionGetsItsOwnTab() {
        connectionSettings.addConnection(local)
        connectionSettings.addConnection(other)

        screens.open(local, "books")
        screens.open(other, "books")

        assertEquals(listOf("a", "b"), screens.openFiles().map { it.connectionId }.sorted())
    }

    /** A fill that comes with the request replaces an open tab's form: the gesture names the query. */
    fun testOpeningWithAFillReplacesTheForm() {
        connectionSettings.addConnection(local)
        val first = screens.open(local, "books")
        first.update(first.state.with("q", "author:herbert"), source = "user")

        val filled = screens.open(local, "books", SolrQueryScreenState(SolrQueryScreenSpec.bundled).with("q", "title:dune"))

        assertEquals("title:dune", filled.state.value("q"))
    }

    /** Opening without a fill leaves what the user typed. */
    fun testOpeningWithoutAFillKeepsTheForm() {
        connectionSettings.addConnection(local)
        val first = screens.open(local, "books")
        first.update(first.state.with("q", "author:herbert"), source = "user")

        assertEquals("author:herbert", screens.open(local, "books").state.value("q"))
    }

    /** A removed connection closes its tabs and only its tabs. */
    fun testRemovingAConnectionClosesOnlyItsTabs() {
        connectionSettings.addConnection(local)
        connectionSettings.addConnection(other)
        screens.open(local, "books")
        screens.open(other, "books")

        connectionSettings.removeConnection("a")

        assertEquals(listOf("b"), screens.openFiles().map { it.connectionId })
    }
}
