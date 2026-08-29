package org.apache.solr.ide.server.query

import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/**
 * Field names offered inside a Solr query written in an `.http` file.
 *
 * **The clean cases come first, because this contributor is registered against JSON** and therefore
 * reaches every JSON file in every project. Offering Solr field names in somebody else's
 * `package.json` is the same failure as an inspection firing on a correct file, and it is the one
 * worth the most care here.
 */
class SolrQueryFieldCompletionTest : SolrConfigsetTestCase() {

    private val schema = """
        <?xml version="1.0" encoding="UTF-8"?>
        <schema name="books" version="1.6">
          <field name="id" type="string" indexed="true" stored="true" required="true"/>
          <field name="title" type="text_general" indexed="true" stored="true"/>
          <field name="author_s" type="string" indexed="true" stored="true"/>
          <dynamicField name="*_i" type="pint" indexed="true" stored="true"/>
          <fieldType name="string" class="solr.StrField"/>
          <fieldType name="text_general" class="solr.TextField"/>
          <fieldType name="pint" class="solr.IntPointField"/>
        </schema>
    """.trimIndent()

    private fun givenAConfigset() {
        myFixture.addFileToProject("books/conf/managed-schema.xml", schema)
        myFixture.addFileToProject("books/conf/solrconfig.xml", "<config><luceneMatchVersion>10.0.0</luceneMatchVersion></config>")
    }

    private fun offeredIn(body: String): List<String> {
        myFixture.configureByText("queries.http", body)
        myFixture.completeBasic()
        return myFixture.lookupElementStrings.orEmpty()
    }

    private fun queryBody(inner: String) = """
        ### Query
        POST http://localhost:8983/solr/books/query
        Content-Type: application/json

        $inner
    """.trimIndent()

    // --- where nothing may be offered -------------------------------------------------------------

    /**
     * A plain JSON file gets nothing, whatever it happens to contain.
     *
     * The contributor is registered against JSON, so this file reaches it. A `fields` key in an
     * unrelated document is not a Solr query, and treating it as one would put field names into
     * somebody else's configuration.
     */
    fun testAnOrdinaryJsonFileIsUntouched() {
        givenAConfigset()
        myFixture.configureByText("package.json", """{"fields": ["<caret>"]}""")
        myFixture.completeBasic()

        val offered = myFixture.lookupElementStrings.orEmpty()
        assertFalse(offered.toString(), offered.contains("author_s"))
    }

    /** A body that is not a Solr query is left alone even inside an `.http` file. */
    fun testAnHttpRequestThatIsNotASolrQueryIsUntouched() {
        givenAConfigset()

        val offered = offeredIn(
            """
            ### Not Solr
            POST http://example.com/api
            Content-Type: application/json

            {"name": "<caret>"}
            """.trimIndent(),
        )

        assertFalse(offered.toString(), offered.contains("author_s"))
    }

    /** A Solr query body still offers nothing where a field name does not belong. */
    fun testAPositionThatIsNotAFieldNameIsUntouched() {
        givenAConfigset()

        val offered = offeredIn(queryBody("""{"query": "*:*", "limit": <caret>}"""))

        assertFalse(offered.toString(), offered.contains("author_s"))
    }

    // --- where fields belong ----------------------------------------------------------------------

    fun testFieldsAreOfferedInsideAFieldList() {
        givenAConfigset()

        val offered = offeredIn(queryBody("""{"query": "*:*", "fields": ["<caret>"]}"""))

        assertContainsElements(offered, "id", "title", "author_s")
    }

    /** A field list written as one string is the same request as one written as an array. */
    fun testFieldsAreOfferedInAStringFieldList() {
        givenAConfigset()

        val offered = offeredIn(queryBody("""{"query": "*:*", "fields": "<caret>"}"""))

        assertContainsElements(offered, "id", "title")
    }

    fun testFieldsAreOfferedInASort() {
        givenAConfigset()

        val offered = offeredIn(queryBody("""{"query": "*:*", "sort": "<caret>"}"""))

        assertContainsElements(offered, "id", "title")
    }

    fun testFieldsAreOfferedInAFacetField() {
        givenAConfigset()

        val offered = offeredIn(
            queryBody("""{"query": "*:*", "facet": {"authors": {"type": "terms", "field": "<caret>"}}}"""),
        )

        assertContainsElements(offered, "author_s")
    }

    /**
     * A dynamic pattern is offered, because it is what a user types when they mean the field it
     * creates.
     */
    fun testDynamicPatternsAreOffered() {
        givenAConfigset()

        val offered = offeredIn(queryBody("""{"query": "*:*", "fields": ["<caret>"]}"""))

        assertContainsElements(offered, "*_i")
    }

    // --- where the fields come from ---------------------------------------------------------------

    /**
     * With no configset in the project, nothing is offered rather than something invented.
     *
     * The honest answer when the source this feature reads has nothing in it.
     */
    fun testAProjectWithNoConfigsetOffersNoFields() {
        val offered = offeredIn(queryBody("""{"query": "*:*", "fields": ["<caret>"]}"""))

        assertFalse(offered.toString(), offered.contains("author_s"))
    }

    /**
     * A project with two configsets offers both, and each entry says which it came from.
     *
     * Merging them without saying which is which would let a user complete a field that exists in a
     * collection they are not querying.
     */
    fun testFieldsFromEveryConfigsetAreOffered() {
        givenAConfigset()
        myFixture.addFileToProject(
            "films/conf/managed-schema.xml",
            schema.replace("author_s", "director_s").replace("\"books\"", "\"films\""),
        )
        myFixture.addFileToProject("films/conf/solrconfig.xml", "<config><luceneMatchVersion>10.0.0</luceneMatchVersion></config>")

        val offered = offeredIn(queryBody("""{"query": "*:*", "fields": ["<caret>"]}"""))

        assertContainsElements(offered, "author_s", "director_s")
    }
}
