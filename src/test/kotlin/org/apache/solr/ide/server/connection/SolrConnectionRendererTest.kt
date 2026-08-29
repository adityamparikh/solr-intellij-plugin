package org.apache.solr.ide.server.connection

import com.intellij.ui.CollectionListModel
import com.intellij.ui.components.JBList
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/**
 * What a row says.
 *
 * Worth pinning because a renderer fails quietly: wrong text is still text, and a row that reads
 * "Local Solr" for three different servers looks perfectly healthy while making the list useless.
 */
class SolrConnectionRendererTest : SolrConfigsetTestCase() {

    private fun rowFor(connection: SolrConnection): String {
        val renderer = SolrConnectionRenderer()
        val list = JBList(CollectionListModel(connection))
        renderer.getListCellRendererComponent(list, connection, 0, false, false)
        return renderer.toString()
    }

    /** The label the user chose, and the URL that distinguishes it from the next one. */
    fun testARowShowsItsNameAndItsUrl() {
        val row = rowFor(SolrConnection("a", "Local Solr", "http://localhost:8983/solr"))

        assertTrue(row, row.contains("Local Solr"))
        assertTrue(row, row.contains("http://localhost:8983/solr"))
    }

    /** A connection that was never named shows its URL once, not twice. */
    fun testAnUnnamedRowDoesNotRepeatItsUrl() {
        val url = "http://localhost:8983/solr"
        val row = rowFor(SolrConnection("a", url, url))

        assertEquals("the url should appear once", url, row.trim())
    }

    /** The user a connection authenticates as, since two rows can differ only by that. */
    fun testARowShowsItsUsername() {
        val row = rowFor(SolrConnection("a", "Prod", "https://solr.example.com/solr", username = "admin"))

        assertTrue(row, row.contains("admin"))
    }

    fun testAnAnonymousRowNamesNoUser() {
        val row = rowFor(SolrConnection("a", "Prod", "https://solr.example.com/solr"))

        assertFalse(row, row.contains("null"))
    }
}
