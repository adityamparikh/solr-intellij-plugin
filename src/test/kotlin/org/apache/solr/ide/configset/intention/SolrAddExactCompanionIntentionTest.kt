package org.apache.solr.ide.configset.intention

import org.apache.solr.ide.configset.activation.SolrConfigsetDetector
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase
import org.apache.solr.ide.configset.parsing.SolrConfigsetReader
import org.apache.solr.ide.model.schema.SolrMatchAnalysis
import org.apache.solr.ide.model.schema.SolrMatchGranularity

/**
 * The `_exact` intention end to end.
 *
 * The availability rules are pure and tested in [SolrExactCompanionTest]. What is left here is the
 * wiring, and that what gets written parses back into a model whose companion really does match
 * whole values.
 */
class SolrAddExactCompanionIntentionTest : SolrConfigsetTestCase() {

    private val hint = "Add exact-match companion field"

    private fun schema(body: String) = """
        <schema name='t' version='1.6'>
          <fieldType name="text_general" class="solr.TextField">
            <analyzer type="index">
              <tokenizer class="solr.StandardTokenizerFactory"/>
              <filter class="solr.LowerCaseFilterFactory"/>
            </analyzer>
          </fieldType>
          BODY
        </schema>
    """.trimIndent().replace("BODY", body)

    private val stringType = """<fieldType name="string" class="solr.StrField" sortMissingLast="true"/>"""

    private fun applyIntention(body: String) {
        myFixture.configureByText("managed-schema.xml", schema(body))
        myFixture.launchAction(myFixture.filterAvailableIntentions(hint).single())
    }

    fun testTheCompanionReusesADeclaredStringType() {
        applyIntention("""$stringType<field name="na<caret>me" type="text_general"/>""")

        val text = myFixture.file.text
        assertTrue(text, text.contains("""<field name="name_exact" type="string" indexed="true" stored="false"/>"""))
        assertTrue(text, text.contains("""<copyField source="name" dest="name_exact"/>"""))
        // Reuse means no second type: the one declaration of `string` is the fixture's own.
        assertEquals(text, 1, text.split("""class="solr.StrField"""").size - 1)
    }

    fun testAStringTypeIsWrittenWhenTheSchemaDeclaresNone() {
        applyIntention("""<field name="na<caret>me" type="text_general"/>""")

        val text = myFixture.file.text
        assertTrue(text, text.contains("""<fieldType name="string" class="solr.StrField""""))
        assertTrue(text, text.contains("""<field name="name_exact" type="string" indexed="true" stored="false"/>"""))
        assertTrue(text, text.contains("""<copyField source="name" dest="name_exact"/>"""))
    }

    /**
     * Each inserted element owns its line, as for the prefix half.
     *
     * Worth repeating rather than trusting the shared insertion code: the type written here is a
     * single self-closing element where the prefix half's spans several lines, and a one-line tag is
     * exactly the shape that could land glued to its neighbour without anything else looking wrong.
     */
    fun testEachInsertedElementIsOnItsOwnLine() {
        applyIntention("""<field name="na<caret>me" type="text_general"/>""")

        val lines = myFixture.file.text.lines().map { it.trim() }
        assertContainsElements(
            lines,
            """<fieldType name="string" class="solr.StrField" sortMissingLast="true" docValues="true"/>""",
            """<field name="name" type="text_general"/>""",
            """<field name="name_exact" type="string" indexed="true" stored="false"/>""",
            """<copyField source="name" dest="name_exact"/>""",
        )
    }

    /** What the user was promised has to survive the round trip through the parser. */
    fun testTheCompanionActuallyReadsBackAsWholeValue() {
        applyIntention("""<field name="na<caret>me" type="text_general"/>""")

        val configset = SolrConfigsetDetector.configsetFor(myFixture.file)!!
        val model = SolrConfigsetReader.getInstance(project).modelFor(configset)
        val companion = model.fields["name_exact"]!!.effective
        val capability = SolrMatchAnalysis.of(model.typeOf(companion)!!)

        assertEquals(SolrMatchGranularity.WHOLE_VALUE, capability.granularity)
        assertTrue(capability.confident)
    }

    /**
     * The trap, at fixture level: the schema declares a numeric type and no string one, so the
     * intention must write `string` rather than pointing the companion at `plong`.
     */
    fun testANumericTypeIsNotBorrowedForTheCompanion() {
        applyIntention(
            """<fieldType name="plong" class="solr.LongPointField"/><field name="na<caret>me" type="text_general"/>""",
        )

        val text = myFixture.file.text
        assertTrue(text, text.contains("""<field name="name_exact" type="string" indexed="true" stored="false"/>"""))
        assertFalse(text, text.contains("""type="plong""""))
    }

    /** The wiring test for availability: a string field already matches whole values. */
    fun testNothingIsOfferedOnAFieldThatAlreadyMatchesWholeValues() {
        myFixture.configureByText(
            "managed-schema.xml",
            schema("""$stringType<field name="co<caret>de" type="string"/>"""),
        )

        assertEmpty(myFixture.filterAvailableIntentions(hint))
    }

    /** The one availability rule that cannot be a pure test, because it is about the caret. */
    fun testNothingIsOfferedWhenTheCaretIsNotOnAField() {
        myFixture.configureByText(
            "managed-schema.xml",
            schema("""<fieldType name="o<caret>ther" class="solr.StrField"/><field name="name" type="text_general"/>"""),
        )

        assertEmpty(myFixture.filterAvailableIntentions(hint))
    }

    /** Outside a Solr project every surface is inert, and an intention is no exception. */
    fun testNothingIsOfferedOutsideASolrProject() {
        givenNoSolrOnTheClasspath()
        myFixture.configureByText(
            "managed-schema.xml",
            schema("""<field name="na<caret>me" type="text_general"/>"""),
        )

        assertEmpty(myFixture.filterAvailableIntentions(hint))
    }

    /** Both companions are offered on the same field, and neither hides the other. */
    fun testThePrefixCompanionIsStillOfferedAlongsideIt() {
        myFixture.configureByText(
            "managed-schema.xml",
            schema("""<field name="na<caret>me" type="text_general"/>"""),
        )

        assertSize(1, myFixture.filterAvailableIntentions(hint))
        assertSize(1, myFixture.filterAvailableIntentions("Add prefix-capable companion field"))
    }
}
