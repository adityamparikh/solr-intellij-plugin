package org.apache.solr.ide.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which attributes each element accepts, tested without a fixture.
 *
 * The two questions this answers are deliberately separate, and the tests are grouped that way:
 * *what does this attribute accept* is answerable far more often than *is this the complete set*.
 */
class SolrAttributeVocabularyTest {

    private val latest = SolrVersionSelection.DEFAULT

    private fun typeOf(tag: String, attribute: String, className: String? = null) =
        SolrAttributeVocabulary.typeOf(tag, attribute, className, latest)

    private fun vocabularyFor(tag: String, className: String? = null) =
        SolrAttributeVocabulary.closedVocabularyFor(tag, className, latest)

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

    // --- whether the set of attributes is complete -------------------------------------------------

    @Test
    fun `a field has a closed vocabulary`() {
        val legal = vocabularyFor("field")
        assertTrue(legal!!.contains("indexed"))
        assertTrue("structural attributes must be legal", legal.contains("name") && legal.contains("type"))
        assertFalse(legal.contains("indexd"))
    }

    /**
     * Solr accepts more on a `<field>` than the Reference Guide's table lists.
     *
     * `FieldProperties.propertyNames` in solr-core carries `tokenized`, `binary` and
     * `storeOffsetsWithPositions`, and `isPropertyIgnored` waves `postingsFormat` and
     * `docValuesFormat` through. Each loads without error, so reporting any of them would underline
     * a file Solr accepts.
     */
    @Test
    fun `every field attribute solr-core accepts is legal`() {
        val accepted = listOf(
            "tokenized", "binary", "storeOffsetsWithPositions", "postingsFormat", "docValuesFormat",
        )
        for (tag in listOf("field", "dynamicField")) {
            val legal = vocabularyFor(tag)!!
            for (name in accepted) {
                assertTrue("$name must be legal on a <$tag>", legal.contains(name))
            }
        }
    }

    @Test
    fun `a known analysis class has a closed vocabulary that includes class itself`() {
        val legal = vocabularyFor("filter", "solr.EdgeNGramFilterFactory")
        assertTrue(legal!!.contains("minGramSize"))
        // The generator strips `class` on purpose, so this is the guard that stops the inspection
        // flagging the very attribute it used to find the entry.
        assertTrue("class must be legal", legal.contains("class"))
    }

    /**
     * A field type delegates to classes its own configuration names.
     *
     * `providerClass` selects the `ExchangeRateProvider` that reads `currencyConfig`, and no walk
     * from the field type reaches a collaborator chosen at runtime. Solr's own
     * `sample_techproducts_configs` writes that attribute, so a closed answer here would underline a
     * configset Solr ships.
     */
    @Test
    fun `a field type never has a closed vocabulary`() {
        assertNull(vocabularyFor("fieldType", "solr.CurrencyFieldType"))
        assertNull(vocabularyFor("fieldType", "solr.StrField"))
    }

    @Test
    fun `a class outside Solr never has a closed vocabulary`() {
        assertNull(vocabularyFor("filter", "com.example.MyFilterFactory"))
    }

    @Test
    fun `an element with no class has no closed vocabulary`() {
        assertNull(vocabularyFor("filter", null))
        assertNull(vocabularyFor("copyField"))
        assertNull(vocabularyFor("analyzer"))
        assertNull(vocabularyFor("schema"))
    }
}
