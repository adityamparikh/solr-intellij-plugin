package org.apache.solr.ide.configset.schema.intention

import org.apache.solr.ide.configset.activation.SolrConfigsetDetector
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase
import org.apache.solr.ide.configset.reading.SolrConfigsetReader
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

    /**
     * A multi-valued source gets a multi-valued companion, or the schema breaks at index time.
     *
     * `copyField` copies every value the source receives, so Solr rejects a multi-valued source
     * feeding a single-valued destination with *multiple values encountered for non-multiValued copy
     * field* — and it rejects it while indexing the user's documents, not while they are looking at
     * the schema. The intention wrote a single-valued companion unconditionally, which made accepting
     * it a way to break indexing later with an edit the plugin had offered.
     */
    fun testAMultiValuedSourceGetsAMultiValuedCompanion() {
        applyIntention("""$stringType<field name="ta<caret>gs" type="text_general" multiValued="true"/>""")

        val text = myFixture.file.text
        assertTrue(
            text,
            text.contains(
                """<field name="tags_exact" type="string" indexed="true" stored="false" multiValued="true"/>""",
            ),
        )
        assertTrue(text, text.contains("""<copyField source="tags" dest="tags_exact"/>"""))
    }

    /**
     * And a single-valued source keeps the shorter tag, so the attribute is not written by habit.
     *
     * Asserted on the word rather than on a rendering of the whole tag: the fixture declares no
     * multi-valued field anywhere, so any occurrence at all is this defect. A first version of this
     * matched one long concatenated literal and would have passed silently the moment the attribute
     * order or spacing changed — a test that fails for no reason it names is worth less than none.
     */
    fun testASingleValuedSourceGetsNoMultiValuedAttribute() {
        applyIntention("""$stringType<field name="na<caret>me" type="text_general"/>""")

        val text = myFixture.file.text
        assertTrue(text, text.contains("""<field name="name_exact" type="string" indexed="true" stored="false"/>"""))
        assertFalse(text, "multiValued" in text)
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
