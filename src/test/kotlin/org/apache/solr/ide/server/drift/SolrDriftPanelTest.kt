package org.apache.solr.ide.server.drift

import com.intellij.openapi.util.Disposer
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
}
