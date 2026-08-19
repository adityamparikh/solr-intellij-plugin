package org.apache.solr.ide.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The SPI name a factory declares, which is the other half of how a schema may name it.
 *
 * A configset writes `<filter class="solr.LowerCaseFilterFactory"/>` or
 * `<filter name="lowercase"/>`, and Solr accepts both. The second spelling cannot be derived from
 * the class name — `LowerCaseFilterFactory` is `lowercase` and not `lowerCase` — so it is read from
 * the `NAME` constant the factory declares, exactly as the attribute names are read from bytecode
 * rather than guessed.
 */
class SolrSpiNamesTest {

    @Test
    fun `the NAME constant is the SPI name`() {
        assertEquals("lowercase", SolrSpiNames.of(listOf("NAME" to "lowercase")))
    }

    @Test
    fun `a factory declaring no NAME has no SPI name`() {
        assertNull(SolrSpiNames.of(listOf("DEFAULT_MAX_TOKEN_LENGTH" to "255")))
    }

    /**
     * The irregular spellings are the whole reason this is read rather than derived. Each of these
     * is a name no mechanical transformation of the class name produces.
     */
    @Test
    fun `irregular names are taken verbatim`() {
        assertEquals("uax29URLEmail", SolrSpiNames.of(listOf("NAME" to "uax29URLEmail")))
        assertEquals("nGram", SolrSpiNames.of(listOf("NAME" to "nGram")))
        assertEquals("kStem", SolrSpiNames.of(listOf("NAME" to "kStem")))
    }

    /**
     * A constant named `NAME` that is not the SPI name would be a different field entirely; only an
     * exact match counts, so a `FIELD_NAME` or `NAME_ATTRIBUTE` nearby cannot be mistaken for one.
     */
    @Test
    fun `only an exactly named NAME constant counts`() {
        assertNull(SolrSpiNames.of(listOf("FIELD_NAME" to "x", "NAME_ATTRIBUTE" to "y")))
    }
}
