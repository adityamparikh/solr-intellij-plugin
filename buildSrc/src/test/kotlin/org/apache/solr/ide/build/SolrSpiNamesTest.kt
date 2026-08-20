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

    /**
     * Only an exactly named `NAME` counts.
     *
     * A class declaring other constants has no SPI name, and neither `FIELD_NAME` nor
     * `NAME_ATTRIBUTE` may be mistaken for one — a near-miss is not a match.
     */
    @Test
    fun `a factory declaring no NAME constant has no SPI name`() {
        assertNull(
            SolrSpiNames.of(
                listOf("DEFAULT_MAX_TOKEN_LENGTH" to "255", "FIELD_NAME" to "x", "NAME_ATTRIBUTE" to "y"),
            ),
        )
    }
}
