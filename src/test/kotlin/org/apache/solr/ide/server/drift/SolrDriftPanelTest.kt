package org.apache.solr.ide.server.drift

import com.intellij.openapi.util.Disposer
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase
import org.apache.solr.ide.model.SolrConfigsetFacts
import org.apache.solr.ide.model.schema.SolrField
import org.apache.solr.ide.server.reading.SolrServerRead
import org.apache.solr.ide.server.transport.SolrResponse

/**
 * The drift view, driven through the states it can be in.
 *
 * **The state worth the most care is the one that looks like success.** An empty table means "these
 * two agree" or "nothing was compared" depending on how it got there, and those are opposite
 * answers to the question a user opens this view to ask.
 */
class SolrDriftPanelTest : SolrConfigsetTestCase() {

    override fun tearDown() {
        try {
            TestDialogManager.setTestDialog(TestDialog.DEFAULT)
        } finally {
            super.tearDown()
        }
    }

    private fun panel(): SolrDriftPanel {
        val created = SolrDriftPanel(project)
        Disposer.register(testRootDisposable, created)
        return created
    }

    private fun field(name: String, type: String = "string") = SolrField(name = name, type = type)

    private fun drift(repository: List<SolrField>, server: List<SolrField>) =
        SolrDrift.between(SolrConfigsetFacts(fields = repository), SolrConfigsetFacts(fields = server))

    // --- the empty table, and what it means --------------------------------------------------------

    /**
     * Before anything is compared, the table is empty and says why.
     *
     * Silence here must not read as agreement.
     */
    fun testNothingComparedShowsNoRowsAndNoSummary() {
        val page = panel()

        page.render(SolrDriftView.NotCompared)

        assertEmpty(page.rowNames)
        assertNull("no comparison ran, so there is nothing to summarise", page.bannerMessage)
    }

    /**
     * A clean comparison is also an empty table — and says so, with a count.
     *
     * The count is the only thing on screen distinguishing this from the state above, which is
     * exactly why it is there.
     */
    fun testACleanComparisonSaysTheyAgreeAndHowMuchAgreed() {
        val page = panel()
        val shared = listOf(field("id"), field("title"))

        page.render(SolrDriftView.Compared("books", "books_prod", drift(shared, shared)))

        assertEmpty(page.rowNames)
        val summary = page.bannerMessage
        assertNotNull("a clean comparison must say it ran", summary)
        assertTrue(summary!!, summary.contains("books"))
        assertTrue(summary, summary.contains("books_prod"))
        assertTrue("the agreeing count is what proves it ran", summary.contains("2"))
    }

    // --- the three categories on screen ------------------------------------------------------------

    fun testEachCategoryGetsARowNamingItsState() {
        val page = panel()

        page.render(
            SolrDriftView.Compared(
                "books",
                "books_prod",
                drift(
                    repository = listOf(field("not_deployed"), field("differing", type = "string")),
                    server = listOf(field("only_on_server"), field("differing", type = "text_general")),
                ),
            ),
        )

        assertEquals(listOf("differing", "not_deployed", "only_on_server"), page.rowNames)
        assertContainsElements(page.rowStates, "Not deployed", "Only on server", "Differs")
    }

    fun testTheSummaryCountsEachCategory() {
        val page = panel()

        page.render(
            SolrDriftView.Compared(
                "books",
                "books_prod",
                drift(listOf(field("a")), listOf(field("b"))),
            ),
        )

        val summary = page.bannerMessage
        assertNotNull(summary)
        assertTrue(summary!!, summary.contains("not deployed"))
        assertTrue(summary, summary.contains("only on the server"))
    }

    // --- failure ------------------------------------------------------------------------------------

    /**
     * A server that could not be read reports that, and compares nothing.
     *
     * The failure this view most needs to avoid: treating an unreadable server as an empty one
     * would report every field in the schema as undeployed, in the view a user consults precisely
     * when they are unsure what is deployed.
     */
    fun testAFailureComparesNothingAndSaysWhy() {
        val page = panel()

        page.render(SolrDriftView.Failed("Solr answered 401: unauthorized"))

        assertEmpty(page.rowNames)
        assertEquals("Solr answered 401: unauthorized", page.bannerMessage)
    }

    /** A failure clears a comparison that was on screen, rather than leaving it under a banner. */
    fun testAFailureClearsAPreviousComparison() {
        val page = panel()
        page.render(SolrDriftView.Compared("books", "books_prod", drift(listOf(field("a")), emptyList())))

        page.render(SolrDriftView.Failed("Connection refused"))

        assertEmpty(page.rowNames)
        assertEquals("Connection refused", page.bannerMessage)
    }

    // --- what reaches the view from a response -----------------------------------------------------

    /** A failed read never becomes a comparison, whatever the repository holds. */
    fun testAFailedReadDoesNotBecomeAComparison() {
        val view = driftViewFor(
            configset = "books",
            collection = "books_prod",
            repository = SolrConfigsetFacts(fields = listOf(field("id"), field("title"))),
            response = SolrResponse.SolrError(401, "unauthorized"),
        )

        assertTrue(view.toString(), view is SolrDriftView.Failed)
        assertTrue((view as SolrDriftView.Failed).message.contains("unauthorized"))
    }

    fun testASuccessfulReadBecomesAComparison() {
        val view = driftViewFor(
            configset = "books",
            collection = "books_prod",
            repository = SolrConfigsetFacts(fields = listOf(field("id"))),
            response = SolrResponse.Success(SolrServerRead(SolrConfigsetFacts(fields = listOf(field("id"))), null)),
        )

        assertTrue(view.toString(), view is SolrDriftView.Compared)
        assertTrue((view as SolrDriftView.Compared).drift.isClean)
        assertEquals(1, view.drift.agreeingCount)
    }

    /** A partial read is compared and labelled, because what arrived is real but is not all of it. */
    fun testAPartialReadIsComparedAndLabelled() {
        val view = driftViewFor(
            configset = "books",
            collection = "books_prod",
            repository = SolrConfigsetFacts(fields = listOf(field("id"))),
            response = SolrResponse.Partial(
                SolrServerRead(SolrConfigsetFacts(fields = listOf(field("id"))), null),
                "time allowed exceeded",
            ),
        )

        assertTrue(view.toString(), view is SolrDriftView.Compared)
        assertNotNull("a partial comparison must say so", (view as SolrDriftView.Compared).warning)
    }

    /** The warning travels onto the summary line rather than being dropped on the way to screen. */
    fun testAPartialComparisonWarnsOnScreen() {
        val page = panel()

        page.render(
            SolrDriftView.Compared("books", "books_prod", drift(emptyList(), emptyList()), warning = "not all of it"),
        )

        assertTrue(page.bannerMessage.orEmpty(), page.bannerMessage.orEmpty().contains("not all of it"))
    }

    // --- writing, and what a successful write does not prove ---------------------------------------

    /** A write in flight is its own state, so a user watching one knows that is what they see. */
    fun testAWriteInFlightSaysSo() {
        val page = panel()
        page.render(SolrDriftView.Compared("books", "books_prod", drift(listOf(field("a")), emptyList())))

        page.render(SolrDriftView.Writing)

        assertEmpty("a write clears the stale comparison it is about to invalidate", page.rowNames)
        assertNull(page.bannerMessage)
    }

    /**
     * A failed write reports the failure and compares nothing.
     *
     * The write's own answer is the only thing known at that point, and reporting the previous
     * comparison beside it would suggest the write left the server where the table says it is.
     */
    fun testAFailedWriteIsReportedAndComparesNothing() {
        val page = panel()

        page.render(SolrDriftView.Failed(SolrConfigsetWriter.NOT_SOLR_CLOUD))

        assertEmpty(page.rowNames)
        assertTrue(page.bannerMessage.orEmpty(), page.bannerMessage.orEmpty().contains("SolrCloud"))
    }

    /**
     * A comparison after a write still reports drift where the server did not take it.
     *
     * **This is the rule the whole write path exists to obey.** A configset upload returning
     * `status: 0` is not proof the server reflects it — an archive lacking `_version_` uploads
     * cleanly, appears in `action=LIST`, and Solr then refuses to build a collection from it. The
     * view must be able to say "written, and still different", which it cannot if a successful
     * write clears the table.
     */
    fun testAComparisonAfterAWriteCanStillShowDrift() {
        val page = panel()

        page.render(
            SolrDriftView.Compared(
                "books",
                "books_prod",
                drift(repository = listOf(field("still_missing")), server = emptyList()),
            ),
        )

        assertEquals(listOf("still_missing"), page.rowNames)
        assertContainsElements(page.rowStates, "Not deployed")
    }

    // --- the guards, which are where "nothing happens" is the correct behaviour --------------------

    /**
     * Pressing Compare with no connection configured does nothing at all.
     *
     * Each of these guards is a branch, and an untested guard is one that could `return` on the
     * wrong condition — or not return at all, and reach a request with a null it never checked.
     */
    fun testComparingWithNoConnectionDoesNothing() {
        val page = panel()
        page.render(SolrDriftView.Compared("books", "books_prod", drift(listOf(field("a")), emptyList())))

        page.compare()

        assertEquals("the previous comparison is left alone", listOf("a"), page.rowNames)
    }

    fun testUploadingWithNoConnectionDoesNothing() {
        val page = panel()

        page.uploadAndReload()

        assertNull(page.bannerMessage)
        assertEmpty(page.rowNames)
    }

    /** With no configset in the project there is nothing to compare, and no request is built. */
    fun testComparingWithNoConfigsetDoesNothing() {
        val page = panel()
        page.reloadConfigsets()

        page.compare()

        assertEmpty(page.rowNames)
    }

    // --- small contracts that are user-visible when broken -----------------------------------------

    /**
     * The table is read-only.
     *
     * It shows what two servers say; typing into it would edit neither, and a cell that accepts a
     * keystroke and discards it is worse than one that refuses.
     */
    fun testTheTableIsNotEditable() {
        val page = panel()
        page.render(SolrDriftView.Compared("books", "books_prod", drift(listOf(field("a")), emptyList())))

        assertFalse(page.isCellEditable(0, 0))
        assertFalse(page.isCellEditable(0, 3))
    }

    /**
     * Both actions are disabled until there is something to act on.
     *
     * An enabled button that silently does nothing is the failure here — every guard inside these
     * actions returns quietly, so enablement is the only thing telling a user why.
     */
    fun testTheActionsAreDisabledWithNoConnection() {
        val page = panel()

        assertFalse("compare needs a connection", page.canAct())
    }

    fun testTheActionsAreEnabledOnceEverythingIsChosen() {
        connectionSettings.addConnection(
            org.apache.solr.ide.server.connection.SolrConnection("c1", "local", "http://127.0.0.1:1/solr"),
        )
        myFixture.addFileToProject("books/conf/managed-schema.xml", "<schema name=\"books\"/>")
        myFixture.addFileToProject("books/conf/solrconfig.xml", "<config/>")
        val page = panel()
        page.reloadConfigsets()
        page.setCollection("books_prod")

        assertTrue("everything is chosen: a connection, a configset and a collection", page.canAct())
    }

    /** A configset row is named as the user thinks of it, and a null renders as text, not "null". */
    fun testTheConfigsetChooserNamesItsEntries() {
        val renderer = SolrConfigsetComboRenderer()
        val list = com.intellij.ui.components.JBList(com.intellij.ui.CollectionListModel<org.apache.solr.ide.configset.activation.SolrConfigset>())

        renderer.getListCellRendererComponent(list, null, -1, false, false)

        assertFalse(renderer.text, renderer.text.contains("null"))
        assertTrue(renderer.text.isNotEmpty())
    }

    // --- each guard, one at a time -----------------------------------------------------------------

    private fun givenConfigset(name: String = "books") {
        myFixture.addFileToProject("$name/conf/managed-schema.xml", "<schema name=\"$name\"/>")
        myFixture.addFileToProject("$name/conf/solrconfig.xml", "<config/>")
    }

    private fun givenConnection() {
        connectionSettings.addConnection(
            org.apache.solr.ide.server.connection.SolrConnection("c1", "local", "http://127.0.0.1:1/solr"),
        )
    }

    /**
     * Each action stops at the first thing it is missing, and each of the three is checked.
     *
     * These are separate branches, and an untested one is a guard that could check the wrong thing —
     * or not check at all, and reach a request built from a null. They are also the only paths where
     * doing nothing is the whole of the correct behaviour, which is precisely what nobody notices is
     * broken.
     */
    fun testBothActionsStopWithNoConfigset() {
        givenConnection()
        val page = panel()
        page.setCollection("books_prod")

        page.compare()
        page.uploadAndReload()

        assertEmpty(page.rowNames)
        assertNull(page.bannerMessage)
    }

    fun testBothActionsStopWithNoCollection() {
        givenConnection()
        givenConfigset()
        val page = panel()
        page.reloadConfigsets()
        page.setCollection("")

        page.compare()
        page.uploadAndReload()

        assertEmpty(page.rowNames)
        assertNull(page.bannerMessage)
    }

    fun testBothActionsStopWithNoConnection() {
        givenConfigset()
        val page = panel()
        page.reloadConfigsets()
        page.setCollection("books_prod")

        page.compare()
        page.uploadAndReload()

        assertEmpty(page.rowNames)
        assertNull(page.bannerMessage)
    }

    // --- what the toolbar says it will do ----------------------------------------------------------

    private fun enablementOf(page: SolrDriftPanel): Map<String, Boolean> =
        page.toolbarActions.childActionsOrStubs.associate { action ->
            val event = com.intellij.testFramework.TestActionEvent.createTestEvent(action)
            action.update(event)
            (action.templatePresentation.text ?: "?") to event.presentation.isEnabled
        }

    /**
     * Every button is disabled until there is something for it to do.
     *
     * An enabled button whose action returns quietly is the failure here: each guards on a missing
     * connection, configset or collection, and enablement is the only thing telling a user which.
     */
    fun testEveryActionIsDisabledWithNothingChosen() {
        val page = panel()

        val enabled = enablementOf(page)

        assertFalse(enabled.toString(), enabled.values.any { it })
    }

    fun testEveryActionIsEnabledOnceEverythingIsChosen() {
        givenConnection()
        givenConfigset()
        val page = panel()
        page.reloadConfigsets()
        page.setCollection("books_prod")

        val enabled = enablementOf(page)

        assertTrue(enabled.toString(), enabled.values.all { it })
    }

    // --- the whole path, past every guard ----------------------------------------------------------
    //
    // The coroutine these actions launch cannot be joined from here. This test case runs on the EDT,
    // and the job completes by dispatching *to* the EDT to render — so awaiting it from a test
    // deadlocks, reliably and immediately. What the job does is covered by testing `writeThenCompare`
    // directly, which is why that function is reachable; what is left uncovered is the `launch`
    // wrapper, and restructuring production code to reach it would be shaping the design around a
    // coverage number.

    /**
     * Upload asks before writing, and a refusal writes nothing.
     *
     * The confirmation is the last thing standing between a keystroke and a live server, so a test
     * that stubbed it away would be testing the path that matters least.
     */
    fun testCancellingTheConfirmationWritesNothing() {
        givenConnection()
        givenConfigset()
        val page = panel()
        page.reloadConfigsets()
        page.setCollection("books_prod")
        TestDialogManager.setTestDialog(TestDialog.NO)

        val job = page.uploadAndReload()

        assertNull("a cancelled confirmation starts no work", job)
        assertNull(page.bannerMessage)
    }
}
