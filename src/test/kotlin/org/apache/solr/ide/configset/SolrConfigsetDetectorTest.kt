package org.apache.solr.ide.configset

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class SolrConfigsetDetectorTest : BasePlatformTestCase() {

    fun testSchemaUnderConfDirIsRecognized() {
        val file = myFixture.addFileToProject("core/conf/schema.xml", "<schema/>").virtualFile
        assertTrue(SolrConfigsetDetector.isConfigsetFile(project, file))
        assertEquals(SolrConfigsetFileKind.SCHEMA, SolrConfigsetDetector.kindOf(project, file))
    }

    fun testManagedSchemaWithSiblingSolrConfigIsRecognized() {
        myFixture.addFileToProject("myset/solrconfig.xml", "<config/>")
        val file = myFixture.addFileToProject("myset/managed-schema", "<schema/>").virtualFile
        assertTrue(SolrConfigsetDetector.isConfigsetFile(project, file))
    }

    fun testLoneSchemaWithoutEvidenceIsNotRecognized() {
        val file = myFixture.addFileToProject("unrelated/schema.xml", "<schema/>").virtualFile
        assertFalse(SolrConfigsetDetector.isConfigsetFile(project, file))
    }

    fun testNonConfigsetFileNameIsNotRecognized() {
        val file = myFixture.addFileToProject("core/conf/notes.xml", "<x/>").virtualFile
        assertFalse(SolrConfigsetDetector.isConfigsetFile(project, file))
    }

    fun testManualRootActivatesOtherwiseUndetectedFile() {
        val file = myFixture.addFileToProject("weird/schema.xml", "<schema/>").virtualFile
        assertFalse(SolrConfigsetDetector.isConfigsetFile(project, file))
        SolrConfigsetSettings.getInstance(project).addManualRoot(file.parent)
        assertTrue(SolrConfigsetDetector.isConfigsetFile(project, file))
    }

    fun testDisablingDetectionSuppressesEverything() {
        val file = myFixture.addFileToProject("core/conf/schema.xml", "<schema/>").virtualFile
        SolrConfigsetSettings.getInstance(project).setDetectionEnabled(false)
        assertFalse(SolrConfigsetDetector.isConfigsetFile(project, file))
    }
}
