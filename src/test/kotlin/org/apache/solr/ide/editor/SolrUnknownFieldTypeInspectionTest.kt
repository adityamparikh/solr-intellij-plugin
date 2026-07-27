package org.apache.solr.ide.editor

import org.apache.solr.ide.configset.SolrConfigsetTestCase

/**
 * The unknown-field-type inspection, on flagged and clean fixtures alike.
 *
 * `checkHighlighting` fails on any warning the fixture did not mark, so every clean case here
 * asserts that nothing at all fires.
 */
class SolrUnknownFieldTypeInspectionTest : SolrConfigsetTestCase() {

    private val schema = """
        <schema name='t'>
          <fieldType name="string" class="solr.StrField"/>
          <fieldType name="text_general" class="solr.TextField"/>
          BODY
        </schema>
    """.trimIndent()

    private fun check(body: String) {
        myFixture.enableInspections(SolrUnknownFieldTypeInspection())
        myFixture.configureByText("managed-schema.xml", schema.replace("BODY", body))
        myFixture.checkHighlighting(true, false, false)
    }

    fun testAFieldNamingAnUndeclaredTypeIsFlagged() {
        check("""<field name="sku" type="<warning descr="Solr: no field type named 'stored' is declared in this configset">stored</warning>"/>""")
    }

    fun testADynamicFieldNamingAnUndeclaredTypeIsFlagged() {
        check("""<dynamicField name="*_x" type="<warning descr="Solr: no field type named 'nope' is declared in this configset">nope</warning>"/>""")
    }

    fun testFieldsNamingDeclaredTypesAreClean() {
        check("""<field name="sku" type="string"/><field name="body" type="text_general"/>""")
    }

    /** A missing type is a different defect, and "unknown type ''" is not a useful message. */
    fun testAFieldWithNoTypeIsNotReportedHere() {
        check("""<field name="sku"/>""")
    }

    fun testNothingIsReportedOutsideASolrProject() {
        givenNoSolrOnTheClasspath()
        check("""<field name="sku" type="stored"/>""")
    }
}
