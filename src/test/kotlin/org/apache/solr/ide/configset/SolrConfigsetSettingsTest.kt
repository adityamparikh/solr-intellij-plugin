package org.apache.solr.ide.configset

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
        assertEquals(listOf(root.path), settings.state.manualConfigsetRoots.toList())
    }

    fun testRemovingRootStopsRecognizingFilesUnderIt() {
        val file = myFixture.addFileToProject("marked/schema.xml", "<schema/>").virtualFile
        val root = file.parent
        settings.addManualRoot(root)
        assertTrue(settings.isUnderManualRoot(file))

        settings.removeManualRoot(root.path)
        assertFalse(settings.isUnderManualRoot(file))
        assertTrue(settings.state.manualConfigsetRoots.isEmpty())
    }

    /** Removing something that was never added must be a no-op, not an error. */
    fun testRemovingUnknownRootIsANoOp() {
        val root = myFixture.addFileToProject("marked/keep.txt", "x").virtualFile.parent
        settings.addManualRoot(root)

        settings.removeManualRoot("/definitely/not/registered")
        assertEquals(listOf(root.path), settings.state.manualConfigsetRoots.toList())
    }
}
