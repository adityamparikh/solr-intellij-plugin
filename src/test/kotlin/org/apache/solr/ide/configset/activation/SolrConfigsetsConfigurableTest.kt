package org.apache.solr.ide.configset.activation

import com.intellij.ui.components.JBList

/**
 * The configset settings page, driven through the contract the platform calls.
 *
 * **This page exists because the escape hatch had no handle.** Detection could be switched off and a
 * root marked only by editing `solr.xml`, and a root a teammate committed could not be removed by
 * the person who received it. What is asserted here is the binding — the rows a user sees, what
 * `apply` writes, what `reset` restores — since that is where a settings page goes wrong without
 * anything on screen.
 */
class SolrConfigsetsConfigurableTest : SolrConfigsetTestCase() {

    private fun page() = SolrConfigsetsConfigurable(project).also { it.reset() }

    private fun detectedConfigset(dir: String) =
        myFixture.addFileToProject("$dir/conf/managed-schema.xml", "<schema/>").virtualFile.parent

    private fun unrecognisedDirectory(dir: String) =
        myFixture.addFileToProject("$dir/schema.xml", "<schema/>").virtualFile.parent

    fun testThePageIsNamed() {
        assertEquals("Solr Configsets", SolrConfigsetsConfigurable(project).displayName)
    }

    /** The panel builds — the assertion that catches a missing bundle key or a renderer that throws. */
    fun testThePanelBuilds() {
        assertNotNull(page().createComponent())
    }

    // --- what the page shows ------------------------------------------------------------------------

    /**
     * Detected and marked configsets are both listed, and told apart.
     *
     * The value of the page is here rather than in its checkbox: a user whose configset did not
     * activate cannot tell a detection miss from a detection that was never going to run, and seeing
     * what *was* found is how they find out which failure they have.
     */
    fun testDetectedAndMarkedConfigsetsAreListedDistinguishably() {
        detectedConfigset("books")
        val marked = unrecognisedDirectory("legacy")
        settings.addManualRoot(marked)

        val rows = page().rows

        assertEquals(setOf("books" to false, "legacy" to true), rows.map { it.name to it.marked }.toSet())
    }

    /** A marked root that detection would also have found is listed once, as marked. */
    fun testAConfigsetBothDetectedAndMarkedIsListedOnceAsMarked() {
        val books = detectedConfigset("books")
        settings.addManualRoot(books)

        val rows = page().rows.filter { it.path == books.path }

        assertEquals(1, rows.size)
        assertTrue(rows.single().marked)
    }

    // --- what the page changes ----------------------------------------------------------------------

    /** Detection can be switched off without editing XML. */
    fun testDetectionCanBeSwitchedOff() {
        val page = page()

        page.detectionEnabled = false
        assertTrue(page.isModified)
        page.apply()

        assertFalse(settings.isDetectionEnabled)
        assertFalse("applied, so nothing is pending", page.isModified)
    }

    /** A directory can be marked and unmarked from the page, and each takes effect on apply. */
    fun testARootCanBeMarkedAndUnmarked() {
        val dir = unrecognisedDirectory("legacy")
        val page = page()

        page.mark(dir)
        page.apply()
        assertEquals(listOf(dir.path), settings.manualRoots)

        page.unmark(page.rows.single { it.path == dir.path })
        page.apply()
        assertEmpty(settings.manualRoots)
    }

    /**
     * A root a teammate committed is visible and removable here.
     *
     * Seeded straight into the persisted state, in the project-relative form a commit carries, because
     * that is how it arrives: nobody on this machine marked it, and until this page existed nobody on
     * this machine could unmark it. The directory need not exist here for the row to be removable.
     */
    fun testARootMarkedByATeammateIsVisibleAndRemovable() {
        settings.state.manualConfigsetRoots.add("\$PROJECT_DIR\$/solr/conf")
        val page = page()

        val committed = page.rows.single { it.marked }
        assertEquals("${project.basePath}/solr/conf", committed.path)

        page.unmark(committed)
        page.apply()

        assertEmpty(settings.state.manualConfigsetRoots)
    }

    /**
     * A detected configset is not removable; switching detection off is how to exclude it.
     *
     * Removing a detected row would have nothing to write — detection is a rule, not a list — so a
     * remove that appeared to work would come straight back on the next reset.
     */
    fun testADetectedConfigsetIsNotRemovable() {
        detectedConfigset("books")
        val page = page()

        assertFalse(page.canUnmark(page.rows.single()))
    }

    /** Reset discards what was not applied. */
    fun testResetDiscardsUnappliedEdits() {
        val page = page()
        page.mark(unrecognisedDirectory("legacy"))
        page.detectionEnabled = false

        page.reset()

        assertFalse(page.isModified)
        assertTrue(page.detectionEnabled)
        assertEmpty(page.rows)
    }

    /** Marking a directory already marked changes nothing, rather than listing it twice. */
    fun testMarkingTheSameDirectoryTwiceListsItOnce() {
        val dir = unrecognisedDirectory("legacy")
        val page = page()

        page.mark(dir)
        page.mark(dir)

        assertEquals(1, page.rows.count { it.path == dir.path })
    }

    /** Asking to unmark a detected row leaves it listed: there is nothing for the page to write. */
    fun testUnmarkingADetectedRowLeavesItListed() {
        detectedConfigset("books")
        val page = page()

        page.unmark(page.rows.single())

        assertFalse(page.isModified)
        assertEquals(listOf("books"), page.rows.map { it.name })
    }

    // --- what a row says ----------------------------------------------------------------------------

    /**
     * Each row names its provenance in words, not only by where it sits in the list.
     *
     * Marked roots sort first, but a list of one row has no order to read, and the difference between
     * "you chose this" and "the plugin found this" is what decides whether remove does anything.
     */
    fun testARowSaysWhetherItWasMarkedOrDetected() {
        assertTrue(rendered(SolrConfigsetRow("/p/legacy", "legacy", marked = true)).endsWith("marked"))
        assertTrue(rendered(SolrConfigsetRow("/p/books/conf", "books", marked = false)).endsWith("detected"))
    }

    /** The path is shown beside the name, since two configsets are routinely called the same thing. */
    fun testARowShowsItsPath() {
        assertTrue(rendered(SolrConfigsetRow("/p/books/conf", "books", marked = false)).contains("/p/books/conf"))
    }

    private fun rendered(row: SolrConfigsetRow): String {
        val renderer = SolrConfigsetRowRenderer()
        renderer.getListCellRendererComponent(JBList<SolrConfigsetRow>(), row, 0, false, false)
        return renderer.getCharSequence(false).toString()
    }
}
