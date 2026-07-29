package org.apache.solr.ide.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The value check, tested as the pure function it is.
 *
 * No fixture: establishing that `"foo"` is not an integer does not need an IDE, and the whole point
 * of keeping this in `model` is that it can be exercised without one.
 */
class SolrValueTypeTest {

    // --- the cases that must be rejected ----------------------------------------------------------

    @Test
    fun `a boolean rejects a value that is not true or false`() {
        assertFalse(SolrValueType.BOOLEAN.accepts("yes"))
        assertFalse(SolrValueType.BOOLEAN.accepts("1"))
        assertFalse(SolrValueType.BOOLEAN.accepts("ture"))
    }

    @Test
    fun `an integer rejects a word and a decimal`() {
        assertFalse(SolrValueType.INTEGER.accepts("foo"))
        assertFalse(SolrValueType.INTEGER.accepts("2.5"))
        assertFalse(SolrValueType.INTEGER.accepts(""))
    }

    @Test
    fun `a float rejects a word`() {
        assertFalse(SolrValueType.FLOAT.accepts("foo"))
    }

    @Test
    fun `an enum rejects a name outside its members`() {
        val members = listOf("as_same_term", "pick_best", "as_distinct_terms")
        assertFalse(SolrValueType.ENUM.accepts("as_same_terms", members))
    }

    // --- the cases that must be accepted ----------------------------------------------------------

    @Test
    fun `a boolean accepts either spelling in either case`() {
        assertTrue(SolrValueType.BOOLEAN.accepts("true"))
        assertTrue(SolrValueType.BOOLEAN.accepts("false"))
        assertTrue(SolrValueType.BOOLEAN.accepts("TRUE"))
    }

    @Test
    fun `an integer accepts a negative number`() {
        // Whether -1 is a *sensible* positionIncrementGap is a semantic question this does not ask.
        assertTrue(SolrValueType.INTEGER.accepts("-1"))
        assertTrue(SolrValueType.INTEGER.accepts("100"))
    }

    @Test
    fun `a float accepts a whole number`() {
        assertTrue(SolrValueType.FLOAT.accepts("1"))
        assertTrue(SolrValueType.FLOAT.accepts("0.75"))
    }

    @Test
    fun `free accepts anything`() {
        assertTrue(SolrValueType.FREE.accepts("anything at all"))
        assertTrue(SolrValueType.FREE.accepts(""))
    }

    @Test
    fun `surrounding whitespace does not make a value wrong`() {
        assertTrue(SolrValueType.INTEGER.accepts(" 100 "))
        assertTrue(SolrValueType.BOOLEAN.accepts(" true "))
    }

    // --- the silence rules ------------------------------------------------------------------------

    @Test
    fun `a substituted value is never judged`() {
        // Solr's resource loader expands these before a field type sees them, and the replacement may
        // come from a system property set outside the repository. Judging one would underline a file
        // that is correct.
        assertTrue(SolrValueType.INTEGER.accepts("\${solr.gap:100}"))
        assertTrue(SolrValueType.INTEGER.accepts("\${gap}"))
        assertTrue(SolrValueType.BOOLEAN.accepts("\${solr.indexed:true}"))
        assertTrue(SolrValueType.INTEGER.accepts("prefix-\${x}-suffix"))
    }

    @Test
    fun `a substituted value is not judged even when its default is wrong`() {
        // Deliberately out of scope. Parsing Solr's substitution syntax to reach the default is a
        // second guess on top of a first, and the value may not come from the default at all.
        assertTrue(SolrValueType.INTEGER.accepts("\${solr.gap:foo}"))
    }

    @Test
    fun `an enum with no declared members judges nothing`() {
        assertTrue(SolrValueType.ENUM.accepts("whatever"))
    }

    // --- the catalog token round trip -------------------------------------------------------------

    @Test
    fun `every token round trips`() {
        for (type in SolrValueType.entries) {
            assertEquals(type, SolrValueType.forToken(type.token))
        }
    }

    @Test
    fun `an absent or unrecognized token reads as free`() {
        // A catalog generated before types existed must degrade to "no value checking" rather than
        // to an exception.
        assertEquals(SolrValueType.FREE, SolrValueType.forToken(""))
        assertEquals(SolrValueType.FREE, SolrValueType.forToken("something-new"))
    }

    @Test
    fun `an attribute parses from either spelling the catalog may hold`() {
        assertEquals(
            SolrClassAttribute("minGramSize", SolrValueType.INTEGER),
            SolrClassAttribute.parse("minGramSize:int"),
        )
        assertEquals(
            SolrClassAttribute("words", SolrValueType.FREE),
            SolrClassAttribute.parse("words"),
        )
    }
}
