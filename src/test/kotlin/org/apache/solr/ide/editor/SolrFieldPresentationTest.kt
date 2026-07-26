package org.apache.solr.ide.editor

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

    // --- the inline hint ----------------------------------------------------------------------

    /** The three claims the demo puts on screen, in the words it uses. */
    @Test
    fun `the hint says what the demo says`() {
        assertEquals("whole value, case-sensitive", SolrFieldPresentation.inlayText(field("string"), stringType))
        assertEquals("tokenised, case-insensitive", SolrFieldPresentation.inlayText(field("text_general"), textType))
        assertEquals(
            "tokenised, case-insensitive, prefix-capable",
            SolrFieldPresentation.inlayText(field("text_prefix"), prefixType),
        )
    }

    /**
     * Silence rather than a guess. An unrecognised factory means the chain was not understood, and
     * this is the output most likely to be quoted back at the plugin.
     */
    @Test
    fun `no hint is shown when the analysis is not confident`() {
        assertNull(SolrFieldPresentation.inlayText(field("custom"), unknownType))
    }

    /** An undeclared type is an inspection's job to report, not a hint's to paper over. */
    @Test
    fun `no hint is shown when the type is undeclared`() {
        assertNull(SolrFieldPresentation.inlayText(field("missing"), null))
    }

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
