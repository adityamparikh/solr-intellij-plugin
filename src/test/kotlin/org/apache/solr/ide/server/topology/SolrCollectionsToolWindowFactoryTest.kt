package org.apache.solr.ide.server.topology

import com.intellij.toolWindow.ToolWindowHeadlessManagerImpl
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase
import org.apache.solr.ide.server.drift.SolrDriftPanel

/**
 * The tool window fills itself.
 *
 * That `plugin.xml` names a class which exists is already checked by `SolrPluginDescriptorTest`;
 * what that cannot see is whether the factory, once called, actually produces a tab. A factory that
 * throws or adds nothing leaves the user a registered tool window that opens onto blank — which is
 * indistinguishable, from outside, from a plugin that did not load.
 *
 * **That the registration itself takes effect is not asserted here, because this environment cannot
 * answer it.** The headless tool-window manager registers no tool windows at all — not this
 * plugin's, not any — so an assertion that "Solr" is among them could only ever fail, for a reason
 * that has nothing to do with the code. It is covered instead where it can be seen: the descriptor
 * test resolves the `factoryClass`, and the sandbox pass in `docs/manual-test-suite.md` is where a
 * human opens the thing.
 */
class SolrCollectionsToolWindowFactoryTest : SolrConfigsetTestCase() {

    fun testTheFactoryAddsATabForEachSurface() {
        val toolWindow = ToolWindowHeadlessManagerImpl.MockToolWindow(project)

        SolrCollectionsToolWindowFactory().createToolWindowContent(project, toolWindow)

        val contents = toolWindow.contentManager.contents
        assertEquals(2, contents.size)
        assertTrue(contents[0].component.toString(), contents[0].component is SolrCollectionsPanel)
        assertTrue(contents[1].component.toString(), contents[1].component is SolrDriftPanel)
    }

    /**
     * Both tabs are named.
     *
     * A tab with no title renders as a blank strip, which reads as a rendering fault rather than as
     * a tab — and the second tab is the one nobody will look for unless it is labelled.
     */
    fun testBothTabsAreNamed() {
        val toolWindow = ToolWindowHeadlessManagerImpl.MockToolWindow(project)

        SolrCollectionsToolWindowFactory().createToolWindowContent(project, toolWindow)

        assertEquals(
            listOf("Collections", "Drift"),
            toolWindow.contentManager.contents.map { it.displayName },
        )
    }

    /**
     * The tab disposes the panel it holds.
     *
     * Without the disposer the panel outlives its tab, and `BasePlatformTestCase`'s own leak
     * checking is what would eventually notice — in some unrelated test, long after the cause.
     */
    fun testClosingTheTabDisposesThePanel() {
        val toolWindow = ToolWindowHeadlessManagerImpl.MockToolWindow(project)
        SolrCollectionsToolWindowFactory().createToolWindowContent(project, toolWindow)

        toolWindow.contentManager.contents.forEach { content ->
            assertNotNull("every tab must dispose what it holds: ${content.displayName}", content.disposer)
        }
    }
}
