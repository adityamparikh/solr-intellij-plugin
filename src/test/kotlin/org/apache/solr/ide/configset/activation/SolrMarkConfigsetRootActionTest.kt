package org.apache.solr.ide.configset.activation

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.TestActionEvent

/**
 * *Mark Directory as Solr Configset Root*, in the Project view's context menu.
 *
 * The one gesture for a configset detection misses — a directory of Solr XML in a repository with no
 * Solr client on its classpath, which the dependency gate turns away. It toggles: on a marked root
 * the same menu entry unmarks it.
 */
class SolrMarkConfigsetRootActionTest : SolrConfigsetTestCase() {

    private val action = SolrMarkConfigsetRootAction()

    private fun eventOn(vararg files: VirtualFile) = TestActionEvent.createTestEvent(
        action,
        SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.VIRTUAL_FILE_ARRAY, arrayOf(*files))
            .build(),
    )

    private fun directory(name: String) = myFixture.addFileToProject("$name/schema.xml", "<schema/>").virtualFile.parent

    fun testOffersToMarkAnUnmarkedDirectory() {
        val event = eventOn(directory("legacy"))

        action.update(event)

        assertTrue(event.presentation.isEnabledAndVisible)
        assertEquals("Mark Directory as Solr Configset Root", event.presentation.text)
    }

    fun testMarkingThenUnmarkingRoundTrips() {
        val dir = directory("legacy")

        action.actionPerformed(eventOn(dir))
        assertEquals(listOf(dir.path), settings.manualRoots)

        val again = eventOn(dir)
        action.update(again)
        assertEquals("Unmark Solr Configset Root", again.presentation.text)

        action.actionPerformed(again)
        assertEmpty(settings.manualRoots)
    }

    /** A file is not a root, and the entry does not appear for one. */
    fun testIsHiddenForAFile() {
        val file = myFixture.addFileToProject("legacy/schema.xml", "<schema/>").virtualFile
        val event = eventOn(file)

        action.update(event)

        assertFalse(event.presentation.isVisible)
    }

    /** Marking several at once would make the toggle ambiguous when they disagree, so it is one at a time. */
    fun testIsHiddenForSeveralDirectories() {
        val event = eventOn(directory("one"), directory("two"))

        action.update(event)

        assertFalse(event.presentation.isVisible)
    }
}
