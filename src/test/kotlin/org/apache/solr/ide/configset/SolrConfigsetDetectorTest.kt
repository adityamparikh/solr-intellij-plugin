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

    /**
     * The gate that replaced the directory heuristics: outside a project that depends on a Solr
     * client, no file activates anything, however Solr-shaped its name.
     */
    fun testNothingIsRecognizedOutsideASolrProject() {
        givenNoSolrOnTheClasspath()
        val schema = myFixture.addFileToProject("core/conf/schema.xml", "<schema/>").virtualFile
        val config = myFixture.addFileToProject("core/conf/solrconfig.xml", "<config/>").virtualFile
        assertFalse(SolrConfigsetDetector.isConfigsetFile(project, schema))
        assertFalse(SolrConfigsetDetector.isConfigsetFile(project, config))
        assertNull(SolrConfigsetDetector.configsetFor(project, schema))
    }

    /**
     * Inside a Solr project a lone `schema.xml` is believed, with no corroborating sibling and no
     * `conf/` parent. An earlier revision required both; the project-level dependency check settles
     * that question before this code runs, and asking a second time only produced false negatives.
     */
    fun testLoneSchemaIsRecognizedInsideASolrProject() {
        val file = myFixture.addFileToProject("unusual-layout/schema.xml", "<schema/>").virtualFile
        assertTrue(SolrConfigsetDetector.isConfigsetFile(project, file))
    }

    fun testNonConfigsetFileNameIsNotRecognized() {
        val file = myFixture.addFileToProject("core/conf/notes.xml", "<x/>").virtualFile
        assertFalse(SolrConfigsetDetector.isConfigsetFile(project, file))
    }

    /**
     * The escape hatch for the case the dependency check cannot serve: a repository of configsets
     * with no build file, and so no dependencies to find. Marking the directory bypasses the gate.
     */
    fun testManualRootActivatesAProjectWithNoSolrDependency() {
        givenNoSolrOnTheClasspath()
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
        val file = myFixture.addFileToProject("unrelated/notes.xml", "<x/>").virtualFile
        assertNull(SolrConfigsetDetector.kindOf(project, file))
    }

    fun testPsiFileOverloadDelegatesToVirtualFileDetection() {
        val recognized = myFixture.addFileToProject("core/conf/schema.xml", "<schema/>")
        assertTrue(SolrConfigsetDetector.isConfigsetFile(recognized))

        val unrecognized = myFixture.addFileToProject("unrelated/notes.xml", "<x/>")
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
     * A single recognized file in a directory is now enough, and needs no sibling. This replaces an
     * earlier test asserting the opposite; the corroboration it checked for moved to the project
     * level, where a dependency answers the same question exactly instead of approximately.
     */
    fun testASingleRecognizedFileIsEnough() {
        val file = myFixture.addFileToProject("solo/managed-schema", "<schema/>").virtualFile
        assertEquals(1, file.parent.children.size)
        assertTrue(SolrConfigsetDetector.isConfigsetFile(project, file))
    }

    /** The rest of a configset corroborates one, and activates like the schema does. */
    fun testIdentifyingFilesBeyondTheSchemaAreRecognized() {
        val elevate = myFixture.addFileToProject("core/conf/elevate.xml", "<elevate/>").virtualFile
        assertTrue(SolrConfigsetDetector.isConfigsetFile(project, elevate))
        assertEquals(SolrConfigsetFileKind.ELEVATE, SolrConfigsetDetector.kindOf(project, elevate))

        val params = myFixture.addFileToProject("core/conf/params.json", "{}").virtualFile
        assertEquals(SolrConfigsetFileKind.PARAMS, SolrConfigsetDetector.kindOf(project, params))
    }

    /**
     * A `params.json` alone is enough to corroborate a `schema.xml` outside a `conf/` directory —
     * the whole point of widening the identifying names.
     */
    fun testAnIdentifyingSiblingCorroboratesASchemaOutsideConf() {
        myFixture.addFileToProject("myset/enumsConfig.xml", "<enumsConfig/>")
        val schema = myFixture.addFileToProject("myset/schema.xml", "<schema/>").virtualFile
        assertTrue(SolrConfigsetDetector.isConfigsetFile(project, schema))
    }

    /** Resources are recognized inside a configset... */
    fun testResourceFileIsRecognizedInsideAConfigset() {
        myFixture.addFileToProject("core/conf/schema.xml", "<schema/>")
        val stopwords = myFixture.addFileToProject("core/conf/stopwords.txt", "a\n").virtualFile
        assertEquals(SolrConfigsetFileKind.STOPWORDS, SolrConfigsetDetector.kindOf(project, stopwords))
    }

    /** ...and never activate anything on their own, however Solr-shaped the name looks. */
    fun testResourceFileOutsideAConfigsetIsNotRecognized() {
        val stopwords = myFixture.addFileToProject("nlp/stopwords.txt", "a\n").virtualFile
        assertNull(SolrConfigsetDetector.kindOf(project, stopwords))
        assertFalse(SolrConfigsetDetector.isConfigsetFile(project, stopwords))
    }

    /**
     * A directory of resource files is not evidence either: two of them side by side must not add
     * up to a configset the way two identifying files do.
     */
    fun testResourceFilesAreNeverEvidenceOfAConfigset() {
        myFixture.addFileToProject("nlp/synonyms.txt", "a,b\n")
        val stopwords = myFixture.addFileToProject("nlp/stopwords.txt", "a\n").virtualFile
        assertNull(SolrConfigsetDetector.configsetFor(project, stopwords))
    }

    /** A resource file never activates features, even sitting inside a real configset. */
    fun testResourceFileInsideAConfigsetStillDoesNotActivate() {
        myFixture.addFileToProject("core/conf/schema.xml", "<schema/>")
        val synonyms = myFixture.addFileToProject("core/conf/synonyms.txt", "a,b\n").virtualFile
        assertFalse(SolrConfigsetDetector.isConfigsetFile(project, synonyms))
    }

    fun testLangDirectoryIsRecognizedInsideAConfigset() {
        myFixture.addFileToProject("core/conf/schema.xml", "<schema/>")
        myFixture.addFileToProject("core/conf/lang/stopwords_en.txt", "a\n")
        val lang = myFixture.tempDirFixture.getFile("core/conf/lang")!!
        assertEquals(SolrConfigsetFileKind.LANGUAGE_RESOURCES, SolrConfigsetDetector.kindOf(project, lang))
    }
}
