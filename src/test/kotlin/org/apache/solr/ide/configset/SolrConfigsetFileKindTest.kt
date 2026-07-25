package org.apache.solr.ide.configset

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Filename-matching rules for [SolrConfigsetFileKind].
 *
 * These need no IDE fixture — the enum is pure — so they run as plain JUnit rather than
 * [com.intellij.testFramework.fixtures.BasePlatformTestCase], keeping them fast.
 */
class SolrConfigsetFileKindTest {

    @Test
    fun `schema file names map to SCHEMA`() {
        for (name in listOf("schema.xml", "managed-schema", "managed-schema.xml")) {
            assertEquals(
                "expected $name to be recognized as SCHEMA",
                SolrConfigsetFileKind.SCHEMA,
                SolrConfigsetFileKind.forFileName(name),
            )
        }
    }

    @Test
    fun `solrconfig xml maps to SOLR_CONFIG`() {
        assertEquals(SolrConfigsetFileKind.SOLR_CONFIG, SolrConfigsetFileKind.forFileName("solrconfig.xml"))
    }

    @Test
    fun `unrelated file names are not recognized`() {
        for (name in listOf("notes.xml", "pom.xml", "schema.json", "", "xml")) {
            assertNull("expected $name to be unrecognized", SolrConfigsetFileKind.forFileName(name))
        }
    }

    /**
     * Solr file names are lower-case on disk, and the detector documents matching as case-sensitive.
     * Pinning this stops a well-meaning `equalsIgnoreCase` from widening detection unnoticed.
     */
    @Test
    fun `matching is case-sensitive`() {
        assertNull(SolrConfigsetFileKind.forFileName("Schema.xml"))
        assertNull(SolrConfigsetFileKind.forFileName("SOLRCONFIG.XML"))
        assertNull(SolrConfigsetFileKind.forFileName("Managed-Schema"))
    }

    /** A bare name must not match — detection keys off the file name only, never a path. */
    @Test
    fun `paths are not accepted in place of file names`() {
        assertNull(SolrConfigsetFileKind.forFileName("conf/schema.xml"))
    }

    @Test
    fun `ALL_FILE_NAMES is the union of every kind's names`() {
        val union = SolrConfigsetFileKind.entries.flatMap { it.fileNames }.toSet()
        assertEquals(union, SolrConfigsetFileKind.ALL_FILE_NAMES)
        assertEquals(4, SolrConfigsetFileKind.ALL_FILE_NAMES.size)
        assertTrue(SolrConfigsetFileKind.ALL_FILE_NAMES.containsAll(listOf("schema.xml", "solrconfig.xml")))
    }

    /**
     * `ALL_FILE_NAMES` drives the `plugin.xml` file-type associations, so every name it advertises
     * must actually resolve to a kind — otherwise the two lists could silently drift apart.
     */
    @Test
    fun `every advertised file name resolves to a kind`() {
        for (name in SolrConfigsetFileKind.ALL_FILE_NAMES) {
            assertNotNull("$name is advertised but resolves to no kind", SolrConfigsetFileKind.forFileName(name))
        }
    }
}
