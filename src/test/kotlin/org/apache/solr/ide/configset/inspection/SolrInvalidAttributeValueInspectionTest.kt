package org.apache.solr.ide.configset.inspection

import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/**
 * The attribute-value inspection, clean fixtures first.
 *
 * `checkHighlighting` fails on any warning the fixture did not mark, so every clean case here is an
 * assertion that nothing at all fires — which is the half that matters. A checker that finds real
 * mistakes and also underlines correct files is worse than no checker, because a reader who learns
 * to ignore it loses the real findings too.
 */
class SolrInvalidAttributeValueInspectionTest : SolrConfigsetTestCase() {

    private val schema = """
        <schema name='t'>
          <fieldType name="string" class="solr.StrField"/>
          BODY
        </schema>
    """.trimIndent()

    private fun checkValues(body: String) {
        myFixture.enableInspections(SolrInvalidAttributeValueInspection())
        myFixture.configureByText("managed-schema.xml", schema.replace("BODY", body))
        myFixture.checkHighlighting(true, false, false)
    }

    // --- clean: values ----------------------------------------------------------------------------

    fun testCorrectValuesAreClean() {
        checkValues(
            """<field name="sku" type="string" indexed="true" stored="false" multiValued="true"/>""" +
                """<fieldType name="t" class="solr.TextField" positionIncrementGap="100"/>""",
        )
    }

    /** Solr's resource loader substitutes these, possibly from outside the repository. */
    fun testASubstitutedValueIsNeverJudged() {
        checkValues(
            """<fieldType name="t" class="solr.TextField" positionIncrementGap="${'$'}{solr.gap:100}"/>""" +
                """<field name="sku" type="string" indexed="${'$'}{solr.indexed:true}"/>""",
        )
    }

    /** A legal value this inspection has no opinion about. */
    fun testANegativeIntegerIsAnInteger() {
        checkValues("""<fieldType name="t" class="solr.TextField" positionIncrementGap="-1"/>""")
    }

    fun testAnOpenValuedAttributeIsNeverJudged() {
        checkValues("""<field name="sku" type="string" default="anything at all"/>""")
    }

    fun testACustomClassIsNeverJudged() {
        checkValues("""<fieldType name="t" class="com.example.MyField"><analyzer><filter class="com.example.MyFilterFactory" minGramSize="not-a-number"/></analyzer></fieldType>""")
    }

    /** Solr reads these with getInt as 0/1 flags, so "1" is right and "true" would not be. */
    fun testWordDelimiterFlagsAcceptIntegers() {
        checkValues(
            """<fieldType name="t" class="solr.TextField"><analyzer>""" +
                """<filter class="solr.WordDelimiterGraphFilterFactory" generateWordParts="1" catenateAll="0"/>""" +
                """</analyzer></fieldType>""",
        )
    }

    // --- flagged: values --------------------------------------------------------------------------

    fun testABooleanPropertyRejectsAWord() {
        checkValues(
            """<field name="sku" type="string" indexed="<warning descr="Solr: 'indexed' accepts true or false">yes</warning>"/>""",
        )
    }

    fun testAnIntegerPropertyRejectsAWord() {
        checkValues(
            """<fieldType name="t" class="solr.TextField" positionIncrementGap="<warning descr="Solr: 'positionIncrementGap' accepts a whole number">foo</warning>"/>""",
        )
    }

    fun testAFactoryIntegerAttributeRejectsADecimal() {
        checkValues(
            """<fieldType name="t" class="solr.TextField"><analyzer>""" +
                """<filter class="solr.EdgeNGramFilterFactory" minGramSize="<warning descr="Solr: 'minGramSize' accepts a whole number">2.5</warning>"/>""" +
                """</analyzer></fieldType>""",
        )
    }

    fun testAFactoryBooleanAttributeRejectsAWord() {
        checkValues(
            """<fieldType name="t" class="solr.TextField"><analyzer>""" +
                """<filter class="solr.EdgeNGramFilterFactory" preserveOriginal="<warning descr="Solr: 'preserveOriginal' accepts true or false">nope</warning>"/>""" +
                """</analyzer></fieldType>""",
        )
    }

    // --- the outer gate ---------------------------------------------------------------------------

    fun testNothingIsReportedOutsideASolrProject() {
        givenNoSolrOnTheClasspath()
        checkValues("""<field name="sku" type="string" indexed="yes"/>""")
    }
}
