package org.apache.solr.ide.configset.intention

import org.apache.solr.ide.model.schema.SolrAnalyzerChain
import org.apache.solr.ide.model.schema.SolrAnalyzerComponent
import org.apache.solr.ide.model.SolrFact
import org.apache.solr.ide.model.schema.SolrField
import org.apache.solr.ide.model.SolrFieldModel
import org.apache.solr.ide.model.schema.SolrFieldType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The availability rules and the type choice, as a pure function over the model.
 *
 * Every rule that does not need a caret lives here rather than in a fixture, because these are the
 * cases that decide whether the intention appears where it should not — and an intention offered
 * wrongly is acted on, which is worse than one that is missing.
 */
class SolrPrefixCompanionTest {

    // --- reuse ------------------------------------------------------------------------------

    @Test
    fun `reuses a declared edge-n-gram type rather than writing a second one`() {
        val model = model(
            fields = listOf(SolrField("description", "text_general")),
            types = listOf(tokenized("text_general"), edgeNGram("text_autocomplete")),
        )

        val plan = SolrPrefixCompanion.planFor(field(model, "description"), model)

        assertEquals("description_prefix", plan?.companionField)
        assertEquals("text_autocomplete", plan?.typeName)
        assertEquals(false, plan?.generatesType)
    }

    @Test
    fun `takes the first edge-n-gram type in document order when the schema declares several`() {
        val model = model(
            fields = listOf(SolrField("description", "text_general")),
            types = listOf(tokenized("text_general"), edgeNGram("first_ngram"), edgeNGram("second_ngram")),
        )

        assertEquals("first_ngram", SolrPrefixCompanion.planFor(field(model, "description"), model)?.typeName)
    }

    @Test
    fun `an n-gram type is not reused, because it indexes far more than prefixes`() {
        val model = model(
            fields = listOf(SolrField("description", "text_general")),
            types = listOf(tokenized("text_general"), nGram("text_substring")),
        )

        val plan = SolrPrefixCompanion.planFor(field(model, "description"), model)

        assertEquals("text_prefix", plan?.typeName)
        assertEquals(true, plan?.generatesType)
    }

    // --- generation -------------------------------------------------------------------------

    @Test
    fun `writes a type when the schema declares no edge-n-gram type at all`() {
        val model = model(
            fields = listOf(SolrField("description", "text_general")),
            types = listOf(tokenized("text_general")),
        )

        val plan = SolrPrefixCompanion.planFor(field(model, "description"), model)

        assertEquals("description_prefix", plan?.companionField)
        assertEquals("text_prefix", plan?.typeName)
        assertEquals(true, plan?.generatesType)
    }

    @Test
    fun `declines to generate when the generated type name is already taken`() {
        val model = model(
            fields = listOf(SolrField("description", "text_general")),
            types = listOf(tokenized("text_general"), tokenized("text_prefix")),
        )

        assertNull(SolrPrefixCompanion.planFor(field(model, "description"), model))
    }

    // --- the negatives ----------------------------------------------------------------------

    @Test
    fun `says nothing when the field already supports prefix matching`() {
        val model = model(
            fields = listOf(SolrField("description", "text_autocomplete")),
            types = listOf(edgeNGram("text_autocomplete")),
        )

        assertNull(SolrPrefixCompanion.planFor(field(model, "description"), model))
    }

    @Test
    fun `says nothing when the companion name is already declared`() {
        val model = model(
            fields = listOf(SolrField("description", "text_general"), SolrField("description_prefix", "text_general")),
            types = listOf(tokenized("text_general"), edgeNGram("text_autocomplete")),
        )

        assertNull(SolrPrefixCompanion.planFor(field(model, "description"), model))
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
            fields = listOf(SolrField("description", "text_custom")),
            types = listOf(unknown, edgeNGram("text_autocomplete")),
        )

        assertNull(SolrPrefixCompanion.planFor(field(model, "description"), model))
    }

    @Test
    fun `says nothing when the field names a type the schema does not declare`() {
        val model = model(
            fields = listOf(SolrField("description", "no_such_type")),
            types = listOf(edgeNGram("text_autocomplete")),
        )

        assertNull(SolrPrefixCompanion.planFor(field(model, "description"), model))
    }

    /**
     * A display-only field is the cleanest case for the pattern, not a case to suppress: `copyField`
     * copies the incoming value before analysis, so the source's own `indexed` setting does not
     * affect what the companion receives.
     */
    @Test
    fun `offers itself on a field that is not indexed`() {
        val model = model(
            fields = listOf(SolrField("description", "text_general", indexed = false, stored = true)),
            types = listOf(tokenized("text_general"), edgeNGram("text_autocomplete")),
        )

        assertEquals("description_prefix", SolrPrefixCompanion.planFor(field(model, "description"), model)?.companionField)
    }

    // --- fixtures ---------------------------------------------------------------------------

    private fun field(model: SolrFieldModel, name: String): SolrField = model.fields.getValue(name).effective

    private fun model(fields: List<SolrField>, types: List<SolrFieldType>) = SolrFieldModel(
        fields = fields.associate { it.name to SolrFact(it) },
        fieldTypes = types.associate { it.name to SolrFact(it) },
    )

    private fun tokenized(name: String) = textType(name)

    private fun edgeNGram(name: String) = textType(name, "solr.EdgeNGramFilterFactory")

    private fun nGram(name: String) = textType(name, "solr.NGramFilterFactory")

    private fun textType(name: String, vararg filters: String) = SolrFieldType(
        name = name,
        className = "solr.TextField",
        indexAnalyzer = SolrAnalyzerChain(
            tokenizer = SolrAnalyzerComponent("solr.StandardTokenizerFactory"),
            filters = (listOf("solr.LowerCaseFilterFactory") + filters).map { SolrAnalyzerComponent(it) },
        ),
    )
}
