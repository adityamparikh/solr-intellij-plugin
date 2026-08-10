package org.apache.solr.ide.configset.schema.inspection

import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/**
 * The unused-field-type inspection, on flagged and clean fixtures alike.
 *
 * Every fixture here declares the whole schema rather than adding a body to a shared preamble: the
 * finding is a statement about the *absence* of a reference anywhere in the file, so a type left
 * over from another test's preamble would be reported in this one and the fixtures have to be read
 * as a whole to be read at all.
 *
 * `checkHighlighting` fails on any finding the fixture did not mark, so each clean case asserts that
 * nothing at all fires.
 */
class SolrUnusedFieldTypeInspectionTest : SolrConfigsetTestCase() {

    private fun check(body: String) {
        myFixture.enableInspections(SolrUnusedFieldTypeInspection())
        myFixture.configureByText("managed-schema.xml", """<schema name="t" version="1.6">$body</schema>""")
        myFixture.checkHighlighting(true, false, false)
    }

    private fun unused(name: String) =
        """<warning descr="Solr: nothing in this configset uses the field type '$name'">$name</warning>"""

    // --- clean fixtures --------------------------------------------------------------------------

    /** The everyday shape: every type has a field behind it. */
    fun testATypeAFieldNamesIsClean() {
        check(
            """
            <fieldType name="string" class="solr.StrField"/>
            <fieldType name="text_general" class="solr.TextField"/>
            <field name="sku" type="string"/>
            <field name="body" type="text_general"/>
            """,
        )
    }

    /**
     * A dynamic field is a use. Whether any document ever matches the pattern is not something a
     * static reading of the configset can know, and a schema of nothing but dynamic fields is an
     * ordinary schema.
     */
    fun testATypeOnlyADynamicFieldNamesIsClean() {
        check(
            """
            <fieldType name="string" class="solr.StrField"/>
            <dynamicField name="*_s" type="string" indexed="true" stored="true"/>
            """,
        )
    }

    /**
     * `PointType` and `LatLonType` delegate each dimension to a real numeric type through
     * `subFieldType`, and that is a reference like any other. Missing it would report the stock
     * spatial arrangement as dead on a schema that uses it exactly as Solr documents.
     */
    fun testATypeAnotherTypeDelegatesToIsClean() {
        check(
            """
            <fieldType name="tdouble" class="solr.DoublePointField"/>
            <fieldType name="location" class="solr.LatLonType" subFieldType="tdouble"/>
            <field name="store" type="location"/>
            """,
        )
    }

    /** Solr accepts the older lowercase spelling of the tag, and a use written that way is a use. */
    fun testTheLegacySpellingOfTheTagIsReadAsADeclaration() {
        check(
            """
            <fieldtype name="string" class="solr.StrField"/>
            <field name="sku" type="string"/>
            """,
        )
    }

    /**
     * A schema that assembles itself out of other files says nothing about its own types.
     *
     * The fields naming them are in the included document, which this reading never sees, so every
     * type in the file would be greyed out — a whole schema reported as dead while being entirely
     * correct.
     */
    fun testASchemaThatIncludesAnotherFileIsNotReportedAtAll() {
        check(
            """
            <fieldType name="string" class="solr.StrField"/>
            <fieldType name="text_general" class="solr.TextField"/>
            <xi:include href="fields.xml" xmlns:xi="http://www.w3.org/2001/XInclude"/>
            """,
        )
    }

    /** A half-typed declaration is unused by definition, and greying it out as it is written is noise. */
    fun testATypeWithNoNameIsNotReported() {
        check("""<fieldType class="solr.StrField"/>""")
    }

    /** Types are declared in the schema; a same-named tag anywhere else is not one. */
    fun testAFieldTypeTagOutsideTheSchemaIsNotReported() {
        myFixture.enableInspections(SolrUnusedFieldTypeInspection())
        myFixture.configureByText(
            "solrconfig.xml",
            """<config><fieldType name="ghost" class="solr.StrField"/></config>""",
        )
        myFixture.checkHighlighting(true, false, false)
    }

    /** Outside a Solr project every inspection is inert, like every other surface. */
    fun testNothingIsReportedOutsideASolrProject() {
        givenNoSolrOnTheClasspath()
        check("""<fieldType name="text_legacy" class="solr.TextField"/>""")
    }

    // --- flagged fixtures ------------------------------------------------------------------------

    /** The finding: a type left behind by the field that used to name it. */
    fun testATypeNothingNamesIsFlagged() {
        check(
            """
            <fieldType name="string" class="solr.StrField"/>
            <fieldType name="${unused("text_legacy")}" class="solr.TextField"/>
            <field name="sku" type="string"/>
            """,
        )
    }

    /** Each unused declaration is greyed out on its own, not merely the first. */
    fun testEveryUnusedTypeIsFlagged() {
        check(
            """
            <fieldType name="${unused("text_legacy")}" class="solr.TextField"/>
            <fieldType name="${unused("text_older")}" class="solr.TextField"/>
            """,
        )
    }

    /** The older spelling of the tag declares a type, so it can carry the finding too. */
    fun testTheLegacySpellingIsFlaggedToo() {
        check("""<fieldtype name="${unused("text_legacy")}" class="solr.TextField"/>""")
    }

    /**
     * A type used only by a field naming *another* type is not used. The obvious way to get this
     * wrong is to check that the schema has any fields at all rather than which types they name.
     */
    fun testATypeIsNotSavedByFieldsThatNameOtherTypes() {
        check(
            """
            <fieldType name="string" class="solr.StrField"/>
            <fieldType name="${unused("text_general")}" class="solr.TextField"/>
            <field name="sku" type="string"/>
            <field name="category" type="string"/>
            """,
        )
    }
}
