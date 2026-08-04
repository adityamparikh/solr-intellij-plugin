package org.apache.solr.ide.configset.inspection

import com.intellij.codeInspection.LocalInspectionTool
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/**
 * The analyzer chain ordering inspection, on chains that cannot work and chains that merely look
 * unusual.
 *
 * The clean cases carry the weight here. Both rules are claims about what a pipeline *cannot* do,
 * and the way to get such a rule wrong is to fire it on a chain that is working — so the correct
 * order, the doubly-flattened chain, the unset attribute and the chain whose graph filter is in the
 * other analyzer are all asserted silent.
 */
class SolrAnalyzerChainOrderInspectionTest : SolrConfigsetTestCase() {

    /**
     * @param chains the analyzer elements under test, carrying their own expected warnings
     * @param alongside inspections enabled *as well*, for the cases asserting which of two speaks
     */
    private fun check(chains: String, vararg alongside: LocalInspectionTool) {
        myFixture.enableInspections(SolrAnalyzerChainOrderInspection(), *alongside)
        myFixture.configureByText(
            "managed-schema.xml",
            """
            <schema name="t" version="1.6">
              <fieldType name="text" class="solr.TextField">$chains</fieldType>
              <field name="body" type="text"/>
            </schema>
            """.trimIndent(),
        )
        myFixture.checkHighlighting(true, false, false)
    }

    private fun index(body: String) = """<analyzer type="index">$body</analyzer>"""

    private fun query(body: String) = """<analyzer type="query">$body</analyzer>"""

    private fun whitespace() = """<tokenizer class="solr.WhitespaceTokenizerFactory"/>"""

    private fun filter(className: String) = """<filter class="$className"/>"""

    private fun flattenerAbove(producer: String, flattener: String = "solr.FlattenGraphFilterFactory") =
        """<filter class="<warning descr="Solr: '$producer' below produces the token graph this """ +
            """flattens, so this filter has to come after it">$flattener</warning>"/>"""

    private fun caseSplit(folder: String, value: String = "1") =
        """<filter class="solr.WordDelimiterGraphFilterFactory" splitOnCaseChange="<warning """ +
            """descr="Solr: '$folder' above has already folded case, so splitOnCaseChange can never """ +
            """split anything">$value</warning>"/>"""

    // --- clean chains ----------------------------------------------------------------------------

    /** The canonical order Solr documents: the graph filter, then the flattener. */
    fun testAFlattenerBelowItsGraphFilterIsClean() {
        check(
            index(
                whitespace() +
                    filter("solr.WordDelimiterGraphFilterFactory") +
                    filter("solr.FlattenGraphFilterFactory"),
            ),
        )
    }

    /**
     * A chain may flatten twice, once after each producer. The second flattener has a producer above
     * it, which is the whole reason the rule looks upward rather than merely at what follows.
     */
    fun testAChainThatFlattensAfterEachProducerIsClean() {
        check(
            index(
                whitespace() +
                    filter("solr.WordDelimiterGraphFilterFactory") +
                    filter("solr.FlattenGraphFilterFactory") +
                    filter("solr.SynonymGraphFilterFactory") +
                    filter("solr.FlattenGraphFilterFactory"),
            ),
        )
    }

    /**
     * A flattener with no graph filter anywhere is inert too, and deliberately not reported: proving
     * it would need to know the tokenizer produces no graph either, and the Japanese one does.
     */
    fun testAFlattenerWithNoGraphFilterAtAllIsNotReported() {
        check(index(whitespace() + filter("solr.FlattenGraphFilterFactory")))
    }

    /**
     * The case the flattener rule exists to stay off: a tokenizer that emits a graph itself.
     *
     * `JapaneseTokenizerFactory` splits compounds in its default `search` mode and emits the whole
     * word alongside its parts, and Lucene documents this flattener as how that reaches an index.
     * The flattener is doing real work here — the graph filter below it is a second producer that
     * the second flattener answers, not evidence that the first one is misplaced.
     */
    fun testAFlattenerBelowAGraphProducingTokenizerIsClean() {
        check(
            index(
                """<tokenizer class="solr.JapaneseTokenizerFactory" mode="search"/>""" +
                    filter("solr.FlattenGraphFilterFactory") +
                    filter("solr.SynonymGraphFilterFactory") +
                    filter("solr.FlattenGraphFilterFactory"),
            ),
        )
    }

    /** Nori decompounds the same way Kuromoji does, and is written in the same position. */
    fun testAFlattenerBelowTheKoreanTokenizerIsClean() {
        check(
            index(
                """<tokenizer class="solr.KoreanTokenizerFactory" decompoundMode="mixed"/>""" +
                    filter("solr.FlattenGraphFilterFactory") +
                    filter("solr.WordDelimiterGraphFilterFactory"),
            ),
        )
    }

    /** The two analyzers are two pipelines; a graph filter in one says nothing about the other. */
    fun testAGraphFilterInTheOtherAnalyzerIsNotCounted() {
        check(
            index(whitespace() + filter("solr.FlattenGraphFilterFactory")) +
                query(whitespace() + filter("solr.SynonymGraphFilterFactory")),
        )
    }

    /** Case splitting requested above the fold still has its case boundaries. */
    fun testSplittingOnCaseChangeAboveTheFoldIsClean() {
        check(
            index(
                whitespace() +
                    """<filter class="solr.WordDelimiterGraphFilterFactory" splitOnCaseChange="1"/>""" +
                    filter("solr.LowerCaseFilterFactory"),
            ),
        )
    }

    /**
     * `true` is not a value this attribute has, and the other inspection is the one that says so.
     *
     * Solr reads `splitOnCaseChange` with `Integer.parseInt` and refuses to load the core when it
     * cannot, so the defect here is a core that will not start rather than a chain that runs wrong.
     * Both inspections are enabled precisely so this asserts *which* of them speaks: `checkHighlighting`
     * fails on a highlight the fixture did not mark, so a second warning from the ordering rule would
     * fail this test.
     */
    fun testAValueSolrCannotReadIsLeftToTheAttributeValueInspection() {
        check(
            index(
                whitespace() +
                    filter("solr.LowerCaseFilterFactory") +
                    """<filter class="solr.WordDelimiterGraphFilterFactory" splitOnCaseChange=""" +
                    """"<warning descr="Solr: 'splitOnCaseChange' accepts a whole number">true""" +
                    """</warning>"/>""",
            ),
            SolrInvalidAttributeValueInspection(),
        )
    }

    /** Turned off, the option is not an intention the chain fails to honour. */
    fun testCaseSplittingTurnedOffIsNotReported() {
        check(
            index(
                whitespace() +
                    filter("solr.LowerCaseFilterFactory") +
                    """<filter class="solr.WordDelimiterGraphFilterFactory" splitOnCaseChange="0"/>""",
            ),
        )
    }

    /**
     * Unwritten, the option is Solr's opinion rather than the author's. This is the case that decides
     * whether the inspection is usable: `splitOnCaseChange` defaults to on, so reporting the default
     * would fire on most chains that lowercase before splitting words.
     */
    fun testTheDefaultedOptionIsNotReported() {
        check(
            index(
                whitespace() +
                    filter("solr.LowerCaseFilterFactory") +
                    filter("solr.WordDelimiterGraphFilterFactory"),
            ),
        )
    }

    /** Outside a Solr project every inspection is inert, like every other surface. */
    fun testNothingIsReportedOutsideASolrProject() {
        givenNoSolrOnTheClasspath()
        check(
            index(
                whitespace() +
                    filter("solr.FlattenGraphFilterFactory") +
                    filter("solr.WordDelimiterGraphFilterFactory"),
            ),
        )
    }

    // --- chains that cannot work -----------------------------------------------------------------

    /** The ordering mistake: the flattener runs before the filter whose graph it should flatten. */
    fun testAFlattenerAboveItsGraphFilterIsFlagged() {
        check(
            index(
                whitespace() +
                    flattenerAbove("solr.WordDelimiterGraphFilterFactory") +
                    filter("solr.WordDelimiterGraphFilterFactory"),
            ),
        )
    }

    /** The same mistake with a synonym graph, and in the query chain, which is checked too. */
    fun testAFlattenerAboveASynonymGraphInTheQueryChainIsFlagged() {
        check(
            query(
                whitespace() +
                    flattenerAbove("solr.SynonymGraphFilterFactory") +
                    filter("solr.SynonymGraphFilterFactory"),
            ),
        )
    }

    /**
     * The `solr.` shorthand is a shorthand; a chain written with the real class name is the same
     * chain, and Lucene's own package is where the flattener actually lives.
     */
    fun testAFullyQualifiedFlattenerIsFlaggedToo() {
        check(
            index(
                whitespace() +
                    flattenerAbove(
                        producer = "solr.SynonymGraphFilterFactory",
                        flattener = "org.apache.lucene.analysis.core.FlattenGraphFilterFactory",
                    ) +
                    filter("solr.SynonymGraphFilterFactory"),
            ),
        )
    }

    /** The stated intention the chain cannot honour: the fold above has removed every boundary. */
    fun testCaseSplittingBelowALowercaseFilterIsFlagged() {
        check(index(whitespace() + filter("solr.LowerCaseFilterFactory") + caseSplit("solr.LowerCaseFilterFactory")))
    }

    /**
     * The tokenizer folds case in this one, and it is consulted whatever position it is written in —
     * Solr runs the tokenizer first regardless of where the element sits.
     */
    fun testCaseSplittingBelowACaseFoldingTokenizerIsFlagged() {
        check(
            index(
                """<tokenizer class="solr.LowerCaseTokenizerFactory"/>""" +
                    caseSplit("solr.LowerCaseTokenizerFactory"),
            ),
        )
    }

    /**
     * `ICUFoldingFilterFactory` folds case among other things, and it is recognized because this rule
     * asks match analysis rather than keeping a second list of the factories that fold.
     */
    fun testCaseSplittingBelowAFoldingFilterIsFlagged() {
        check(
            index(
                whitespace() +
                    filter("solr.ICUFoldingFilterFactory") +
                    caseSplit("solr.ICUFoldingFilterFactory"),
            ),
        )
    }

    /**
     * Any non-zero integer asks for the option, because that is how Solr reads it —
     * `getInt(args, "splitOnCaseChange", 1) != 0`. Reporting only the documented `1` would let a
     * chain state the intention and escape the finding on a spelling Solr treats identically.
     */
    fun testAnyNonZeroValueIsARequestForCaseSplitting() {
        check(
            index(
                whitespace() +
                    filter("solr.LowerCaseFilterFactory") +
                    caseSplit("solr.LowerCaseFilterFactory", value = "2"),
            ),
        )
    }
}
