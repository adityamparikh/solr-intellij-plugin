package org.apache.solr.ide.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What each attribute accepts, tested without a fixture.
 *
 * Only half the question this file will eventually answer. Whether a *set* of attributes is complete
 * is a separate and much harder claim, and arrives with the inspection that needs it.
 */
class SolrAttributeVocabularyTest {

    private val latest = SolrVersionSelection.DEFAULT

    private fun typeOf(tag: String, attribute: String, className: String? = null) =
        SolrAttributeVocabulary.typeOf(tag, attribute, className, latest)

    // --- what an attribute accepts ----------------------------------------------------------------

    @Test
    fun `a boolean field property is typed`() {
        assertEquals(SolrValueType.BOOLEAN, typeOf("field", "indexed")?.valueType)
        assertFalse(typeOf("field", "indexed")!!.accepts("yes"))
        assertTrue(typeOf("field", "indexed")!!.accepts("true"))
    }

    @Test
    fun `positionIncrementGap is an integer`() {
        val accepted = typeOf("fieldType", "positionIncrementGap")
        assertEquals(SolrValueType.INTEGER, accepted?.valueType)
        assertFalse(accepted!!.accepts("foo"))
        assertTrue(accepted.accepts("100"))
    }

    @Test
    fun `synonymQueryStyle offers its members`() {
        val accepted = typeOf("fieldType", "synonymQueryStyle")
        assertEquals(SolrValueType.ENUM, accepted?.valueType)
        assertTrue(accepted!!.members.contains("pick_best"))
        assertFalse(accepted.accepts("pick_worst"))
    }

    @Test
    fun `a factory attribute is typed from the catalog`() {
        val accepted = typeOf("filter", "minGramSize", "solr.EdgeNGramFilterFactory")
        assertEquals(SolrValueType.INTEGER, accepted?.valueType)
        assertFalse(accepted!!.accepts("2.5"))
    }

    @Test
    fun `an open-valued property is not typed at all`() {
        // `default` accepts any value of the field's type, so there is nothing to check.
        assertNull(typeOf("field", "default"))
        assertNull(typeOf("fieldType", "docValuesFormat"))
    }

    @Test
    fun `structural attributes are never typed`() {
        assertNull(typeOf("field", "name"))
        assertNull(typeOf("field", "type"))
        assertNull(typeOf("filter", "class", "solr.EdgeNGramFilterFactory"))
    }

    @Test
    fun `an unknown class yields no type`() {
        assertNull(typeOf("filter", "minGramSize", "com.example.MyFilterFactory"))
    }

    @Test
    fun `a filter is not typed against a tokenizer's attributes`() {
        // `mode` belongs to JapaneseTokenizerFactory. Asking for it on a filter must not resolve.
        assertNull(typeOf("filter", "mode", "solr.JapaneseTokenizerFactory"))
    }
}
