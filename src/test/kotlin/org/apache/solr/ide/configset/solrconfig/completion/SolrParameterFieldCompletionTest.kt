package org.apache.solr.ide.configset.solrconfig.completion

import com.intellij.codeInsight.completion.CompletionType
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/**
 * Field names offered inside a `solrconfig.xml` parameter that holds them.
 *
 * **What this withholds matters as much as what it offers.** Every parameter in the file looks alike, so
 * a provider that answered everywhere would put field names inside `rows`, inside a `defType`, and after
 * the `^` of a boost — three suggestions that are wrong in three different ways. The negative cases below
 * are the ones that would let those through.
 */
class SolrParameterFieldCompletionTest : SolrConfigsetTestCase() {

    private val schema = """
        <schema name="t" version="1.7">
          <fieldType name="string" class="solr.StrField"/>
          <fieldType name="text_general" class="solr.TextField"/>
          <field name="id" type="string" docValues="true"/>
          <field name="title" type="text_general" indexed="true"/>
          <field name="body" type="text_general" indexed="false" stored="true"/>
          <field name="category" type="string" indexed="true" docValues="false"/>
          <dynamicField name="*_s" type="string" docValues="true"/>
        </schema>
    """.trimIndent()

    /** Added once per test: several cases complete twice, to compare one list against another. */
    private var schemaAdded = false

    private fun completionsFor(body: String): List<String> {
        if (!schemaAdded) {
            myFixture.addFileToProject("managed-schema.xml", schema)
            schemaAdded = true
        }
        myFixture.configureByText("solrconfig.xml", "<config>\n$body\n</config>")
        myFixture.complete(CompletionType.BASIC)
        return myFixture.lookupElementStrings.orEmpty()
    }

    private fun handler(vararg parameters: String) =
        """<requestHandler name="/select"><lst name="defaults">${parameters.joinToString("")}</lst></requestHandler>"""

    // --- what is offered ------------------------------------------------------------------------

    /** The everyday gesture, and the point of the feature. */
    fun testFieldsAreOfferedInsideAQueryField() {
        val offered = completionsFor(handler("""<str name="qf"><caret></str>"""))
        assertTrue("expected title among $offered", "title" in offered)
        assertTrue("dynamic patterns are legitimate here: $offered", "*_s" in offered)
    }

    /** `fl` returns stored values and asks nothing of the index, so every declared field is offered. */
    fun testEveryFieldIsOfferedInsideAFieldList() {
        val offered = completionsFor(handler("""<str name="fl"><caret></str>"""))
        for (field in listOf("id", "title", "body", "category")) {
            assertTrue("expected $field among $offered", field in offered)
        }
    }

    /** An `arr` supplies the parameter name to each `str` inside it. */
    fun testFieldsAreOfferedInsideAnArrEntry() {
        val offered = completionsFor(handler("""<arr name="facet.field"><str><caret></str></arr>"""))
        assertTrue("expected id among $offered", "id" in offered)
    }

    /** Solr's other value tags carry parameters too, and completion follows the parser. */
    fun testFieldsAreOfferedInsideAnIntValueTag() {
        val offered = completionsFor(handler("""<int name="qf"><caret></int>"""))
        assertTrue("expected title among $offered", "title" in offered)
    }

    // --- what the capability rules withhold ------------------------------------------------------

    /**
     * A field the `qf` inspection would underline is not offered, because a completion list that
     * suggested it would be arguing with an inspection in the same file. `body` is a non-indexed text
     * field, which no doc values can rescue.
     */
    fun testAnUnsearchableFieldIsNotOfferedInAQueryField() {
        val offered = completionsFor(handler("""<str name="qf"><caret></str>"""))
        assertFalse("body is unsearchable and must not be offered: $offered", "body" in offered)
    }

    /**
     * The split the operation model exists for, seen from completion: `category` is indexed and has no
     * doc values, so at schema version 1.7 it is searchable and unfacetable. It appears in one list and
     * not the other.
     */
    fun testAFieldMayBeOfferedForSearchingAndNotForFaceting() {
        assertTrue("category is searchable", "category" in completionsFor(handler("""<str name="qf"><caret></str>""")))
        assertFalse(
            "category cannot be faceted at schema 1.7",
            "category" in completionsFor(handler("""<arr name="facet.field"><str><caret></str></arr>""")),
        )
    }

    // --- what the syntax withholds ---------------------------------------------------------------

    /** A caret past a `^` is inside a boost. Completing there would produce `title^title`. */
    fun testNothingIsOfferedInsideABoost() {
        val offered = completionsFor(handler("""<str name="qf">title^<caret></str>"""))
        assertFalse("a boost is not a field name: $offered", "title" in offered)
    }

    /** A field is still offered after a completed boost, because a `qf` holds several. */
    fun testFieldsAreOfferedAfterACompletedBoost() {
        val offered = completionsFor(handler("""<str name="qf">title^3 <caret></str>"""))
        assertTrue("expected id among $offered", "id" in offered)
    }

    /** A sort clause ends in a direction, and `asc` is not a field. */
    fun testNothingIsOfferedInASortDirection() {
        val offered = completionsFor(handler("""<str name="sort">id <caret></str>"""))
        assertFalse("a sort direction is not a field: $offered", "id" in offered)
    }

    /** The first token of each comma-separated sort clause is a field, including the second clause. */
    fun testFieldsAreOfferedAtTheStartOfASecondSortClause() {
        val offered = completionsFor(handler("""<str name="sort">id asc,<caret></str>"""))
        assertTrue("expected a field at the start of the clause: $offered", "id" in offered)
    }

    // --- where nothing is offered at all ---------------------------------------------------------

    /** A parameter that holds no field names holds no field names, however much it looks like one. */
    fun testNothingIsOfferedInsideANonFieldParameter() {
        for (parameter in listOf("rows", "defType", "ps")) {
            val offered = completionsFor(handler("""<str name="$parameter"><caret></str>"""))
            assertFalse("$parameter does not hold field names: $offered", "title" in offered)
        }
    }

    /**
     * `<lst name="defaults">` also appears under an update processor chain, where its contents are not
     * query parameters — the position the reference rules already decline and this must decline too.
     */
    fun testNothingIsOfferedOutsideARequestHandler() {
        val offered = completionsFor(
            """
            <updateRequestProcessorChain name="x">
              <processor class="solr.Custom">
                <lst name="defaults"><str name="qf"><caret></str></lst>
              </processor>
            </updateRequestProcessorChain>
            """.trimIndent(),
        )
        assertFalse("not a query parameter list: $offered", "title" in offered)
    }

    /** Outside a Solr project every surface is inert. */
    fun testNothingIsOfferedOutsideASolrProject() {
        givenNoSolrOnTheClasspath()
        val offered = completionsFor(handler("""<str name="qf"><caret></str>"""))
        assertFalse("no Solr on the classpath: $offered", "title" in offered)
    }
}
