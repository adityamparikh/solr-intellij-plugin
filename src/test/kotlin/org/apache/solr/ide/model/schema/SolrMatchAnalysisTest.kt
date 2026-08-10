package org.apache.solr.ide.model.schema

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Classification rules for [SolrMatchAnalysis].
 *
 * Tested as a function rather than through anything that displays it, because this is the claim the
 * demo puts in front of a room and invites the audience to challenge. Every canonical Solr field
 * type is covered, and so are the orderings where the same set of filters produces a different
 * answer depending on where they sit.
 */
class SolrMatchAnalysisTest {

    private fun tokenizer(name: String) = SolrAnalyzerComponent("solr.$name")
    private fun filter(name: String) = SolrAnalyzerComponent("solr.$name")

    private fun chain(tokenizer: String, vararg filters: String) = SolrAnalyzerChain(
        tokenizer = tokenizer(tokenizer),
        filters = filters.map { filter(it) },
    )

    private fun analyze(tokenizer: String, vararg filters: String) =
        SolrMatchAnalysis.of(chain(tokenizer, *filters))

    // --- the canonical types the demo walks through -------------------------------------------

    /** `StrField` and the numeric and date types: no analyzer at all. */
    @Test
    fun `an unanalyzed type matches the whole value, case-sensitively`() {
        val capability = SolrMatchAnalysis.of(SolrFieldType("string", "solr.StrField"))
        assertEquals(SolrMatchGranularity.WHOLE_VALUE, capability.granularity)
        assertTrue(capability.caseSensitive)
        assertEquals(SolrPrefixSupport.NONE, capability.prefix)
        assertTrue(capability.confident)
    }

    /** `text_general`: the field the demo asks the room about, and the answer that surprises them. */
    @Test
    fun `a standard tokenized lowercased type is tokenized and case-insensitive`() {
        val capability = analyze("StandardTokenizerFactory", "LowerCaseFilterFactory")
        assertEquals(SolrMatchGranularity.TOKENS, capability.granularity)
        assertFalse(capability.caseSensitive)
        assertEquals("no filter grinds terms into prefixes, so `wid` cannot hit `widget`", SolrPrefixSupport.NONE, capability.prefix)
    }

    /** `text_prefix`: the field that does support `wid`, and the mechanism it uses. */
    @Test
    fun `an edge n-gram type supports prefix matching`() {
        val capability = analyze("StandardTokenizerFactory", "LowerCaseFilterFactory", "EdgeNGramFilterFactory")
        assertEquals(SolrMatchGranularity.TOKENS, capability.granularity)
        assertFalse(capability.caseSensitive)
        assertEquals(SolrPrefixSupport.EDGE_NGRAM, capability.prefix)
        assertEquals("EdgeNGramFilterFactory", capability.evidenceFor(SolrMatchTrait.PREFIX))
    }

    /** The `string_ci` type every schema eventually grows: whole value, but case-folded. */
    @Test
    fun `a keyword tokenizer with a lowercase filter is whole value and case-insensitive`() {
        val capability = analyze("KeywordTokenizerFactory", "LowerCaseFilterFactory")
        assertEquals(SolrMatchGranularity.WHOLE_VALUE, capability.granularity)
        assertFalse(capability.caseSensitive)
    }

    @Test
    fun `a tokenized type with no case folding stays case-sensitive`() {
        assertTrue(analyze("WhitespaceTokenizerFactory").caseSensitive)
        assertTrue(analyze("StandardTokenizerFactory", "StopFilterFactory").caseSensitive)
    }

    // --- orderings and compositions that change the answer -------------------------------------

    /**
     * The composition worth testing: a keyword tokenizer emits one term, and a word-delimiter
     * filter downstream takes it apart again. The field is tokenized *despite* its tokenizer, and
     * the evidence must name the filter rather than the tokenizer.
     */
    @Test
    fun `a splitting filter after a keyword tokenizer makes the field tokenized`() {
        val capability = analyze("KeywordTokenizerFactory", "WordDelimiterGraphFilterFactory")
        assertEquals(SolrMatchGranularity.TOKENS, capability.granularity)
        assertEquals("WordDelimiterGraphFilterFactory", capability.evidenceFor(SolrMatchTrait.GRANULARITY))
    }

    /** The same filter after a splitting tokenizer changes nothing — the field was already tokens. */
    @Test
    fun `a splitting filter after a splitting tokenizer leaves the tokenizer as the reason`() {
        val capability = analyze("StandardTokenizerFactory", "WordDelimiterGraphFilterFactory")
        assertEquals(SolrMatchGranularity.TOKENS, capability.granularity)
        assertEquals("StandardTokenizerFactory", capability.evidenceFor(SolrMatchTrait.GRANULARITY))
    }

    /** Case folding is decided by the first filter that folds; a second one does not re-attribute it. */
    @Test
    fun `the first case-folding filter is the one credited`() {
        val capability = analyze("StandardTokenizerFactory", "LowerCaseFilterFactory", "ICUFoldingFilterFactory")
        assertFalse(capability.caseSensitive)
        assertEquals("LowerCaseFilterFactory", capability.evidenceFor(SolrMatchTrait.CASE))
    }

    /** A later n-gram filter replaces an earlier one: the index holds whatever the last one made. */
    @Test
    fun `the last partial-match filter decides the prefix support`() {
        assertEquals(
            SolrPrefixSupport.N_GRAM,
            analyze("StandardTokenizerFactory", "EdgeNGramFilterFactory", "NGramFilterFactory").prefix,
        )
        assertEquals(
            SolrPrefixSupport.EDGE_NGRAM,
            analyze("StandardTokenizerFactory", "NGramFilterFactory", "EdgeNGramFilterFactory").prefix,
        )
    }

    /** Prefix support survives a keyword tokenizer: whole-value terms are still ground into prefixes. */
    @Test
    fun `an edge n-gram filter after a keyword tokenizer keeps both facts`() {
        val capability = analyze("KeywordTokenizerFactory", "EdgeNGramFilterFactory")
        assertEquals(SolrMatchGranularity.WHOLE_VALUE, capability.granularity)
        assertEquals(SolrPrefixSupport.EDGE_NGRAM, capability.prefix)
    }

    // --- tokenizers that decide more than granularity ------------------------------------------

    @Test
    fun `a lowercase tokenizer folds case with no filter present`() {
        val capability = analyze("LowerCaseTokenizerFactory")
        assertFalse(capability.caseSensitive)
        assertEquals("LowerCaseTokenizerFactory", capability.evidenceFor(SolrMatchTrait.CASE))
    }

    @Test
    fun `n-gram and edge n-gram tokenizers supply partial matching themselves`() {
        assertEquals(SolrPrefixSupport.EDGE_NGRAM, analyze("EdgeNGramTokenizerFactory").prefix)
        assertEquals(SolrPrefixSupport.N_GRAM, analyze("NGramTokenizerFactory").prefix)
        assertEquals(SolrPrefixSupport.PATH_HIERARCHY, analyze("PathHierarchyTokenizerFactory").prefix)
    }

    /** Only full n-grams make arbitrary substrings efficient; edge n-grams anchor at the start. */
    @Test
    fun `substring support follows from n-grams and not from edge n-grams`() {
        assertTrue(analyze("StandardTokenizerFactory", "NGramFilterFactory").substringSupported)
        assertFalse(analyze("StandardTokenizerFactory", "EdgeNGramFilterFactory").substringSupported)
    }

    // --- confidence ----------------------------------------------------------------------------

    /**
     * A wrong hint is worse than no hint, so anything unrecognized drops confidence rather than
     * being assumed harmless. The display can then decline to make a claim it cannot defend.
     */
    @Test
    fun `an unknown filter drops confidence`() {
        val capability = analyze("StandardTokenizerFactory", "AcmeProprietaryFilterFactory")
        assertFalse(capability.confident)
    }

    @Test
    fun `an unknown tokenizer drops confidence`() {
        assertFalse(analyze("AcmeProprietaryTokenizerFactory").confident)
    }

    @Test
    fun `an unknown char filter drops confidence`() {
        val capability = SolrMatchAnalysis.of(
            SolrAnalyzerChain(
                charFilters = listOf(filter("AcmeCharFilterFactory")),
                tokenizer = tokenizer("StandardTokenizerFactory"),
            ),
        )
        assertFalse(capability.confident)
    }

    @Test
    fun `known char filters keep confidence`() {
        val capability = SolrMatchAnalysis.of(
            SolrAnalyzerChain(
                charFilters = listOf(filter("HTMLStripCharFilterFactory")),
                tokenizer = tokenizer("StandardTokenizerFactory"),
                filters = listOf(filter("LowerCaseFilterFactory")),
            ),
        )
        assertTrue(capability.confident)
        assertFalse(capability.caseSensitive)
    }

    /** A chain declared as one analyzer class hides its behaviour where this cannot see it. */
    @Test
    fun `a chain with no tokenizer is not confident`() {
        val capability = SolrMatchAnalysis.of(SolrAnalyzerChain(className = "org.example.WholeThingAnalyzer"))
        assertFalse(capability.confident)
    }

    @Test
    fun `every neutral filter is recognized and changes nothing`() {
        for (neutral in listOf(
            "StopFilterFactory", "SynonymGraphFilterFactory", "ASCIIFoldingFilterFactory",
            "PorterStemFilterFactory", "KStemFilterFactory", "TrimFilterFactory",
            "FlattenGraphFilterFactory", "ShingleFilterFactory", "ReversedWildcardFilterFactory",
        )) {
            val capability = analyze("StandardTokenizerFactory", neutral)
            assertTrue("$neutral should be recognized", capability.confident)
            assertEquals(SolrMatchGranularity.TOKENS, capability.granularity)
            assertTrue("$neutral must not change case sensitivity", capability.caseSensitive)
            assertEquals(SolrPrefixSupport.NONE, capability.prefix)
        }
    }

    // --- naming --------------------------------------------------------------------------------

    /** The `solr.` shorthand is near-universal, but the fully qualified form is legal. */
    @Test
    fun `fully qualified factory names are matched as well as the solr shorthand`() {
        val capability = SolrMatchAnalysis.of(
            SolrAnalyzerChain(
                tokenizer = SolrAnalyzerComponent("org.apache.lucene.analysis.core.KeywordTokenizerFactory"),
                filters = listOf(SolrAnalyzerComponent("org.apache.lucene.analysis.core.LowerCaseFilterFactory")),
            ),
        )
        assertEquals(SolrMatchGranularity.WHOLE_VALUE, capability.granularity)
        assertFalse(capability.caseSensitive)
        assertTrue(capability.confident)
    }

    @Test
    fun `evidence is absent for traits nothing decided`() {
        val capability = analyze("StandardTokenizerFactory")
        assertNull(capability.evidenceFor(SolrMatchTrait.CASE))
        assertNull(capability.evidenceFor(SolrMatchTrait.PREFIX))
        assertEquals("StandardTokenizerFactory", capability.evidenceFor(SolrMatchTrait.GRANULARITY))
    }

    // --- the wording ----------------------------------------------------------------------------

    /**
     * The exact words the demo puts on screen. They live on the capability so the inline hint, the
     * documentation popup and the completion lookup cannot word the same field three ways — and
     * they are asserted here because the demo's whole opening depends on them.
     */
    @Test
    fun `the summary reads the way the demo says it does`() {
        assertEquals(
            "whole value, case-sensitive",
            SolrMatchAnalysis.of(SolrFieldType("string", "solr.StrField")).summary,
        )
        assertEquals(
            "tokenised, case-insensitive",
            analyze("StandardTokenizerFactory", "LowerCaseFilterFactory").summary,
        )
        assertEquals(
            "tokenised, case-insensitive, prefix-capable",
            analyze("StandardTokenizerFactory", "LowerCaseFilterFactory", "EdgeNGramFilterFactory").summary,
        )
    }

    @Test
    fun `substring and path-prefix support are worded distinctly`() {
        assertEquals(
            "tokenised, case-sensitive, substring-capable",
            analyze("StandardTokenizerFactory", "NGramFilterFactory").summary,
        )
        assertEquals(
            "tokenised, case-sensitive, path-prefix-capable",
            analyze("PathHierarchyTokenizerFactory").summary,
        )
    }
}
