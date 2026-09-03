package org.apache.solr.ide.server.topology

import com.intellij.openapi.util.Disposer
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase
import org.apache.solr.ide.server.connection.SolrConnection
import javax.swing.tree.DefaultMutableTreeNode
import org.apache.solr.ide.server.reading.SolrCore
import org.apache.solr.ide.server.reading.SolrIndexContents
import org.apache.solr.ide.server.reading.SolrIndexField
import org.apache.solr.ide.server.reading.SolrIndexSummary
import org.apache.solr.ide.server.transport.SolrResponse
import org.apache.solr.ide.server.reading.SolrServerMode
import org.apache.solr.ide.server.reading.SolrCollection
import org.apache.solr.ide.server.reading.SolrReplica
import org.apache.solr.ide.server.reading.SolrShard
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
    // --- the indexed fields, fetched when their row is expanded ------------------------------------

    private val indexed = SolrIndexContents(
        summary = SolrIndexSummary(numDocs = 3),
        fields = listOf(
            SolrIndexField(name = "id", type = "string", docs = 3),
            SolrIndexField(name = "author_s", type = "string", dynamicBase = "*_s", docs = 3),
        ),
    )

    private fun fieldsRowIn(page: SolrCollectionsPanel): DefaultMutableTreeNode {
        page.render(SolrCollectionsView.Loaded(standaloneRoots))
        val cores = page.treeRoot.getChildAt(0) as DefaultMutableTreeNode
        val books = cores.getChildAt(0) as DefaultMutableTreeNode
        return books.getChildAt(0) as DefaultMutableTreeNode
    }

    /** The row is there and empty until someone opens it. */
    fun testTheFieldsRowStartsEmpty() {
        val row = fieldsRowIn(panel())

        assertEquals("Fields", (row.userObject as SolrTopologyNode).label)
        assertEquals(0, row.childCount)
    }

    fun testFilledFieldsBecomeRows() {
        val page = panel()
        val row = fieldsRowIn(page)

        page.fillFields(row, SolrResponse.Success(indexed))

        assertEquals(2, row.childCount)
        assertEquals("id", ((row.getChildAt(0) as DefaultMutableTreeNode).userObject as SolrTopologyNode).label)
        assertEquals("author_s", ((row.getChildAt(1) as DefaultMutableTreeNode).userObject as SolrTopologyNode).label)
    }

    /**
     * The counts move onto the row that was expanded rather than under it.
     *
     * The reader returns its own "Fields" heading, and adopting its children while taking its detail
     * is what stops a user opening Fields to find Fields.
     */
    fun testTheRowGainsTheFieldCounts() {
        val page = panel()
        val row = fieldsRowIn(page)

        page.fillFields(row, SolrResponse.Success(indexed))

        val detail = (row.userObject as SolrTopologyNode).detail
        assertNotNull(detail)
        assertTrue(detail!!, detail.contains("2 fields"))
        assertTrue(detail, detail.contains("1 from dynamic patterns"))
    }

    /**
     * A failure reading an index is reported inline and leaves the row empty.
     *
     * Filling it with an apology would put a field called "could not be read" in a list of field
     * names, which is worse than an empty row beside a banner that says what happened.
     */
    fun testAFailureReadingFieldsIsReportedAndLeavesTheRowEmpty() {
        val page = panel()
        val row = fieldsRowIn(page)

        page.fillFields(row, SolrResponse.SolrError(404, null))

        assertEquals(0, row.childCount)
        assertNotNull(page.bannerMessage)
        assertTrue(page.bannerMessage!!, page.bannerMessage!!.contains("404"))
    }

    /** A partial answer fills what arrived and says it was partial. */
    fun testAPartialFieldListIsShownAndLabelled() {
        val page = panel()
        val row = fieldsRowIn(page)

        page.fillFields(row, SolrResponse.Partial(indexed, "time allowed exceeded"))

        assertEquals(2, row.childCount)
        assertNotNull(page.bannerMessage)
    }

    /**
     * Expanding a row that already has its fields asks nothing.
     *
     * Nothing changed by the row being collapsed, so re-expanding must not spend a request. Refresh
     * is what asks again.
     */
    fun testReExpandingAFilledRowDoesNotRefetch() {
        connectionSettings.addConnection(connection())
        val page = panel()
        val row = fieldsRowIn(page)
        page.fillFields(row, SolrResponse.Success(indexed))

        page.fieldsRequested(row)

        assertEquals("the rows must be left exactly as they were", 2, row.childCount)
    }

    /** A row that is not a fields row is not a request. */
    fun testExpandingAnOrdinaryRowAsksNothing() {
        connectionSettings.addConnection(connection())
        val page = panel()
        page.render(SolrCollectionsView.Loaded(standaloneRoots))
        val cores = page.treeRoot.getChildAt(0) as DefaultMutableTreeNode

        page.fieldsRequested(cores)

        assertNull(page.bannerMessage)
    }

    // --- which collection a document would go into --------------------------------------------------

    private fun collectionsTreeOn(page: SolrCollectionsPanel) {
        page.render(SolrCollectionsView.Loaded(standaloneRoots))
    }

    /** Nothing selected means nothing to index into, and the action stays out of the way. */
    fun testNoSelectionNamesNoCollection() {
        val page = panel()
        collectionsTreeOn(page)

        assertNull(page.selectedCollection())
    }

    /**
     * A row deeper in the tree still answers with the collection it sits under.
     *
     * Asking a user to click the exact right row would be the plugin being unhelpful on purpose:
     * a field, a shard and a replica all belong to one collection, and that is the one a document
     * would go into.
     */
    fun testARowUnderACollectionNamesThatCollection() {
        val page = panel()
        collectionsTreeOn(page)
        val cores = page.treeRoot.getChildAt(0) as javax.swing.tree.DefaultMutableTreeNode
        val books = cores.getChildAt(0) as javax.swing.tree.DefaultMutableTreeNode
        val fieldsRow = books.getChildAt(0) as javax.swing.tree.DefaultMutableTreeNode

        page.selectPath(javax.swing.tree.TreePath(fieldsRow.path))

        assertEquals("books", page.selectedCollection())
    }

    /** Selecting the core itself names it. */
    fun testSelectingACoreNamesIt() {
        val page = panel()
        collectionsTreeOn(page)
        val cores = page.treeRoot.getChildAt(0) as javax.swing.tree.DefaultMutableTreeNode
        val books = cores.getChildAt(0) as javax.swing.tree.DefaultMutableTreeNode

        page.selectPath(javax.swing.tree.TreePath(books.path))

        assertEquals("books", page.selectedCollection())
    }

    /** A heading belongs to no collection, so it names none. */
    fun testAGroupHeadingNamesNoCollection() {
        val page = panel()
        collectionsTreeOn(page)
        val cores = page.treeRoot.getChildAt(0) as javax.swing.tree.DefaultMutableTreeNode

        page.selectPath(javax.swing.tree.TreePath(cores.path))

        assertNull(page.selectedCollection())
    }

    /** With no connection there is nothing to index into, whatever is selected. */
    fun testIndexingWithNoConnectionDoesNothing() {
        val page = panel()
        collectionsTreeOn(page)

        page.indexTestDocument()

        assertNull(page.bannerMessage)
    }

    // --- the fields row has to be reachable before it has anything in it -------------------------

    private val cloudRoots = SolrTopologyNodes.rootsOf(
        SolrTopology(
            SolrServerMode.SOLR_CLOUD,
            collections = listOf(
                SolrCollection(
                    name = "books",
                    configName = "_default",
                    health = "GREEN",
                    shards = listOf(
                        SolrShard(
                            name = "shard1",
                            range = "80000000-7fffffff",
                            state = "active",
                            health = "GREEN",
                            replicas = listOf(
                                SolrReplica("core_node2", "books_s1_r_n1", "n:8983_solr", "active", "NRT", true),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun fieldsRowUnderTheCollection(page: SolrCollectionsPanel): DefaultMutableTreeNode {
        page.render(SolrCollectionsView.Loaded(cloudRoots))
        val collections = page.treeRoot.getChildAt(0) as DefaultMutableTreeNode
        val books = collections.getChildAt(0) as DefaultMutableTreeNode
        val fields = books.getChildAt(0) as DefaultMutableTreeNode
        assertEquals("Fields", (fields.userObject as SolrTopologyNode).label)
        return fields
    }

    /**
     * **The row is a promise, and a promise nobody can click is not one.**
     *
     * Its children arrive only when it is expanded, so it is built empty — and a tree asked whether
     * an empty node is a leaf says yes, draws no handle, and never fires the expansion the fetch
     * hangs off. The fetch was reachable from a test and unreachable from the tool window.
     */
    fun testTheFieldsRowCanBeExpandedBeforeItHasAnyFields() {
        val page = panel()

        val fields = fieldsRowUnderTheCollection(page)

        assertEquals("the row has no children yet, which is the whole point", 0, fields.childCount)
        assertTrue("the fields row must offer an expand handle", page.willOfferExpansion(fields))
    }

    /**
     * And nothing else grows one it has no use for.
     *
     * Making the tree ask each node rather than count its children is what fixes the row above; a
     * replica answering yes by default would put a handle on every leaf in the tree.
     */
    fun testARowWithNothingUnderItIsStillALeaf() {
        val page = panel()
        page.render(SolrCollectionsView.Loaded(cloudRoots))
        val collections = page.treeRoot.getChildAt(0) as DefaultMutableTreeNode
        val shard = (collections.getChildAt(0) as DefaultMutableTreeNode).getChildAt(1) as DefaultMutableTreeNode
        val replica = shard.getChildAt(0) as DefaultMutableTreeNode

        assertFalse("a replica has nothing under it", page.willOfferExpansion(replica))
    }

    /**
     * Rendering must not open it, which would fetch from every Refresh.
     *
     * [render] expands the rows a user came for, and the fields row becoming expandable puts it in
     * reach of that. Server data moves on request and connection change and on nothing else, so an
     * auto-expanded row here would issue a request nobody asked for on every redraw.
     */
    fun testRenderingDoesNotOpenTheFieldsRow() {
        val page = panel()

        val fields = fieldsRowUnderTheCollection(page)

        assertFalse("rendering must not expand the fields row", page.isExpandedRow(fields))
    }
}
