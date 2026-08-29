package org.apache.solr.ide.server.drift

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/**
 * Zipping a configset the way Solr's ConfigSets API expects.
 *
 * **The mistake worth preventing does not fail at upload.** A zip built from the parent directory —
 * `conf/managed-schema.xml` rather than `managed-schema.xml` — uploads with `status: 0` and appears
 * in `action=LIST`, and Solr then refuses to build a collection from it, with a core creation error
 * naming a missing schema and nothing pointing back at how the archive was made.
 */
class SolrConfigsetArchiveTest : SolrConfigsetTestCase() {

    private fun givenConfigset(): com.intellij.openapi.vfs.VirtualFile {
        myFixture.addFileToProject("books/conf/managed-schema.xml", "<schema name=\"books\"/>")
        myFixture.addFileToProject("books/conf/solrconfig.xml", "<config/>")
        myFixture.addFileToProject("books/conf/lang/stopwords_en.txt", "the\na\n")
        return myFixture.findFileInTempDir("books/conf")
    }

    private fun entriesIn(archive: ByteArray): Map<String, String> = buildMap {
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                put(entry.name, zip.readBytes().decodeToString())
                entry = zip.nextEntry
            }
        }
    }

    /**
     * Paths are relative to the configset root, with no wrapping directory.
     *
     * The whole point: Solr unpacks the archive as the configset's *contents*.
     */
    fun testEntriesAreRelativeToTheConfigsetRoot() {
        val entries = entriesIn(SolrConfigsetArchive.of(givenConfigset()))

        assertContainsElements(entries.keys, "managed-schema.xml", "solrconfig.xml")
        assertFalse(entries.keys.toString(), entries.keys.any { it.startsWith("conf/") })
        assertFalse(entries.keys.toString(), entries.keys.any { it.startsWith("books/") })
    }

    /** A nested directory keeps its path, because Solr reads `lang/stopwords_en.txt` as that. */
    fun testNestedFilesKeepTheirRelativePath() {
        val entries = entriesIn(SolrConfigsetArchive.of(givenConfigset()))

        assertTrue(entries.keys.toString(), entries.containsKey("lang/stopwords_en.txt"))
    }

    fun testFileContentsSurviveTheRoundTrip() {
        val entries = entriesIn(SolrConfigsetArchive.of(givenConfigset()))

        assertEquals("<schema name=\"books\"/>", entries["managed-schema.xml"])
        assertEquals("the\na\n", entries["lang/stopwords_en.txt"])
    }

    /**
     * Zipping the same directory twice produces the same bytes.
     *
     * Not something Solr cares about, but it is what makes the archive a function of the directory
     * rather than of the order the filesystem happened to hand files over — and a test that could
     * not rely on that would have to assert on sets rather than on content.
     */
    fun testTheArchiveIsAFunctionOfTheDirectory() {
        val configset = givenConfigset()

        assertEquals(
            entriesIn(SolrConfigsetArchive.of(configset)),
            entriesIn(SolrConfigsetArchive.of(configset)),
        )
    }

    fun testAnEmptyDirectoryProducesAnEmptyArchive() {
        myFixture.addFileToProject("empty/conf/placeholder.txt", "")
        val root = myFixture.findFileInTempDir("empty/conf")

        val entries = entriesIn(SolrConfigsetArchive.of(root))

        assertEquals(setOf("placeholder.txt"), entries.keys)
    }
}
