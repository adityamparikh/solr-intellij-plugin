package org.apache.solr.ide.configset.activation

import com.intellij.configurationStore.deserializeInto
import com.intellij.configurationStore.serialize
import com.intellij.openapi.components.PathMacroManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertThrows
import java.io.File

/**
 * Behaviour of the persistent manual-override settings backing [SolrConfigsetDetector].
 *
 * These exercise the service directly rather than through the detector, so a regression in the
 * root bookkeeping is attributed here instead of surfacing as a confusing detection failure.
 */
class SolrConfigsetSettingsTest : SolrConfigsetTestCase() {

    fun testDetectionIsEnabledByDefault() {
        assertTrue(settings.isDetectionEnabled)
    }

    fun testDetectionToggleRoundTrips() {
        settings.setDetectionEnabled(false)
        assertFalse(settings.isDetectionEnabled)
        settings.setDetectionEnabled(true)
        assertTrue(settings.isDetectionEnabled)
    }

    /**
     * A marked root is in the state that gets written, not only in the one held in memory.
     *
     * **Every other test here reads the roots back from the live object**, which answers whether or
     * not a byte ever reaches the workspace file. Serialization is a separate question and it had a
     * separate answer: the list was skipped and `detectionEnabled` beside it was not, so the
     * component was written on every save and came back holding no roots — indistinguishable from a
     * user who had never marked one.
     *
     * What that cost is the whole of this escape hatch. A repository holding configsets and nothing
     * else cannot satisfy the dependency gate, so marking a root by hand is the only way the plugin
     * ever wakes there — and it woke until the IDE was closed, then never again.
     */
    fun testAMarkedRootSurvivesSerialization() {
        val state = SolrConfigsetSettings.State()
        state.manualConfigsetRoots.add("\$PROJECT_DIR\$/solr/conf")

        val written = checkNotNull(serialize(state)) { "the state serialized to nothing at all" }
        val reloaded = SolrConfigsetSettings.State().also { written.deserializeInto(it) }

        assertEquals(listOf("\$PROJECT_DIR\$/solr/conf"), reloaded.manualConfigsetRoots)
    }

    fun testNoRootsMeansNothingIsUnderAManualRoot() {
        val file = myFixture.addFileToProject("anywhere/schema.xml", "<schema/>").virtualFile
        assertFalse(settings.isUnderManualRoot(file))
    }

    fun testFileDirectlyUnderMarkedRootIsRecognized() {
        val file = myFixture.addFileToProject("marked/schema.xml", "<schema/>").virtualFile
        settings.addManualRoot(file.parent)
        assertTrue(settings.isUnderManualRoot(file))
    }

    /** Marking a root must cover arbitrarily deep descendants, not just direct children. */
    fun testNestedDescendantOfMarkedRootIsRecognized() {
        val root = myFixture.addFileToProject("marked/keep.txt", "x").virtualFile.parent
        val nested = myFixture.addFileToProject("marked/a/b/c/schema.xml", "<schema/>").virtualFile
        settings.addManualRoot(root)
        assertTrue(settings.isUnderManualRoot(nested))
    }

    /**
     * `isUnderManualRoot` uses a non-strict ancestor check, so a marked directory counts as being
     * under itself. Pinning this documents the boundary rather than leaving it to chance.
     */
    fun testMarkedRootIsUnderItself() {
        val root = myFixture.addFileToProject("marked/keep.txt", "x").virtualFile.parent
        settings.addManualRoot(root)
        assertTrue(settings.isUnderManualRoot(root))
    }

    fun testSiblingOfMarkedRootIsNotRecognized() {
        val marked = myFixture.addFileToProject("marked/keep.txt", "x").virtualFile.parent
        val outside = myFixture.addFileToProject("other/schema.xml", "<schema/>").virtualFile
        settings.addManualRoot(marked)
        assertFalse(settings.isUnderManualRoot(outside))
    }

    /**
     * A path prefix is not an ancestor: `/marked-extra` must not be captured by a `/marked` root.
     * A naive `startsWith` implementation would pass every other test here but fail this one.
     */
    fun testPathPrefixSiblingIsNotTreatedAsDescendant() {
        val marked = myFixture.addFileToProject("marked/keep.txt", "x").virtualFile.parent
        val lookalike = myFixture.addFileToProject("marked-extra/schema.xml", "<schema/>").virtualFile
        settings.addManualRoot(marked)
        assertFalse(settings.isUnderManualRoot(lookalike))
    }

    fun testAddingSameRootTwiceIsIdempotent() {
        val root = myFixture.addFileToProject("marked/keep.txt", "x").virtualFile.parent
        settings.addManualRoot(root)
        settings.addManualRoot(root)
        assertEquals(listOf(root.path), settings.manualRoots)
    }

    fun testRemovingRootStopsRecognizingFilesUnderIt() {
        val file = myFixture.addFileToProject("marked/schema.xml", "<schema/>").virtualFile
        val root = file.parent
        settings.addManualRoot(root)
        assertTrue(settings.isUnderManualRoot(file))

        settings.removeManualRoot(root.path)
        assertFalse(settings.isUnderManualRoot(file))
        assertTrue(settings.manualRoots.isEmpty())
    }

    /**
     * The portability mechanism itself: a path under the project collapses to `$PROJECT_DIR$` and
     * expands back, which is what lets `solr.xml` be shared through VCS.
     *
     * This asserts against [PathMacroManager] directly rather than through [addManualRoot],
     * because files created by [BasePlatformTestCase] live on an in-memory `/src` filesystem that
     * is *outside* `project.basePath`. Collapsing correctly does nothing for those paths, so a
     * fixture-created root cannot demonstrate the behaviour — only a genuinely project-relative
     * path can.
     */
    fun testProjectRelativePathCollapsesAndExpandsBack() {
        val macros = PathMacroManager.getInstance(project)
        val underProject = "${project.basePath}/core/conf"

        assertEquals("\$PROJECT_DIR\$/core/conf", macros.collapsePath(underProject))
        assertEquals(underProject, macros.expandPath(macros.collapsePath(underProject)))
    }

    /**
     * A stored `$PROJECT_DIR$` root must expand to a real absolute path when read back. This is
     * the half of portability that makes a VCS-shared `solr.xml` usable on a fresh checkout.
     */
    fun testCollapsedRootExpandsToTheProjectDirectory() {
        settings.state.manualConfigsetRoots.add("\$PROJECT_DIR\$/core/conf")
        assertEquals(listOf("${project.basePath}/core/conf"), settings.manualRoots)
    }

    /**
     * Callers hold absolute paths, but a shared root is stored collapsed. Removal has to bridge
     * the two, or roots marked on another machine could never be un-marked on this one.
     */
    fun testCollapsedRootIsRemovableByItsAbsolutePath() {
        settings.state.manualConfigsetRoots.add("\$PROJECT_DIR\$/core/conf")
        settings.removeManualRoot("${project.basePath}/core/conf")
        assertTrue(settings.manualRoots.isEmpty())
    }

    /**
     * The other half of portability: marking a directory that really is inside the project must
     * persist it collapsed. Uses a directory created under `project.basePath` on the local file
     * system, because fixture-created files live on an in-memory `/src` filesystem that is outside
     * the project and so can never exercise collapsing.
     */
    fun testRootInsideProjectDirectoryIsStoredCollapsed() {
        val dir = File(project.basePath!!, "conf").apply { mkdirs() }
        val vDir = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(dir)
        assertNotNull("could not resolve $dir in the VFS", vDir)

        settings.addManualRoot(vDir!!)
        assertEquals("\$PROJECT_DIR\$/conf", settings.state.manualConfigsetRoots.single())
        assertEquals(listOf(dir.path), settings.manualRoots)
    }

    /** Whatever the storage form, adding a root must round-trip back to its real location. */
    fun testAddedRootRoundTripsToItsAbsolutePath() {
        val root = myFixture.addFileToProject("marked/keep.txt", "x").virtualFile.parent
        settings.addManualRoot(root)
        assertEquals(listOf(root.path), settings.manualRoots)
    }

    /**
     * Settings written before paths were collapsed hold raw absolute paths. Those must keep
     * working — expanding a path with no macro in it returns it unchanged — so no migration
     * step is needed.
     */
    fun testAbsolutePathFromEarlierVersionStillResolves() {
        val file = myFixture.addFileToProject("legacy/schema.xml", "<schema/>").virtualFile
        settings.state.manualConfigsetRoots.add(file.parent.path)

        assertTrue(settings.isUnderManualRoot(file))
        assertEquals(listOf(file.parent.path), settings.manualRoots)
    }

    /**
     * `isUnderManualRoot` is non-strict, so a marked *file* would match itself and become its own
     * configset root. Rejecting non-directories keeps that from being expressible.
     */
    fun testMarkingAFileRatherThanADirectoryIsRejected() {
        val file = myFixture.addFileToProject("marked/schema.xml", "<schema/>").virtualFile
        assertThrows(IllegalArgumentException::class.java) { settings.addManualRoot(file) }
        assertTrue(settings.manualRoots.isEmpty())
    }

    /** Removal must accept the absolute path callers naturally hold, not the collapsed form. */
    fun testRemovingByAbsolutePathWorksForACollapsedRoot() {
        val root = myFixture.addFileToProject("marked/keep.txt", "x").virtualFile.parent
        settings.addManualRoot(root)

        settings.removeManualRoot(root.path)
        assertTrue(settings.manualRoots.isEmpty())
    }

    /**
     * A root written by the previous version is stored as a raw absolute path. If it happens to
     * live inside the project, removal must still find it — otherwise the collapsed lookup misses
     * the legacy entry and the remove silently does nothing.
     */
    fun testLegacyAbsoluteRootInsideProjectIsRemovableByPath() {
        val dir = File(project.basePath!!, "legacy-conf").apply { mkdirs() }
        settings.state.manualConfigsetRoots.add(dir.path)
        assertEquals(listOf(dir.path), settings.manualRoots)

        settings.removeManualRoot(dir.path)
        assertTrue("legacy absolute root was not removed", settings.manualRoots.isEmpty())
    }

    /** Removing something that was never added must be a no-op, not an error. */
    fun testRemovingUnknownRootIsANoOp() {
        val root = myFixture.addFileToProject("marked/keep.txt", "x").virtualFile.parent
        settings.addManualRoot(root)

        settings.removeManualRoot("/definitely/not/registered")
        assertEquals(listOf(root.path), settings.manualRoots)
    }
}
