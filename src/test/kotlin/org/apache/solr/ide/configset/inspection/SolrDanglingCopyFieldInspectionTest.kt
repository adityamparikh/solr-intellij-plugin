package org.apache.solr.ide.configset.inspection

import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/**
 * The dangling `copyField` inspection, on flagged and clean fixtures alike.
 *
 * The clean cases carry more weight. A warning that fails to appear is a missing feature; a warning
 * on a correct file is what gets a plugin uninstalled. `checkHighlighting` fails on any warning the
 * fixture did not mark, so every clean case here asserts that nothing at all fires.
 */
class SolrDanglingCopyFieldInspectionTest : SolrConfigsetTestCase() {

    private val schema = """
        <schema name='t'>
          <fieldType name="string" class="solr.StrField"/>
          <fieldType name="text_general" class="solr.TextField"/>
          <field name="id" type="string"/>
          <field name="name" type="text_general"/>
          <field name="text" type="text_general"/>
          <dynamicField name="*_s" type="string"/>
          BODY
        </schema>
    """.trimIndent()

    private fun check(body: String) {
        myFixture.enableInspections(SolrDanglingCopyFieldInspection())
        myFixture.configureByText("managed-schema.xml", schema.replace("BODY", body))
        myFixture.checkHighlighting(true, false, false)
    }

    fun testADanglingSourceIsFlagged() {
        check("""<copyField source="<warning descr="Solr: no field named 'manufacturer' is declared in this configset">manufacturer</warning>" dest="text"/>""")
    }

    fun testADanglingDestinationIsFlagged() {
        check("""<copyField source="name" dest="<warning descr="Solr: no field named 'missing' is declared in this configset">missing</warning>"/>""")
    }

    fun testACopyFieldBetweenDeclaredFieldsIsClean() {
        check("""<copyField source="name" dest="text"/>""")
    }

    /** A dynamic field supplies the name, so nothing is dangling. */
    fun testACopyFieldOntoADynamicFieldIsClean() {
        check("""<copyField source="name" dest="title_s"/>""")
    }

    /**
     * A glob source is a pattern over dynamic fields. Whether anything matches it is not a question
     * the schema alone answers, so it must not be reported either way.
     */
    fun testAGlobSourceIsNotReported() {
        check("""<copyField source="*_s" dest="text"/><copyField source="*" dest="text"/>""")
    }

    /** Outside a Solr project every inspection is inert, like every other surface. */
    fun testNothingIsReportedOutsideASolrProject() {
        givenNoSolrOnTheClasspath()
        check("""<copyField source="manufacturer" dest="text"/>""")
    }

    /** A file that is not part of a configset is not inspected, whatever it contains. */
    fun testNothingIsReportedInAFileThatIsNotAConfigset() {
        myFixture.enableInspections(SolrDanglingCopyFieldInspection())
        myFixture.configureByText("notes.xml", schema.replace("BODY", """<copyField source="manufacturer" dest="text"/>"""))
        myFixture.checkHighlighting(true, false, false)
    }
}
