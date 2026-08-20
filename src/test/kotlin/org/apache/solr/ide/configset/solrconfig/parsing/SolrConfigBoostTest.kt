package org.apache.solr.ide.configset.solrconfig.parsing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The boost under a caret, which is the position completion deliberately declines.
 *
 * Separate from [SolrConfigParserTest] because that reads whole values into references while this
 * answers about one offset, and the boundary cases are all offsets.
 */
class SolrConfigBoostTest {

    /** The offset of the `3` in `name^3 description` — `n` is 0, so the `^` is 4. */
    private val insideFirstBoost = 5

    @Test
    fun `a caret inside a boost yields the boost and the field it applies to`() {
        val occurrence = SolrConfigParser.boostAt("qf", "name^3 description", insideFirstBoost)

        assertEquals("3", occurrence?.boost)
        assertEquals("name", occurrence?.fieldName)
    }

    @Test
    fun `a caret on the whitespace after a boost is not in it`() {
        // Offset 6 is the space in `name^3 title^5`. Answering there would put a popup on a gap
        // between two tokens, attributed to whichever one happens to be to the left.
        assertNull(SolrConfigParser.boostAt("qf", "name^3 title^5", 6))
    }

    @Test
    fun `a caret on the marker itself is in the boost`() {
        assertEquals("3", SolrConfigParser.boostAt("qf", "name^3 description", 4)?.boost)
    }

    @Test
    fun `a caret in the name is not in the boost`() {
        assertNull(SolrConfigParser.boostAt("qf", "name^3 description", 2))
    }

    @Test
    fun `a caret at the end of the value is in the last boost`() {
        assertEquals("3", SolrConfigParser.boostAt("qf", "name^3", 6)?.boost)
    }

    @Test
    fun `the second token answers with its own boost`() {
        val occurrence = SolrConfigParser.boostAt("qf", "name^3 title^5", 13)

        assertEquals("5", occurrence?.boost)
        assertEquals("title", occurrence?.fieldName)
    }

    @Test
    fun `a token carrying no boost yields nothing`() {
        assertNull(SolrConfigParser.boostAt("qf", "name description", 2))
    }

    @Test
    fun `a marker with nothing after it still answers`() {
        // Half-typed, and the position a reader is most likely to ask about. The empty boost is
        // carried rather than discarded so the popup can say what belongs there.
        val occurrence = SolrConfigParser.boostAt("qf", "name^", 5)

        assertEquals("", occurrence?.boost)
        assertEquals("name", occurrence?.fieldName)
    }

    @Test
    fun `a value split across lines answers on the boost of a later line`() {
        // Real handlers wrap their qf, and the separator set includes the newline for that reason.
        val occurrence = SolrConfigParser.boostAt("qf", "\n  name^3\n  title^5\n", 18)

        assertEquals("5", occurrence?.boost)
        assertEquals("title", occurrence?.fieldName)
    }

    @Test
    fun `a function query names no field, even where a boost follows it`() {
        val occurrence = SolrConfigParser.boostAt("bf", "recip(rord(price),1,1000,1000)^2.5", 32)

        assertEquals("2.5", occurrence?.boost)
        assertNull(occurrence?.fieldName)
    }

    @Test
    fun `a function written in a field parameter names no field either`() {
        // `qf` is a field parameter, but `max(price,0)` is not a field name — the same judgement
        // that keeps the unknown-field inspection off it.
        val occurrence = SolrConfigParser.boostAt("qf", "max(price,0)^2", 13)

        assertEquals("2", occurrence?.boost)
        assertNull(occurrence?.fieldName)
    }

    @Test
    fun `a parameter that is not boostable has no boost to find`() {
        // A `^` in an `fl` is an ordinary character, and reading it as a boost would answer where
        // Solr has no such syntax.
        assertNull(SolrConfigParser.boostAt("fl", "name^3", 5))
    }
}
