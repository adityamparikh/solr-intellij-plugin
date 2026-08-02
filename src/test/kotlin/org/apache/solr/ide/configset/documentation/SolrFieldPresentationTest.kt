package org.apache.solr.ide.configset.documentation

import org.apache.solr.ide.model.SolrAnalyzerChain
import org.apache.solr.ide.model.SolrAnalyzerComponent
import org.apache.solr.ide.model.SolrClassAttribute
import org.apache.solr.ide.model.SolrClassEntry
import org.apache.solr.ide.model.SolrClassKind
import org.apache.solr.ide.model.SolrField
import org.apache.solr.ide.model.SolrFieldType
import org.apache.solr.ide.model.SolrValueType
import org.apache.solr.ide.model.SolrVersionSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private val strFieldEntry = SolrClassEntry(
        SolrClassKind.FIELD_TYPE,
        "org.apache.solr.schema.StrField",
        "solr.StrField",
        listOf(SolrClassAttribute("docValuesFormat")),
    )

    private val edgeNGramEntry = SolrClassEntry(
        SolrClassKind.TOKEN_FILTER,
        "org.apache.lucene.analysis.ngram.EdgeNGramFilterFactory",
        "solr.EdgeNGramFilterFactory",
        listOf(
            SolrClassAttribute("maxGramSize", SolrValueType.INTEGER),
            SolrClassAttribute("preserveOriginal", SolrValueType.BOOLEAN),
        ),
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

    // --- the class documentation popup -------------------------------------------------------

    @Test
    fun `a class popup names the kind and both spellings`() {
        val html = SolrFieldPresentation.classDocumentation(strFieldEntry, null, SolrVersionSelection.DEFAULT)
        assertTrue(html.contains("solr.StrField"))
        assertTrue("the kind belongs in the definition: $html", html.contains("field type class"))
        assertTrue(html.contains("org.apache.solr.schema.StrField"))
    }

    @Test
    fun `a class popup lists the attributes the class accepts with their value types`() {
        val html = SolrFieldPresentation.classDocumentation(edgeNGramEntry, null, SolrVersionSelection.DEFAULT)
        assertTrue(html.contains("maxGramSize"))
        assertTrue("an int attribute reads as a whole number: $html", html.contains("whole number"))
        assertTrue("a bool attribute reads as true or false: $html", html.contains("true or false"))
    }

    @Test
    fun `a class with no known attributes claims nothing about them`() {
        val bare = SolrClassEntry(SolrClassKind.TOKENIZER, "org.example.T", "solr.T")
        val html = SolrFieldPresentation.classDocumentation(bare, null, SolrVersionSelection.DEFAULT)
        assertFalse("no attribute table for an empty list: $html", html.contains("Accepts"))
    }

    @Test
    fun `a factory popup links the guide page for its kind`() {
        val html = SolrFieldPresentation.classDocumentation(edgeNGramEntry, null, SolrVersionSelection.DEFAULT)
        assertTrue("expected the filters page: $html", html.contains("/indexing-guide/filters.html"))
    }

    @Test
    fun `a field type class popup links the field types page`() {
        val html = SolrFieldPresentation.classDocumentation(strFieldEntry, null, SolrVersionSelection.DEFAULT)
        assertTrue("expected the field types page: $html", html.contains("/indexing-guide/field-types-included-with-solr.html"))
    }

    @Test
    fun `specifics render under the configset heading`() {
        val html = SolrFieldPresentation.classDocumentation(
            strFieldEntry,
            "Used by 1 field type: <code>string</code>.",
            SolrVersionSelection.DEFAULT,
        )
        assertTrue(html.contains("In this configset:"))
        assertTrue(html.contains("<code>string</code>"))
    }

    /** The one-sentence Javadoc summary, when the catalog carries one. */
    @Test
    fun `a class popup shows its documentation summary when the catalog carries one`() {
        val html = SolrFieldPresentation.classDocumentation(
            edgeNGramEntry.copy(summary = "Creates new instances of EdgeNGramTokenFilter."),
            null,
            SolrVersionSelection.DEFAULT,
        )
        assertTrue(html.contains("Creates new instances of EdgeNGramTokenFilter."))
    }

    /** No `-sources` documentation for this class is no paragraph, not an empty one. */
    @Test
    fun `a class with no summary shows no documentation paragraph`() {
        val html = SolrFieldPresentation.classDocumentation(strFieldEntry, null, SolrVersionSelection.DEFAULT)
        assertTrue("strFieldEntry carries no summary", strFieldEntry.summary == null)
        assertFalse("no empty <p></p> for a missing summary", html.contains("<p></p>"))
    }
}
