package org.apache.solr.ide.configset.intention

import org.apache.solr.ide.model.SolrAnalyzerChain
import org.apache.solr.ide.model.SolrAnalyzerComponent
import org.apache.solr.ide.model.SolrFact
import org.apache.solr.ide.model.SolrField
import org.apache.solr.ide.model.SolrFieldModel
import org.apache.solr.ide.model.SolrFieldType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The `_exact` companion's availability rules and type choice, as a pure function.
 *
 * The rule that carries this class is the one about which types may be reused. A tokenised field's
 * exact companion has to be a string, and "matches whole values" is not the same question — every
 * numeric and date type matches whole values too, because none of them has an analyzer chain.
 */
class SolrExactCompanionTest {

    // --- reuse ------------------------------------------------------------------------------

    @Test
    fun `reuses a declared string type`() {
        val model = model(
            fields = listOf(SolrField("name", "text_general")),
            types = listOf(tokenized("text_general"), strField("string")),
        )

        val plan = SolrExactCompanion.planFor(field(model, "name"), model)

        assertEquals("name_exact", plan?.companionField)
        assertEquals("string", plan?.typeName)
        assertEquals(false, plan?.generatesType)
    }

    /**
     * The trap this whole class exists for. A numeric type is unanalysed, so it matches whole values
     * exactly as a string type does — and copying text into it fails at index time.
     */
    @Test
    fun `never reuses a numeric or date type, whole-value though they are`() {
        val model = model(
            fields = listOf(SolrField("name", "text_general")),
            types = listOf(
                tokenized("text_general"),
                SolrFieldType("plong", "solr.LongPointField"),
                SolrFieldType("pdate", "solr.DatePointField"),
                strField("string"),
            ),
        )

        assertEquals("string", SolrExactCompanion.planFor(field(model, "name"), model)?.typeName)
    }

    @Test
    fun `takes the first string type in document order`() {
        val model = model(
            fields = listOf(SolrField("name", "text_general")),
            types = listOf(tokenized("text_general"), strField("first_string"), strField("second_string")),
        )

        assertEquals("first_string", SolrExactCompanion.planFor(field(model, "name"), model)?.typeName)
    }

    // --- generation -------------------------------------------------------------------------

    @Test
    fun `writes a string type when the schema declares none`() {
        val model = model(
            fields = listOf(SolrField("name", "text_general")),
            types = listOf(tokenized("text_general"), SolrFieldType("plong", "solr.LongPointField")),
        )

        val plan = SolrExactCompanion.planFor(field(model, "name"), model)

        assertEquals("name_exact", plan?.companionField)
        assertEquals("string", plan?.typeName)
        assertEquals(true, plan?.generatesType)
    }

    @Test
    fun `declines to generate when the name string is taken by something that is not one`() {
        val model = model(
            fields = listOf(SolrField("name", "text_general")),
            types = listOf(tokenized("text_general"), SolrFieldType("string", "solr.LongPointField")),
        )

        assertNull(SolrExactCompanion.planFor(field(model, "name"), model))
    }

    // --- the negatives ----------------------------------------------------------------------

    @Test
    fun `says nothing when the field already matches whole values`() {
        val model = model(
            fields = listOf(SolrField("code", "string")),
            types = listOf(strField("string")),
        )

        assertNull(SolrExactCompanion.planFor(field(model, "code"), model))
    }

    /** A keyword-tokenised text field is whole-value too, and equally has nothing to gain. */
    @Test
    fun `says nothing when a text type is keyword-tokenised`() {
        val keyword = SolrFieldType(
            name = "text_keyword",
            className = "solr.TextField",
            indexAnalyzer = SolrAnalyzerChain(tokenizer = SolrAnalyzerComponent("solr.KeywordTokenizerFactory")),
        )
        val model = model(
            fields = listOf(SolrField("name", "text_keyword")),
            types = listOf(keyword, strField("string")),
        )

        assertNull(SolrExactCompanion.planFor(field(model, "name"), model))
    }

    @Test
    fun `says nothing when the companion name is already declared`() {
        val model = model(
            fields = listOf(SolrField("name", "text_general"), SolrField("name_exact", "string")),
            types = listOf(tokenized("text_general"), strField("string")),
        )

        assertNull(SolrExactCompanion.planFor(field(model, "name"), model))
    }

    @Test
    fun `says nothing when the chain contains a factory match analysis does not know`() {
        val unknown = SolrFieldType(
            name = "text_custom",
            className = "solr.TextField",
            indexAnalyzer = SolrAnalyzerChain(
                tokenizer = SolrAnalyzerComponent("solr.StandardTokenizerFactory"),
                filters = listOf(SolrAnalyzerComponent("com.example.MysteryFilterFactory")),
            ),
        )
        val model = model(
            fields = listOf(SolrField("name", "text_custom")),
            types = listOf(unknown, strField("string")),
        )

        assertNull(SolrExactCompanion.planFor(field(model, "name"), model))
    }

    @Test
    fun `says nothing when the field names a type the schema does not declare`() {
        val model = model(
            fields = listOf(SolrField("name", "no_such_type")),
            types = listOf(strField("string")),
        )

        assertNull(SolrExactCompanion.planFor(field(model, "name"), model))
    }

    /** Same reasoning as the prefix half: `copyField` copies before analysis. */
    @Test
    fun `offers itself on a field that is not indexed`() {
        val model = model(
            fields = listOf(SolrField("name", "text_general", indexed = false, stored = true)),
            types = listOf(tokenized("text_general"), strField("string")),
        )

        assertEquals("name_exact", SolrExactCompanion.planFor(field(model, "name"), model)?.companionField)
    }

    // --- fixtures ---------------------------------------------------------------------------

    private fun field(model: SolrFieldModel, name: String): SolrField = model.fields.getValue(name).effective

    private fun model(fields: List<SolrField>, types: List<SolrFieldType>) = SolrFieldModel(
        fields = fields.associate { it.name to SolrFact(it) },
        fieldTypes = types.associate { it.name to SolrFact(it) },
    )

    private fun strField(name: String) = SolrFieldType(name, "solr.StrField")

    private fun tokenized(name: String) = SolrFieldType(
        name = name,
        className = "solr.TextField",
        indexAnalyzer = SolrAnalyzerChain(
            tokenizer = SolrAnalyzerComponent("solr.StandardTokenizerFactory"),
            filters = listOf(SolrAnalyzerComponent("solr.LowerCaseFilterFactory")),
        ),
    )
}
