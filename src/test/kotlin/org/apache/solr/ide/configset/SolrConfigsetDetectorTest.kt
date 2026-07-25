package org.apache.solr.ide.configset

import com.intellij.ide.highlighter.XmlFileType
import com.intellij.psi.PsiFileFactory

class SolrConfigsetDetectorTest : SolrConfigsetTestCase() {

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

    fun testSolrConfigUnderConfDirIsRecognizedAsSolrConfig() {
        val file = myFixture.addFileToProject("core/conf/solrconfig.xml", "<config/>").virtualFile
        assertTrue(SolrConfigsetDetector.isConfigsetFile(project, file))
        assertEquals(SolrConfigsetFileKind.SOLR_CONFIG, SolrConfigsetDetector.kindOf(project, file))
    }

    /** Newer Solr writes `managed-schema.xml`; the extensionless form is the legacy spelling. */
    fun testManagedSchemaXmlVariantIsRecognized() {
        val file = myFixture.addFileToProject("core/conf/managed-schema.xml", "<schema/>").virtualFile
        assertEquals(SolrConfigsetFileKind.SCHEMA, SolrConfigsetDetector.kindOf(project, file))
    }

    /**
     * A *directory* named like a configset file must be rejected before any heuristic runs —
     * otherwise a `conf/schema.xml/` directory would activate features against a non-file.
     */
    fun testDirectoryIsNeverAConfigsetFile() {
        val dir = myFixture.tempDirFixture.findOrCreateDir("core/conf/schema.xml")
        assertTrue(dir.isDirectory)
        assertFalse(SolrConfigsetDetector.isConfigsetFile(project, dir))
        assertNull(SolrConfigsetDetector.kindOf(project, dir))
    }

    fun testKindOfIsNullWhenFileIsNotRecognized() {
        val file = myFixture.addFileToProject("unrelated/schema.xml", "<schema/>").virtualFile
        assertNull(SolrConfigsetDetector.kindOf(project, file))
    }

    fun testPsiFileOverloadDelegatesToVirtualFileDetection() {
        val recognized = myFixture.addFileToProject("core/conf/schema.xml", "<schema/>")
        assertTrue(SolrConfigsetDetector.isConfigsetFile(recognized))

        val unrecognized = myFixture.addFileToProject("unrelated/schema.xml", "<schema/>")
        assertFalse(SolrConfigsetDetector.isConfigsetFile(unrecognized))
    }

    /** The PSI overload must honour the same kill switch as the [com.intellij.openapi.vfs.VirtualFile] one. */
    fun testPsiFileOverloadRespectsDisabledDetection() {
        val psiFile = myFixture.addFileToProject("core/conf/schema.xml", "<schema/>")
        SolrConfigsetSettings.getInstance(project).setDetectionEnabled(false)
        assertFalse(SolrConfigsetDetector.isConfigsetFile(psiFile))
    }

    /**
     * A non-physical PSI file (scratch buffer, intention preview, in-memory copy) has no
     * [com.intellij.openapi.vfs.VirtualFile]. Detection must decline rather than throw, since the
     * directory heuristics have no filesystem to inspect.
     */
    fun testInMemoryPsiFileWithoutVirtualFileIsNotRecognized() {
        val inMemory = PsiFileFactory.getInstance(project)
            .createFileFromText("schema.xml", XmlFileType.INSTANCE, "<schema/>")
        assertNull("expected a non-physical PsiFile to have no VirtualFile", inMemory.virtualFile)
        assertFalse(SolrConfigsetDetector.isConfigsetFile(inMemory))
    }

    /**
     * Sibling evidence must come from a *different* file: a lone recognized file is not its own
     * corroboration. Without the `sibling != file` guard every stray `schema.xml` would activate.
     */
    fun testFileIsNotItsOwnSiblingEvidence() {
        val file = myFixture.addFileToProject("solo/managed-schema", "<schema/>").virtualFile
        assertEquals(1, file.parent.children.size)
        assertFalse(SolrConfigsetDetector.isConfigsetFile(project, file))
    }
}
