package org.apache.solr.ide.configset.inspection

import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/**
 * The inspections, on flagged and clean fixtures alike.
 *
 * The clean cases carry more weight than the flagged ones. A warning that fails to appear is a
 * missing feature; a warning on a correct file is what gets a plugin uninstalled, and Solr
 * configuration is full of syntax that resembles a field name without being one.
 *
 * `checkHighlighting` compares against `<warning>` markers in the fixture, so a warning *anywhere*
 * unmarked fails the test. Every clean case is therefore an assertion that nothing at all fires.
 */
class SolrUnknownFieldReferenceInspectionTest : SolrConfigsetTestCase() {

    private val types = """
          <fieldType name="string" class="solr.StrField"/>
          <fieldType name="text_general" class="solr.TextField"/>
    """.trimIndent()

    private val fields = """
          <field name="id" type="string"/>
          <field name="name" type="text_general"/>
          <field name="name_prefix" type="string"/>
          <field name="text" type="text_general"/>
          <dynamicField name="*_s" type="string"/>
    """.trimIndent()

    private fun schema(body: String) = "<schema name='t'>\n$types\n$fields\n$body\n</schema>"

    private fun checkConfig(body: String) {
        myFixture.addFileToProject("managed-schema.xml", schema(""))
        myFixture.enableInspections(SolrUnknownFieldReferenceInspection())
        myFixture.configureByText("solrconfig.xml", "<config>\n$body\n</config>")
        myFixture.checkHighlighting(true, false, false)
    }

    private fun handler(vararg parameters: String) =
        """<requestHandler name="/select"><lst name="defaults">${parameters.joinToString("")}</lst></requestHandler>"""

    // --- handler parameters naming fields --------------------------------------------------------

    fun testAHandlerParameterNamingAnUndeclaredFieldIsFlagged() {
        checkConfig(
            handler("""<str name="qf">name^3 <warning descr="Solr: no field named 'descriptoin' is declared in the schema">descriptoin</warning></str>"""),
        )
    }

    fun testHandlerParametersNamingDeclaredFieldsAreClean() {
        checkConfig(
            handler(
                """<str name="qf">name^3 text</str>""",
                """<str name="df">text</str>""",
                """<str name="fl">id,name</str>""",
                """<str name="sort">name asc</str>""",
            ),
        )
    }

    /**
     * The false positives this inspection exists to avoid. Every one of these is legal syntax that
     * resembles a field name, and every one lands on a file that is entirely correct.
     */
    fun testSolrSyntaxThatResemblesAFieldNameIsNotReported() {
        checkConfig(
            handler(
                """<str name="fl">*,score,[docid],max(price,0),alias:name</str>""",
                """<str name="qf">${'$'}boostParam</str>""",
                """<str name="rows">10</str>""",
                """<str name="defType">edismax</str>""",
            ),
        )
    }

    /** A dynamic field satisfies a handler reference exactly as it satisfies any other. */
    fun testAHandlerParameterNamingADynamicFieldIsClean() {
        checkConfig(handler("""<str name="fl">title_s</str>"""))
    }

    /** Reporting `name` must not underline the `name` inside `name_prefix`. */
    fun testOnlyWholeTokensAreUnderlined() {
        checkConfig(handler("""<str name="qf">name_prefix name</str>"""))
    }

    fun testParametersOutsideAParameterListAreIgnored() {
        checkConfig("""<lst name="something-else"><str name="qf">nonexistent</str></lst>""")
    }

    // --- the gate ------------------------------------------------------------------------------

    /** Outside a Solr project every inspection is inert, like every other surface. */
    fun testNothingIsReportedOutsideASolrProject() {
        givenNoSolrOnTheClasspath()
        checkConfig(handler("""<str name="qf">nonexistent</str>"""))
    }

    /**
     * The false positive this inspection had. `<lst name="defaults">` also appears under elements
     * that configure something other than a query — an update processor chain, for one — and the
     * parser already declines to read those. The inspection did not, and reported a field reference
     * the model itself says does not exist.
     */
    fun testAParameterListOutsideARequestHandlerIsNotInspected() {
        checkConfig(
            """
            <updateRequestProcessorChain name="x">
              <processor class="solr.Custom">
                <lst name="defaults"><str name="fl">nosuchfield</str></lst>
              </processor>
            </updateRequestProcessorChain>
            """.trimIndent(),
        )
    }

    /** An `arr` supplies the parameter name to each `str` inside it. */
    fun testABadFieldInsideAnArrIsFlagged() {
        checkConfig(
            handler("""<arr name="facet.field"><str><warning descr="Solr: no field named 'nosuchfield' is declared in the schema">nosuchfield</warning></str><str>id</str></arr>"""),
        )
    }

    fun testAnArrOfDeclaredFieldsIsClean() {
        checkConfig(handler("""<arr name="facet.field"><str>id</str><str>name</str></arr>"""))
    }

    /** An unnamed `str` outside an `arr` supplies no parameter name, so nothing is examined. */
    fun testAnUnnamedValueOutsideAnArrIsIgnored() {
        checkConfig(handler("""<str>nosuchfield</str>"""))
    }

    /** A name at either end of the value has no separator on that side. */
    fun testAFieldAtTheStartAndEndOfAValueIsMatched() {
        checkConfig(
            handler("""<str name="qf"><warning descr="Solr: no field named 'aaa' is declared in the schema">aaa</warning> name <warning descr="Solr: no field named 'zzz' is declared in the schema">zzz</warning></str>"""),
        )
    }

    /** The schema is not where handler parameters live, so it is not visited at all. */
    fun testTheSchemaFileIsNotInspected() {
        myFixture.enableInspections(SolrUnknownFieldReferenceInspection())
        myFixture.configureByText("managed-schema.xml", schema(""))
        myFixture.checkHighlighting(true, false, false)
    }
}
