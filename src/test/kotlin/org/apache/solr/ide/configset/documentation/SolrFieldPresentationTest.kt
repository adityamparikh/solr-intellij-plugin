package org.apache.solr.ide.configset.documentation

import org.apache.solr.ide.model.SolrAnalyzerChain
import org.apache.solr.ide.model.SolrAnalyzerComponent
import org.apache.solr.ide.model.SolrField
import org.apache.solr.ide.model.SolrFieldType
import org.apache.solr.ide.model.SolrVersionSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The text shown to the user, tested without an editor.
 *
 * The hint and the documentation popup render through the same code precisely so they cannot
 * disagree about what a field matches, so both are covered here.
 */
class SolrFieldPresentationTest {

    private val stringType = SolrFieldType("string", "solr.StrField")

    private val textType = SolrFieldType(
        name = "text_general",
        className = "solr.TextField",
        indexAnalyzer = SolrAnalyzerChain(
            tokenizer = SolrAnalyzerComponent("solr.StandardTokenizerFactory"),
            filters = listOf(SolrAnalyzerComponent("solr.LowerCaseFilterFactory")),
        ),
    )

    private val prefixType = textType.copy(
        name = "text_prefix",
        indexAnalyzer = textType.indexAnalyzer!!.copy(
            filters = textType.indexAnalyzer!!.filters + SolrAnalyzerComponent("solr.EdgeNGramFilterFactory"),
        ),
    )

    private val unknownType = SolrFieldType(
        name = "custom",
        className = "solr.TextField",
        indexAnalyzer = SolrAnalyzerChain(tokenizer = SolrAnalyzerComponent("com.example.MysteryTokenizerFactory")),
    )

    private fun field(type: String) = SolrField("f", type)

    // --- the documentation popup ----------------------------------------------------------------

    @Test
    fun `field documentation names the field, its type and what it matches`() {
        val html = SolrFieldPresentation.fieldDocumentation(
            SolrField("sku", "string", attributes = mapOf("indexed" to "true")),
            stringType,
            SolrVersionSelection.DEFAULT,
        )
        assertTrue(html.contains("sku"))
        assertTrue(html.contains("string"))
        assertTrue(html.contains("whole value, case-sensitive"))
    }

    /**
     * The caveat that keeps the claim defensible. A reader who knows wildcards work on any indexed
     * field will assume the hint is wrong unless it says what it means.
     */
    @Test
    fun `documentation states that the claim is about efficient matching`() {
        val html = SolrFieldPresentation.fieldDocumentation(field("string"), stringType, SolrVersionSelection.DEFAULT)
        assertTrue(html.contains("wid*"))
        assertTrue(html.contains("efficiently") || html.contains("<i>efficiently</i>"))
    }

    /** The three-level resolution is the part no external documentation can supply. */
    @Test
    fun `field documentation shows each property's value and where it came from`() {
        val html = SolrFieldPresentation.fieldDocumentation(
            SolrField("sku", "string", attributes = mapOf("stored" to "false")),
            stringType.copy(attributes = mapOf("sortMissingLast" to "true")),
            SolrVersionSelection.DEFAULT,
        )
        assertTrue("declared on the field", html.contains("on this field"))
        assertTrue("inherited from the type", html.contains("from the field type"))
        assertTrue("fallen back to Solr", html.contains("Solr default"))
        assertTrue("undetermined defaults are admitted", html.contains("depends on the field type"))
    }

    @Test
    fun `field type documentation shows both chains in pipeline order`() {
        val html = SolrFieldPresentation.fieldTypeDocumentation(prefixType, SolrVersionSelection.DEFAULT)
        assertTrue(html.contains("StandardTokenizerFactory"))
        assertTrue(html.contains("EdgeNGramFilterFactory"))
        assertTrue(html.contains("Index analyser"))
    }

    @Test
    fun `documentation declines to claim a match behaviour it could not determine`() {
        val html = SolrFieldPresentation.fieldTypeDocumentation(unknownType, SolrVersionSelection.DEFAULT)
        assertTrue(html.contains("not determined"))
    }

    @Test
    fun `documentation links to the guide and names which version it linked to`() {
        val html = SolrFieldPresentation.fieldDocumentation(field("string"), stringType, SolrVersionSelection.DEFAULT)
        assertTrue(html.contains("https://solr.apache.org/guide/solr/latest/"))
        assertTrue(html.contains("latest release"))
    }

    @Test
    fun `an undeclared type is reported rather than rendered as blank`() {
        val html = SolrFieldPresentation.fieldDocumentation(field("nope"), null, SolrVersionSelection.DEFAULT)
        assertTrue(html.contains("not declared"))
    }

    /** Field names come from a file the user controls, so they must not be able to inject markup. */
    @Test
    fun `text from the configset is escaped`() {
        val html = SolrFieldPresentation.fieldDocumentation(
            SolrField("<script>alert(1)</script>", "string"),
            stringType,
            SolrVersionSelection.DEFAULT,
        )
        assertTrue("raw markup must not survive", !html.contains("<script>"))
        assertTrue(html.contains("&lt;script&gt;"))
    }
}
