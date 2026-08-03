package org.apache.solr.ide.configset.intention

import org.apache.solr.ide.configset.activation.SolrConfigsetDetector
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase
import org.apache.solr.ide.configset.parsing.SolrConfigsetReader
import org.apache.solr.ide.model.SolrMatchAnalysis
import org.apache.solr.ide.model.SolrPrefixSupport

/**
 * The intention end to end: what it offers, and what the file says afterwards.
 *
 * The availability rules themselves are pure and tested in [SolrPrefixCompanionTest]. What is left
 * here is the wiring — that availability really is decided by that function, that the caret has to
 * be on a field, and that what gets written parses back into a model with the capability the user
 * was promised.
 */
class SolrAddPrefixCompanionIntentionTest : SolrConfigsetTestCase() {

    private val hint = "Add prefix-capable companion field"

    private fun schema(body: String) = """
        <schema name='t' version='1.6'>
          <fieldType name="string" class="solr.StrField"/>
          <fieldType name="text_general" class="solr.TextField">
            <analyzer type="index">
              <tokenizer class="solr.StandardTokenizerFactory"/>
              <filter class="solr.LowerCaseFilterFactory"/>
            </analyzer>
          </fieldType>
          BODY
          <field name="id" type="string"/>
        </schema>
    """.trimIndent().replace("BODY", body)

    private val autocompleteType = """
        <fieldType name="text_autocomplete" class="solr.TextField">
          <analyzer type="index">
            <tokenizer class="solr.StandardTokenizerFactory"/>
            <filter class="solr.LowerCaseFilterFactory"/>
            <filter class="solr.EdgeNGramFilterFactory" minGramSize="2" maxGramSize="15"/>
          </analyzer>
        </fieldType>
    """.trimIndent()

    private fun applyIntention(body: String) {
        myFixture.configureByText("managed-schema.xml", schema(body))
        myFixture.launchAction(myFixture.filterAvailableIntentions(hint).single())
    }

    fun testTheCompanionReusesATypeTheSchemaAlreadyDeclares() {
        applyIntention("""$autocompleteType<field name="desc<caret>ription" type="text_general"/>""")

        val text = myFixture.file.text
        assertTrue(text, text.contains("""<field name="description_prefix" type="text_autocomplete" indexed="true" stored="false"/>"""))
        assertTrue(text, text.contains("""<copyField source="description" dest="description_prefix"/>"""))
        // Reuse means exactly that: no second type gets written.
        assertFalse(text, text.contains("text_prefix"))
    }

    /**
     * Each inserted element owns its line.
     *
     * `addAfter` places a tag directly against its anchor, and nothing in this file asks for
     * whitespace, so "did the companion land glued to the source field" is a real question about
     * PSI insertion rather than a stylistic one. Asserting on `contains` alone would pass either
     * way, which is what makes this worth its own test.
     */
    fun testEachInsertedElementIsOnItsOwnLine() {
        applyIntention("""<field name="desc<caret>ription" type="text_general"/>""")

        val lines = myFixture.file.text.lines().map { it.trim() }
        assertContainsElements(
            lines,
            """<field name="description" type="text_general"/>""",
            """<field name="description_prefix" type="text_prefix" indexed="true" stored="false"/>""",
            """<copyField source="description" dest="description_prefix"/>""",
        )
    }

    fun testTheGeneratedTypePutsTheEdgeNGramOnTheIndexSideOnly() {
        applyIntention("""<field name="desc<caret>ription" type="text_general"/>""")

        val text = myFixture.file.text
        assertTrue(text, text.contains("""<fieldType name="text_prefix" class="solr.TextField""""))
        assertTrue(text, text.contains("""<field name="description_prefix" type="text_prefix" indexed="true" stored="false"/>"""))
        assertTrue(text, text.contains("""<copyField source="description" dest="description_prefix"/>"""))

        // The asymmetry is the whole point, and the part hand-written copies get wrong: an
        // edge-n-gram on the query side grinds the query into its own prefixes and collapses
        // relevance. Index side exactly once, query side never.
        assertEquals(text, 1, text.split("EdgeNGramFilterFactory").size - 1)
    }

    /** What the user was promised has to survive the round trip through the parser. */
    fun testTheCompanionActuallyReadsBackAsPrefixCapable() {
        applyIntention("""<field name="desc<caret>ription" type="text_general"/>""")

        val configset = SolrConfigsetDetector.configsetFor(myFixture.file)!!
        val model = SolrConfigsetReader.getInstance(project).modelFor(configset)
        val companion = model.fields["description_prefix"]!!.effective
        val capability = SolrMatchAnalysis.of(model.typeOf(companion)!!)

        assertEquals(SolrPrefixSupport.EDGE_NGRAM, capability.prefix)
        assertTrue(capability.confident)
    }

    fun testTheCopyRuleJoinsTheExistingBlockOfCopyRules() {
        applyIntention(
            """$autocompleteType<field name="desc<caret>ription" type="text_general"/>""" +
                """<field name="title" type="text_general"/><copyField source="title" dest="description"/>""",
        )

        val text = myFixture.file.text
        assertTrue(text, text.indexOf("""source="title"""") < text.indexOf("""source="description""""))
    }

    /** The wiring test for availability: the plan says no, so nothing is offered. */
    fun testNothingIsOfferedOnAFieldThatIsAlreadyPrefixCapable() {
        myFixture.configureByText(
            "managed-schema.xml",
            schema("""$autocompleteType<field name="desc<caret>ription" type="text_autocomplete"/>"""),
        )

        assertEmpty(myFixture.filterAvailableIntentions(hint))
    }

    /** The one availability rule that cannot be a pure test, because it is about the caret. */
    fun testNothingIsOfferedWhenTheCaretIsNotOnAField() {
        myFixture.configureByText(
            "managed-schema.xml",
            schema("""<fieldType name="text_o<caret>ther" class="solr.StrField"/><field name="description" type="text_general"/>"""),
        )

        assertEmpty(myFixture.filterAvailableIntentions(hint))
    }

    /** Outside a Solr project every surface is inert, and an intention is no exception. */
    fun testNothingIsOfferedOutsideASolrProject() {
        givenNoSolrOnTheClasspath()
        myFixture.configureByText(
            "managed-schema.xml",
            schema("""<field name="desc<caret>ription" type="text_general"/>"""),
        )

        assertEmpty(myFixture.filterAvailableIntentions(hint))
    }
}
