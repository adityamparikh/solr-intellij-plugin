package org.apache.solr.ide.configset.reading

import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

class SolrConfigsetScannerTest : SolrConfigsetTestCase() {

    private val scanner: SolrConfigsetScanner get() = SolrConfigsetScanner.getInstance(project)

    private fun givenTwoConfigsets() {
        myFixture.addFileToProject("products/conf/managed-schema.xml", "<schema/>")
        myFixture.addFileToProject("products/conf/solrconfig.xml", "<config/>")
        myFixture.addFileToProject("orders/conf/managed-schema.xml", "<schema/>")
    }

    fun testEveryConfigsetInTheProjectIsFound() {
        givenTwoConfigsets()
        assertEquals(listOf("orders", "products"), scanner.scan().map { it.name })
    }

    /**
     * The pruning criterion. A configset inside build output is a copy of one in the source tree,
     * and reporting the copy would send the user to edit a file their next build overwrites.
     */
    fun testTheScanDeclinesToDescendIntoBuildAndDependencyTrees() {
        givenTwoConfigsets()
        for (pruned in listOf("build", "node_modules", "target", "out", "dist", ".git")) {
            myFixture.addFileToProject("$pruned/conf/managed-schema.xml", "<schema/>")
            myFixture.addFileToProject("$pruned/nested/deep/conf/solrconfig.xml", "<config/>")
        }

        assertEquals(
            "only the two real configsets may be reported",
            listOf("orders", "products"),
            scanner.scan().map { it.name },
        )
    }

    fun testADirectoryWithOnlyAnAmbiguousNameIsNotAConfigset() {
        myFixture.addFileToProject("src/main/resources/schema.xml", "<xs:schema/>")
        myFixture.addFileToProject("src/test/resources/params.json", "{}")
        assertTrue(scanner.scan().isEmpty())
    }

    fun testNestedConfigsetsAreBothReported() {
        myFixture.addFileToProject("cores/a/conf/managed-schema.xml", "<schema/>")
        myFixture.addFileToProject("cores/b/conf/solrconfig.xml", "<config/>")
        assertEquals(2, scanner.scan().size)
    }

    fun testNothingIsFoundOutsideASolrProject() {
        givenTwoConfigsets()
        givenNoSolrOnTheClasspath()
        assertTrue(scanner.scan().isEmpty())
    }

    /** The manual override has to work here too, or it fails in the one shape that depends on it. */
    fun testAMarkedRootIsReportedWithoutASolrDependency() {
        val schema = myFixture.addFileToProject("weird/managed-schema.xml", "<schema/>").virtualFile
        givenNoSolrOnTheClasspath()
        assertTrue(scanner.scan().isEmpty())

        settings.addManualRoot(schema.parent)

        assertEquals(listOf("weird"), scanner.scan().map { it.name })
    }

    /**
     * A marked root need not contain a recognized file. The user asserted the directory is a
     * configset, and second-guessing that makes the override useless.
     */
    fun testAMarkedRootWithNoRecognizedFileIsStillReported() {
        val other = myFixture.addFileToProject("blank/notes.txt", "hello").virtualFile
        settings.addManualRoot(other.parent)
        assertTrue(scanner.scan().any { it.name == "blank" })
    }

    fun testDisablingDetectionSilencesTheScan() {
        givenTwoConfigsets()
        settings.setDetectionEnabled(false)
        assertTrue(scanner.scan().isEmpty())
    }

    fun testAProjectWithNoConfigsetsScansToNothing() {
        myFixture.addFileToProject("src/App.java", "class App {}")
        assertTrue(scanner.scan().isEmpty())
    }
}
