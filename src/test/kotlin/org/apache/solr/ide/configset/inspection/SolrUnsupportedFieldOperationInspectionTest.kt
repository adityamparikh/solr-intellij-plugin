package org.apache.solr.ide.configset.inspection

import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/**
 * Faceting and sorting on a field that cannot serve it.
 *
 * **The clean fixtures still come first, but the flagged ones carry unusual weight here.** Elsewhere in
 * this package a missing warning is a missing feature; here it is a configuration that fails every
 * query, which the plugin accepted silently before this inspection existed.
 *
 * Only this inspection is enabled, so a fixture may name an undeclared field or a non-indexed one
 * without marking a warning — those findings belong to the other two inspections and are asserted there.
 */
class SolrUnsupportedFieldOperationInspectionTest : SolrConfigsetTestCase() {

    /**
     * Version 1.7 matters and is the point of the fixture: `uninvertible` defaults false from it, so an
     * indexed field with no doc values cannot be faceted. The same schema at 1.6 is asserted separately.
     */
    private val schema = """
        <schema name="t" version="1.7">
          <fieldType name="string" class="solr.StrField"/>
          <fieldType name="text_general" class="solr.TextField"/>
          <field name="id" type="string" docValues="true"/>
          <field name="category" type="string" indexed="true" docValues="false"/>
          <field name="rescued" type="string" indexed="true" docValues="false" uninvertible="true"/>
          <field name="popularity" type="string" indexed="false" docValues="true"/>
          <field name="custom" type="nosuchtype" indexed="true"/>
          <field name="tags" type="string" docValues="true" multiValued="true"/>
          <dynamicField name="*_dv" type="string" docValues="true"/>
          <dynamicField name="*_nodv" type="string" indexed="true" docValues="false"/>
        </schema>
    """.trimIndent()

    private fun checkConfig(body: String, schemaText: String = schema) {
        myFixture.addFileToProject("managed-schema.xml", schemaText)
        myFixture.enableInspections(SolrUnsupportedFieldOperationInspection())
        myFixture.configureByText("solrconfig.xml", "<config>\n$body\n</config>")
        myFixture.checkHighlighting(true, false, false)
    }

    private fun handler(vararg parameters: String) =
        """<requestHandler name="/select"><lst name="defaults">${parameters.joinToString("")}</lst></requestHandler>"""

    private fun unusable(field: String, parameter: String) =
        """<warning descr="Solr: '$parameter' will fail on '$field' — it needs doc values or an un-invertible index, and for sorting a single value per document">$field</warning>"""

    // --- clean fixtures, written first -----------------------------------------------------------

    /** Doc values are what faceting and sorting actually read, so a field carrying them is clean. */
    fun testFacetingAndSortingOnDocValuesFieldsAreClean() {
        checkConfig(
            handler(
                """<arr name="facet.field"><str>id</str><str>popularity</str></arr>""",
                """<str name="sort">popularity desc</str>""",
                """<str name="group.field">id</str>""",
            ),
        )
    }

    /** An explicit `uninvertible="true"` buys back what the version default took away. */
    fun testAnUninvertibleIndexedFieldIsClean() {
        checkConfig(
            handler(
                """<arr name="facet.field"><str>rescued</str></arr>""",
                """<str name="sort">rescued asc</str>""",
            ),
        )
    }

    /** Below 1.7 `uninvertible` defaulted true, so the same indexed-only field faceted fine. */
    fun testAnIndexedFieldIsCleanInAnOlderSchema() {
        checkConfig(
            handler("""<arr name="facet.field"><str>category</str></arr>"""),
            schemaText = schema.replace("""version="1.7"""", """version="1.6""""),
        )
    }

    /**
     * A field type the catalog has never seen leaves `docValues` undetermined, and a custom type is not
     * evidence of a defect. This is the fixture that catches a drift into validating by absence.
     *
     * The field must *not* declare `docValues` for this to be the undetermined case — the first draft
     * wrote `docValues="false"` and the inspection flagged it correctly, because two explicit
     * declarations and a version default leave nothing unknown. What an undeclared type costs is the
     * type-level answer, not the field's own.
     */
    fun testAFieldWithAnUndeclaredTypeIsNotReported() {
        checkConfig(handler("""<arr name="facet.field"><str>custom</str></arr>"""))
    }

    /** The parameters that search rather than facet are not this inspection's business. */
    fun testSearchParametersAreNotExamined() {
        checkConfig(
            handler(
                """<str name="qf">category^2</str>""",
                """<str name="fl">category</str>""",
                """<str name="df">category</str>""",
            ),
        )
    }

    /** Syntax that resembles a field name, on a file that is entirely correct. */
    fun testSolrSyntaxIsNotReported() {
        checkConfig(
            handler(
                """<str name="sort">score desc</str>""",
                """<str name="sort">_docid_ asc</str>""",
                """<arr name="facet.field"><str>${'$'}facetParam</str></arr>""",
            ),
        )
    }

    /** A parameter list outside a request handler configures no query. */
    fun testAParameterListOutsideARequestHandlerIsNotInspected() {
        checkConfig(
            """
            <updateRequestProcessorChain name="x">
              <processor class="solr.Custom">
                <lst name="defaults"><arr name="facet.field"><str>category</str></arr></lst>
              </processor>
            </updateRequestProcessorChain>
            """.trimIndent(),
        )
    }

    /** Outside a Solr project every inspection is inert. */
    fun testNothingIsReportedOutsideASolrProject() {
        givenNoSolrOnTheClasspath()
        checkConfig(handler("""<arr name="facet.field"><str>category</str></arr>"""))
    }

    // --- flagged fixtures ------------------------------------------------------------------------

    /** The defect: a request Solr refuses, on a configset that looks correct. */
    fun testFacetingOnAFieldWithNeitherIsFlagged() {
        checkConfig(
            handler("""<arr name="facet.field"><str>${unusable("category", "facet.field")}</str></arr>"""),
        )
    }

    /** Sorting fails the same way and for the same reason. */
    fun testSortingOnAFieldWithNeitherIsFlagged() {
        checkConfig(handler("""<str name="sort">${unusable("category", "sort")} desc</str>"""))
    }

    /** Grouping orders documents by the field's value, so it needs what sorting needs. */
    fun testGroupingOnAFieldWithNeitherIsFlagged() {
        checkConfig(handler("""<str name="group.field">${unusable("category", "group.field")}</str>"""))
    }

    /** Every name in a pivot is faceted on. */
    fun testAPivotNamingAnUnfacetableFieldIsFlagged() {
        checkConfig(
            handler("""<str name="facet.pivot">id,${unusable("category", "facet.pivot")}</str>"""),
        )
    }

    /**
     * A dynamic field is resolved by Solr's longest-literal rule and its properties are as real, so a
     * facet matching an indexed-only pattern fails exactly as a declared field would.
     */
    fun testADynamicFieldWithoutDocValuesIsFlagged() {
        checkConfig(
            handler("""<arr name="facet.field"><str>${unusable("colour_nodv", "facet.field")}</str></arr>"""),
        )
    }

    /**
     * A multiValued field sorts nowhere. Several values have no defined order, so Solr rejects a plain
     * sort and requires a selector — `field(tags,min)` — which is not what a bare name here asks for.
     */
    fun testSortingOnAMultiValuedFieldIsFlagged() {
        checkConfig(handler("""<str name="sort">${unusable("tags", "sort")} asc</str>"""))
    }

    /** And faceting on the same field is exactly what multiValued fields are for. */
    fun testFacetingOnAMultiValuedFieldIsClean() {
        checkConfig(handler("""<arr name="facet.field"><str>tags</str></arr>"""))
    }

    /** A field that is searchable and unfacetable is the split this inspection exists to express. */
    fun testTheSameFieldIsFlaggedForFacetingAndNotForSearching() {
        checkConfig(
            handler(
                """<str name="qf">category^2</str>""",
                """<arr name="facet.field"><str>${unusable("category", "facet.field")}</str></arr>""",
            ),
        )
    }
}
