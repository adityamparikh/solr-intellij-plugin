package org.apache.solr.ide.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The generated catalog, as the plugin reads it.
 *
 * These assert against the shipped resource rather than a fixture, so they fail when the generator
 * regresses. That is the point: a generator's failure mode is a plausible short list, not an error,
 * and only a named expectation catches it.
 */
class SolrClassCatalogTest {

    private val latest = SolrVersionSelection.DEFAULT
    private val solr9 = SolrVersionSelection.fromLuceneMatchVersion("9.12.0")

    @Test
    fun `every supported line ships a catalog`() {
        for (line in SolrClassCatalog.SUPPORTED_LINES) {
            val entries = SolrClassCatalog.entriesFor(
                SolrVersionSelection.fromLuceneMatchVersion("$line.0.0"),
            )
            assertTrue("line $line ships no catalog", entries.size > 100)
        }
    }

    @Test
    fun `every kind is populated`() {
        for (kind in SolrClassKind.entries) {
            assertTrue("$kind is empty", SolrClassCatalog.of(kind, latest).isNotEmpty())
        }
    }

    /**
     * The entry an earlier generator missed.
     *
     * `StandardTokenizerFactory` lives in `lucene-core`, not `lucene-analysis-common`, so a scan
     * limited to the two obvious artifacts produced a list that looked right and omitted the most
     * used tokenizer there is. Naming it here is what turns that from a silent gap into a failure.
     */
    @Test
    fun `the standard tokenizer is present`() {
        val tokenizers = SolrClassCatalog.of(SolrClassKind.TOKENIZER, latest).map { it.shortName }
        assertTrue("expected solr.StandardTokenizerFactory in $tokenizers", "solr.StandardTokenizerFactory" in tokenizers)
    }

    /** The classes a schema names most, one from each population. */
    @Test
    fun `the everyday classes are present`() {
        for (name in listOf("solr.StrField", "solr.TextField", "solr.LowerCaseFilterFactory")) {
            assertNotNull("expected $name", SolrClassCatalog.find(name, latest))
        }
    }

    /** A configset may write either spelling, and they name the same class. */
    @Test
    fun `a class resolves by either spelling`() {
        val short = SolrClassCatalog.find("solr.StrField", latest)
        val qualified = SolrClassCatalog.find("org.apache.solr.schema.StrField", latest)
        assertEquals(short, qualified)
    }

    @Test
    fun `an unknown class resolves to nothing`() {
        assertNull(SolrClassCatalog.find("solr.NoSuchField", latest))
    }

    /** Every kind is filed under the tag it belongs to; a field type is not a tokenizer. */
    @Test
    fun `kinds do not overlap`() {
        val fieldTypes = SolrClassCatalog.of(SolrClassKind.FIELD_TYPE, latest).map { it.shortName }.toSet()
        val tokenizers = SolrClassCatalog.of(SolrClassKind.TOKENIZER, latest).map { it.shortName }.toSet()
        assertTrue("a class cannot be both", (fieldTypes intersect tokenizers).isEmpty())
    }

    /**
     * The lines genuinely differ, which is the whole reason this is generated per line rather than
     * once. Solr 10 removed four field types that 9.10 has.
     */
    @Test
    fun `the lines are not the same catalog`() {
        val nine = SolrClassCatalog.of(SolrClassKind.FIELD_TYPE, solr9).map { it.shortName }.toSet()
        val ten = SolrClassCatalog.of(SolrClassKind.FIELD_TYPE, latest).map { it.shortName }.toSet()
        assertTrue("expected solr.EnumField in 9.10", "solr.EnumField" in nine)
        assertTrue("expected solr.EnumField gone from 10", "solr.EnumField" !in ten)
    }

    /**
     * A version this plugin does not support falls back to the newest line rather than to nothing.
     * Someone reading a Solr 7 configset is better served by the current vocabulary than by silence.
     */
    @Test
    fun `an unsupported line falls back to the newest`() {
        val ancient = SolrClassCatalog.entriesFor(SolrVersionSelection.fromLuceneMatchVersion("7.0.0"))
        assertEquals(SolrClassCatalog.entriesFor(latest), ancient)
    }

    /** Every entry carries both spellings, and the short one is the `solr.` form. */
    @Test
    fun `entries are well formed`() {
        for (entry in SolrClassCatalog.entriesFor(latest)) {
            assertTrue("${entry.className} is not qualified", '.' in entry.className)
            assertTrue("${entry.shortName} is not a solr. name", entry.shortName.startsWith("solr."))
            assertEquals(entry.className.substringAfterLast('.'), entry.shortName.removePrefix("solr."))
        }
    }
}
