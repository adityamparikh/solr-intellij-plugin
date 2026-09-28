package org.apache.solr.ide.model.query

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where, in a query being typed, a field name may go — the question completion asks at the caret.
 *
 * **The half of the query grammar that decides whether to offer at all.** In `category:bo|` the caret
 * is in a *value*, and offering field names there would complete `category:category`. The sandbox
 * report was the opposite half — no popup where one belonged — and fixing that by popping up
 * everywhere in the string would have traded one fault for this one.
 *
 * Null is "not a field position" and an empty string is "a field position, nothing typed yet"; the
 * two are different answers and every test here names which it expects.
 */
class SolrQueryExpressionTokenTest {

    /** The token at the end of [typed], as if the caret sat there. */
    private fun at(typed: String) = SolrQueryExpressions.tokenAt(typed, typed.length)

    // --- where a field name goes --------------------------------------------------------------------

    @Test
    fun `an empty query is a field position with nothing typed`() {
        assertEquals("", at(""))
    }

    @Test
    fun `the start of a query is a field being typed`() {
        assertEquals("cat", at("cat"))
    }

    @Test
    fun `the clause after an operator starts a new field`() {
        assertEquals("na", at("category:books AND na"))
    }

    @Test
    fun `a space after a value starts a new clause`() {
        assertEquals("", at("category:books "))
    }

    @Test
    fun `a prohibited or required clause names its field after the sign`() {
        assertEquals("ca", at("-ca"))
        assertEquals("ca", at("+ca"))
    }

    @Test
    fun `a group opens a clause`() {
        assertEquals("na", at("(na"))
    }

    @Test
    fun `a hyphen inside a name is part of the name`() {
        assertEquals("some-fi", at("some-fi"))
    }

    @Test
    fun `the caret decides, not the end of the text`() {
        assertEquals("ca", SolrQueryExpressions.tokenAt("category:books", 2))
    }

    // --- where it does not ------------------------------------------------------------------------

    @Test
    fun `after a colon the caret is in a value`() {
        assertNull(at("category:"))
        assertNull(at("category:bo"))
    }

    @Test
    fun `a group after a colon holds values`() {
        assertNull(at("category:(books OR mu"))
    }

    @Test
    fun `a clause after a value group closes is a field again`() {
        assertEquals("na", at("category:(books OR music) AND na"))
    }

    @Test
    fun `a range holds values`() {
        assertNull(at("price:[1 TO 9"))
        assertNull(at("price:[1 T"))
    }

    @Test
    fun `a phrase holds values`() {
        assertNull(at("\"hello wor"))
    }

    @Test
    fun `a local parameter block declares parameters, not fields`() {
        assertNull(at("{!edismax q"))
    }

    @Test
    fun `after a local parameter block a field may start`() {
        assertEquals("ti", at("{!edismax}ti"))
    }

    @Test
    fun `a boost holds a number`() {
        assertNull(at("name:dune^"))
        assertNull(at("dune^2"))
    }

    @Test
    fun `syntax that is not a name is not completed`() {
        assertNull(at("*"))
        assertNull(at("*:*"))
        assertNull(at("\$qq"))
        assertNull(at("1"))
    }
}
