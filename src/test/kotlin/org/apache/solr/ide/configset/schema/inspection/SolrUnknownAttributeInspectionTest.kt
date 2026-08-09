package org.apache.solr.ide.configset.schema.inspection

import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/**
 * The unknown-attribute inspection, clean fixtures first.
 *
 * `checkHighlighting` fails on any warning the fixture did not mark, so every clean case here is an
 * assertion that nothing at all fires — which is the half that matters. A checker that finds real
 * mistakes and also underlines correct files is worse than no checker, because a reader who learns
 * to ignore it loses the real findings too.
 */
class SolrUnknownAttributeInspectionTest : SolrConfigsetTestCase() {

    private val schema = """
        <schema name='t'>
          <fieldType name="string" class="solr.StrField"/>
          BODY
        </schema>
    """.trimIndent()

    private fun checkNames(body: String) {
        myFixture.enableInspections(SolrUnknownAttributeInspection())
        myFixture.configureByText("managed-schema.xml", schema.replace("BODY", body))
        myFixture.checkHighlighting(true, false, false)
    }

    // --- clean: attribute names -------------------------------------------------------------------

    fun testCorrectAttributeNamesAreClean() {
        checkNames("""<field name="sku" type="string" indexed="true" stored="true" docValues="true"/>""")
    }

    /**
     * Field attributes solr-core accepts but the Reference Guide's field table omits.
     *
     * `storeOffsetsWithPositions` is real and documented only alongside the highlighter that reads
     * it; `postingsFormat` is accepted and inert on a field, being read only from the type. Both
     * load without error, so flagging either would underline a file Solr accepts.
     */
    fun testFieldAttributesTheGuideOmitsAreClean() {
        checkNames(
            """<field name="sku" type="string" storeOffsetsWithPositions="true" postingsFormat="Lucene912"/>""",
        )
    }

    /**
     * A field type delegates to classes its own configuration names.
     *
     * `providerClass` picks the provider that reads `currencyConfig`, which no walk from the field
     * type can reach — and Solr's own `sample_techproducts_configs` writes exactly this. A field
     * type's attribute list is open by construction, so nothing here may be reported.
     */
    fun testAFieldTypeIsNeverCheckedForUnknownAttributes() {
        checkNames(
            """<fieldType name="currency" class="solr.CurrencyFieldType" """ +
                """defaultCurrency="USD" currencyConfig="currency.xml" """ +
                """amountLongSuffix="_l_ns" codeStrSuffix="_s_ns"/>""",
        )
    }

    fun testACustomFilterClassIsNeverChecked() {
        checkNames(
            """<fieldType name="t" class="solr.TextField"><analyzer>""" +
                """<filter class="com.example.MyFilterFactory" whateverItLikes="1"/>""" +
                """</analyzer></fieldType>""",
        )
    }

    /** The generator strips `class`, so the inspection has to allow it structurally. */
    fun testTheClassAttributeIsNotFlaggedOnTheElementThatDeclaresIt() {
        checkNames(
            """<fieldType name="t" class="solr.TextField"><analyzer type="index">""" +
                """<tokenizer class="solr.StandardTokenizerFactory"/>""" +
                """<filter class="solr.EdgeNGramFilterFactory" minGramSize="2" maxGramSize="15"/>""" +
                """</analyzer></fieldType>""",
        )
    }

    fun testAStopFilterResourceAttributeIsClean() {
        checkNames(
            """<fieldType name="t" class="solr.TextField"><analyzer>""" +
                """<filter class="solr.StopFilterFactory" words="stopwords.txt" ignoreCase="true" format="wordset"/>""" +
                """</analyzer></fieldType>""",
        )
    }

    // --- flagged: attribute names -----------------------------------------------------------------

    fun testAMisspelledFieldPropertyIsFlagged() {
        checkNames(
            """<field name="sku" type="string" <warning descr="Solr: <field> does not accept an attribute named 'indexd'">indexd</warning>="true"/>""",
        )
    }

    fun testAMisspelledFactoryAttributeIsFlagged() {
        checkNames(
            """<fieldType name="t" class="solr.TextField"><analyzer>""" +
                """<filter class="solr.EdgeNGramFilterFactory" <warning descr="Solr: <filter> does not accept an attribute named 'maxGramSiz'">maxGramSiz</warning>="15"/>""" +
                """</analyzer></fieldType>""",
        )
    }

    // --- the outer gate ---------------------------------------------------------------------------

    fun testNothingIsReportedOutsideASolrProject() {
        givenNoSolrOnTheClasspath()
        checkNames("""<field name="sku" type="string" indexd="true"/>""")
    }
}
