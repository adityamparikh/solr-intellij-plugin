package org.apache.solr.ide.configset.solrconfig.inspection

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

    /**
     * `_docid_` is the sort spelling of the same thing `[docid]` is in an `fl`, and it wears no
     * bracket to give itself away — it is an ordinary-looking name that no schema declares and Solr
     * answers itself, out of `SortSpecParsing`. A sort by internal document id is a correct file.
     */
    fun testSortingByTheInternalDocumentIdIsNotReported() {
        checkConfig(handler("""<str name="sort">_docid_ asc</str>"""))
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

    /**
     * Solr's other scalar value tags carry parameters too, and a field name in one is as real.
     *
     * `<str>` is the spelling every example uses, which is why reading it alone went unnoticed: the
     * parser places no restriction on the tag at all, so a `qf` written as an `<int>` produced a model
     * reference that no position could underline. Contrived as Solr, and the point is that Solr accepts
     * it — nothing rejects a numeric tag holding text.
     */
    fun testFieldNamesInSolrsOtherValueTagsAreRead() {
        checkConfig(
            handler(
                """<int name="qf"><warning descr="Solr: no field named 'nosuchfield' is declared in the schema">nosuchfield</warning></int>""",
                """<bool name="df"><warning descr="Solr: no field named 'alsomissing' is declared in the schema">alsomissing</warning></bool>""",
            ),
        )
    }

    /** And a declared field in one of them is as clean. */
    fun testDeclaredFieldsInOtherValueTagsAreClean() {
        checkConfig(handler("""<int name="qf">name</int>""", """<long name="fl">id</long>"""))
    }

    /**
     * A constant boost is not a field name, in any tag that can hold it.
     *
     * `boost` takes a multiplier and `bf` an additive function, so a flat number in either is ordinary
     * Solr — and `<float name="boost">1.5</float>` is the natural way to write one. Reading `1.5` as a
     * field produced a warning about a field nobody could declare, on a file that was entirely correct.
     * The `<str>` spelling of the same value had the defect first; widening the value tags is what made
     * the numeric spellings reach it.
     */
    fun testAConstantBoostIsNotReadAsAFieldName() {
        checkConfig(
            handler(
                """<float name="boost">1.5</float>""",
                """<str name="boost">2</str>""",
                """<str name="bf">3.16e-11</str>""",
                """<str name="qf">-2.5</str>""",
            ),
        )
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

    // --- the terms and more-like-this field lists --------------------------------------------------

    /**
     * The clean case, written before the flagged one.
     *
     * These two parameters became field references in the same change as this test. Solr's own
     * shipped configsets use neither, so the suite's existing clean-fixture check — which reads those
     * configsets and asserts nothing is reported — would have passed whatever this change did. A
     * fixture that cannot fail is not a gate, and this is the one that can.
     *
     * The `terms.fl` value is deliberately one name: Solr reads that parameter whole rather than
     * splitting it, so a comma in a value is part of the name it looks up.
     */
    fun testTermsAndMoreLikeThisFieldListsNamingRealFieldsAreClean() {
        checkConfig(handler("""<str name="terms.fl">name</str>""", """<str name="mlt.fl">name,text</str>"""))
    }

    /** And now a typo is visible in either, which it was not before: nobody read these parameters. */
    fun testATypoInEitherParameterIsFlagged() {
        checkConfig(
            handler(
                """<str name="terms.fl"><warning descr="Solr: no field named 'nmae' is declared in the schema">nmae</warning></str>""",
                """<str name="mlt.fl">name,<warning descr="Solr: no field named 'txet' is declared in the schema">txet</warning></str>""",
            ),
        )
    }
}
