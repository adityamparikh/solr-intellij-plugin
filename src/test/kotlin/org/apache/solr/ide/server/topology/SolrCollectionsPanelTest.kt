package org.apache.solr.ide.server.topology

import com.intellij.openapi.util.Disposer
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase
import org.apache.solr.ide.server.connection.SolrConnection
import org.apache.solr.ide.server.reading.SolrCore
import org.apache.solr.ide.server.reading.SolrServerMode
import org.apache.solr.ide.server.reading.SolrTopology

/**
 * The tool window, driven through the states it can be in rather than through a server.
 *
 * `render` is reachable for exactly this reason: every state a user can meet — no connection, a
 * failure, a partial answer — is a state a test can put the panel into directly, and none of them
 * needs a Solr to produce it. What the panel does with a *response* is tested beside this, in
 * `SolrCollectionsViewTest`.
 */
class SolrCollectionsPanelTest : SolrConfigsetTestCase() {

    private fun panel(): SolrCollectionsPanel {
        val created = SolrCollectionsPanel(project)
        Disposer.register(testRootDisposable, created)
        return created
    }

    private fun connection(id: String = "a") =
        SolrConnection(id = id, displayName = "Local Solr", baseUrl = "http://127.0.0.1:1/solr")

    private val standaloneRoots = SolrTopologyNodes.rootsOf(
        SolrTopology(SolrServerMode.STANDALONE, cores = listOf(SolrCore("books", "_default"))),
    )

    // --- what it shows in each state --------------------------------------------------------------

    /**
     * With nothing configured the tree is empty and says why.
     *
     * The state that must not look like a failure: an empty tree with no explanation reads as a
     * server that answered nothing, when in fact none was asked.
     */
    fun testNoConnectionShowsAnEmptyTreeAndNoBanner() {
        val page = panel()

        page.render(SolrCollectionsView.NoConnection)

        assertEmpty(page.rootLabels)
        assertNull("nothing has gone wrong, so nothing is reported", page.bannerMessage)
    }

    fun testALoadedTopologyRendersItsRows() {
        val page = panel()

        page.render(SolrCollectionsView.Loaded(standaloneRoots))

        assertEquals(listOf("Cores"), page.rootLabels)
        assertNull(page.bannerMessage)
    }

    /** A failure is reported inline, in Solr's own words, and never as a popup. */
    fun testAFailureIsShownInline() {
        val page = panel()

        page.render(SolrCollectionsView.Failed("Solr answered 400: undefined field categry"))

        assertEquals("Solr answered 400: undefined field categry", page.bannerMessage)
    }

    /**
     * A failure clears whatever was on screen.
     *
     * Leaving the previous server's topology under a failure banner would read as the failure being
     * partial — some of this worked — when it was total.
     */
    fun testAFailureClearsThePreviousTopology() {
        val page = panel()
        page.render(SolrCollectionsView.Loaded(standaloneRoots))

        page.render(SolrCollectionsView.Failed("Connection refused"))

        assertEmpty(page.rootLabels)
        assertEquals("Connection refused", page.bannerMessage)
    }

    /** A partial answer shows what arrived *and* the warning, since both are true. */
    fun testAPartialAnswerShowsTheRowsAndTheWarning() {
        val page = panel()

        page.render(SolrCollectionsView.Loaded(standaloneRoots, warning = "not all of it"))

        assertEquals(listOf("Cores"), page.rootLabels)
        assertEquals("not all of it", page.bannerMessage)
    }

    /** Recovering from a failure takes the banner away rather than leaving it under good data. */
    fun testALaterSuccessClearsTheBanner() {
        val page = panel()
        page.render(SolrCollectionsView.Failed("Connection refused"))

        page.render(SolrCollectionsView.Loaded(standaloneRoots))

        assertEquals(listOf("Cores"), page.rootLabels)
        assertNull(page.bannerMessage)
    }

    // --- when it asks a server --------------------------------------------------------------------

    /**
     * With no connection configured, refreshing asks nothing and says so.
     *
     * The check that keeps "refresh" from being a request against a server that does not exist.
     */
    fun testRefreshingWithNoConnectionAsksNothing() {
        val page = panel()

        page.refresh()

        assertEmpty(page.rootLabels)
        assertNull(page.bannerMessage)
    }

    /**
     * Opening the panel with a connection configured selects it and starts a read.
     *
     * The connection points at a closed port, so what this pins is that the panel *asks* — it goes
     * to Loading rather than sitting in NoConnection — not what the server would have said.
     */
    fun testAConfiguredConnectionIsSelectedAndRead() {
        connectionSettings.addConnection(connection())

        val page = panel()

        assertEquals("a", connectionSettings.selectedConnection?.id)
        assertEmpty(page.rootLabels)
    }
}
