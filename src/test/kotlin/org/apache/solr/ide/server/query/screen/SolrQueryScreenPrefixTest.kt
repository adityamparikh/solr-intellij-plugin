package org.apache.solr.ide.server.query.screen

import org.junit.Assert.assertEquals
import org.junit.Test

/** The name being typed in a field-naming input, whatever the input's own syntax around it. */
class SolrQueryScreenPrefixTest {

    private fun prefix(textWithCaret: String): String {
        val offset = textWithCaret.indexOf('|')
        return SolrQueryScreenFieldCompletion.prefixOf(textWithCaret.replace("|", ""), offset)
    }

    @Test
    fun `a field list starts a name after each comma`() {
        assertEquals("ti", prefix("id,ti|"))
    }

    @Test
    fun `a sort starts a name after a comma and a space`() {
        assertEquals("na", prefix("price asc, na|"))
    }

    @Test
    fun `query fields start a name after each space`() {
        assertEquals("bo", prefix("title^2 bo|"))
    }

    @Test
    fun `a function's argument is a name`() {
        assertEquals("pri", prefix("max(pri|"))
    }

    @Test
    fun `an empty input has an empty prefix`() {
        assertEquals("", prefix("|"))
    }

    @Test
    fun `only what is before the caret counts`() {
        assertEquals("ti", prefix("id,ti|tle"))
    }
}
