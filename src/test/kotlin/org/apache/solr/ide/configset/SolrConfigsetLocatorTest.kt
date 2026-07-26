package org.apache.solr.ide.configset

class SolrConfigsetLocatorTest : SolrConfigsetTestCase() {

    fun testFileResolvesToItsOwningConfigset() {
        val file = myFixture.addFileToProject("core/conf/managed-schema.xml", "<schema/>").virtualFile
        val configset = SolrConfigsetDetector.configsetFor(project, file)
        assertNotNull(configset)
        assertEquals("conf", configset!!.root.name)
        assertTrue(configset.contains(file))
    }

    /** A `conf` root is named for its parent, so a multi-core project does not show two "conf"s. */
    fun testConfigsetIsNamedForTheCoreDirectory() {
        val file = myFixture.addFileToProject("products/conf/managed-schema.xml", "<schema/>").virtualFile
        assertEquals("products", SolrConfigsetDetector.configsetFor(project, file)!!.name)
    }

    /** The core of the per-configset model: two configsets in one project must not merge. */
    fun testTwoConfigsetsInOneProjectStayDistinct() {
        val products = myFixture.addFileToProject("products/conf/managed-schema.xml", "<schema/>").virtualFile
        val orders = myFixture.addFileToProject("orders/conf/managed-schema.xml", "<schema/>").virtualFile

        val productsSet = SolrConfigsetDetector.configsetFor(project, products)!!
        val ordersSet = SolrConfigsetDetector.configsetFor(project, orders)!!

        assertFalse("configsets in sibling cores must not be equal", productsSet == ordersSet)
        assertFalse("a configset must not claim the other's files", productsSet.contains(orders))
        assertTrue(productsSet.contains(products))
    }

    fun testFileOutsideAnyConfigsetResolvesToNothing() {
        val file = myFixture.addFileToProject("unrelated/notes.txt", "hello").virtualFile
        assertNull(SolrConfigsetDetector.configsetFor(project, file))
    }

    /** Even inside a Solr project, a directory with no recognized file is not a configset. */
    fun testDirectoryWithNoRecognizedFileIsNotAConfigset() {
        val file = myFixture.addFileToProject("src/main/java/App.java", "class App {}").virtualFile
        assertNull(SolrConfigsetDetector.configsetFor(project, file))
    }

    fun testResolutionIsSuppressedWhenDetectionIsDisabled() {
        val file = myFixture.addFileToProject("core/conf/managed-schema.xml", "<schema/>").virtualFile
        settings.setDetectionEnabled(false)
        assertNull(SolrConfigsetDetector.configsetFor(project, file))
    }

    /** A language resource sits one level below the root and must still reach it. */
    fun testLanguageResourceResolvesUpToTheConfigsetRoot() {
        myFixture.addFileToProject("core/conf/managed-schema.xml", "<schema/>")
        val stopwords = myFixture.addFileToProject("core/conf/lang/stopwords_en.txt", "a\nan\n").virtualFile
        assertEquals("conf", SolrConfigsetDetector.configsetFor(project, stopwords)!!.root.name)
    }

    fun testConfigsetDirectoryItselfResolvesToItsOwnConfigset() {
        val file = myFixture.addFileToProject("core/conf/managed-schema.xml", "<schema/>").virtualFile
        val confDir = file.parent
        assertEquals(confDir, SolrConfigsetDetector.configsetFor(project, confDir)!!.root)
    }

    // --- caching ---------------------------------------------------------------------------

    fun testRepeatedLookupsAreServedFromTheCache() {
        val file = myFixture.addFileToProject("core/conf/managed-schema.xml", "<schema/>").virtualFile
        locator.dropCache()
        assertEquals(0, locator.cacheSize)

        repeat(5) { SolrConfigsetDetector.configsetFor(project, file) }
        assertEquals("five lookups in one directory must produce one entry", 1, locator.cacheSize)
    }

    /** A negative answer is cached too, or every unrelated file the user opens re-walks the tree. */
    fun testNegativeResultsAreCached() {
        val file = myFixture.addFileToProject("plain/notes.txt", "hello").virtualFile
        locator.dropCache()
        assertNull(SolrConfigsetDetector.configsetFor(project, file))
        assertEquals(1, locator.cacheSize)
    }

    /**
     * The invalidation that matters: a directory that was not a configset becomes one when a second
     * identifying file appears. Without invalidation the stale negative would persist for the rest
     * of the session.
     */
    fun testCreatingAFileInvalidatesAStaleNegativeResult() {
        val readme = myFixture.addFileToProject("myset/README.md", "notes").virtualFile
        assertNull("no recognized file yet", SolrConfigsetDetector.configsetFor(project, readme))

        myFixture.addFileToProject("myset/solrconfig.xml", "<config/>")

        assertNotNull(
            "the new solrconfig.xml must invalidate the cached negative",
            SolrConfigsetDetector.configsetFor(project, readme),
        )
    }

    /** Marking a root changes resolution without touching a file, so settings must invalidate too. */
    fun testMarkingAManualRootInvalidatesTheCache() {
        givenNoSolrOnTheClasspath()
        val file = myFixture.addFileToProject("weird/managed-schema.xml", "<schema/>").virtualFile
        assertNull(SolrConfigsetDetector.configsetFor(project, file))

        settings.addManualRoot(file.parent)

        assertEquals(file.parent, SolrConfigsetDetector.configsetFor(project, file)!!.root)
    }

    /** The deepest marked root wins, so marking a core and its conf/ does not give the core. */
    fun testNestedManualRootsResolveToTheDeepestMatch() {
        val file = myFixture.addFileToProject("core/conf/managed-schema.xml", "<schema/>").virtualFile
        val confDir = file.parent
        settings.addManualRoot(confDir.parent)
        settings.addManualRoot(confDir)

        assertEquals(confDir, SolrConfigsetDetector.configsetFor(project, file)!!.root)
    }
}
