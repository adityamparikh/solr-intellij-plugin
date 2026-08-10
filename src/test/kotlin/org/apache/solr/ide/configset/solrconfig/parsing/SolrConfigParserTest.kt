package org.apache.solr.ide.configset.solrconfig.parsing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.apache.solr.ide.model.schema.SolrFieldOperation
import org.junit.Assert.assertNull
import org.junit.Test

/** Field-reference extraction from `solrconfig.xml`, the file boundary the plugin exists to close. */
class SolrConfigParserTest {

    private val config = """
        <config>
          <requestHandler name="/select" class="solr.SearchHandler">
            <lst name="defaults">
              <str name="defType">edismax</str>
              <str name="df">text</str>
              <str name="qf">name^3 description category</str>
              <str name="fl">id,name,price</str>
              <str name="sort">score desc, name asc</str>
              <str name="rows">10</str>
            </lst>
            <lst name="invariants">
              <str name="pf">name_prefix</str>
            </lst>
          </requestHandler>
          <requestHandler name="/suggest" class="solr.SearchHandler">
            <lst name="defaults">
              <arr name="facet.field"><str>category</str><str>brand</str></arr>
            </lst>
          </requestHandler>
        </config>
    """.trimIndent()

    private fun references(xml: CharSequence = config) = SolrConfigParser.parse(xml).fieldReferences

    @Test
    fun `boosted qf terms yield a field and its boost`() {
        val qf = references().filter { it.parameterName == "qf" }
        assertEquals(listOf("name", "description", "category"), qf.map { it.fieldName })
        assertEquals("3", qf.first().boost)
        assertEquals(null, qf.last().boost)
    }

    @Test
    fun `references record the handler that made them`() {
        assertTrue(references().filter { it.parameterName == "qf" }.all { it.handlerName == "/select" })
        assertEquals("/suggest", references().single { it.fieldName == "brand" }.handlerName)
    }

    @Test
    fun `comma separated field lists are split`() {
        assertEquals(
            listOf("id", "name", "price"),
            references().filter { it.parameterName == "fl" }.map { it.fieldName },
        )
    }

    @Test
    fun `sort clauses contribute the field and not the direction`() {
        assertEquals(
            listOf("score", "name"),
            references().filter { it.parameterName == "sort" }.map { it.fieldName },
        )
    }

    @Test
    fun `arr values are read one field per entry`() {
        assertEquals(
            listOf("category", "brand"),
            references().filter { it.parameterName == "facet.field" }.map { it.fieldName },
        )
    }

    /** `appends` and `invariants` supply parameters just as `defaults` does; only precedence differs. */
    @Test
    fun `invariants and appends are read as well as defaults`() {
        assertEquals("name_prefix", references().single { it.parameterName == "pf" }.fieldName)
        val appended = references(
            """
            <config><requestHandler name="/x"><lst name="appends">
              <str name="fq">public</str><str name="df">body</str>
            </lst></requestHandler></config>
            """.trimIndent(),
        )
        assertEquals("body", appended.single().fieldName)
    }

    /**
     * Parameters not known to name fields contribute nothing. Guessing from the value would turn
     * `rows` and `defType` into fields, and a false reference becomes a false "no such field".
     */
    @Test
    fun `parameters that do not name fields are ignored`() {
        val names = references().map { it.parameterName }.toSet()
        assertTrue("rows" !in names)
        assertTrue("defType" !in names)
    }

    /**
     * Function queries, globs, aliases and parameter references all appear in these parameters and
     * none is a field name.
     */
    @Test
    fun `syntax that is not a field name is excluded`() {
        val tricky = references(
            """
            <config><requestHandler name="/x"><lst name="defaults">
              <str name="fl">*,score,max(price,0),alias:field,${'$'}param</str>
              <str name="qf">name^3 ${'$'}boostParam</str>
            </lst></requestHandler></config>
            """.trimIndent(),
        )
        assertEquals(listOf("score", "name"), tricky.map { it.fieldName })
    }

    @Test
    fun `initParams contributes references under its path`() {
        val fromInitParams = references(
            """
            <config><initParams path="/select,/query"><lst name="defaults">
              <str name="df">text</str>
            </lst></initParams></config>
            """.trimIndent(),
        )
        assertEquals("text", fromInitParams.single().fieldName)
        assertEquals("/select,/query", fromInitParams.single().handlerName)
    }

    @Test
    fun `malformed xml yields no references rather than throwing`() {
        assertTrue(SolrConfigParser.parse("<config><requestHandler").fieldReferences.isEmpty())
        assertTrue(SolrConfigParser.parse("").fieldReferences.isEmpty())
    }

    @Test
    fun `a config with no handlers yields no references`() {
        assertTrue(SolrConfigParser.parse("<config><lst name='x'/></config>").isEmpty)
    }

    /** The declared version is what decides which Reference Guide the plugin links to. */
    @Test
    fun `luceneMatchVersion is captured`() {
        val facts = SolrConfigParser.parse("<config><luceneMatchVersion>10.0.0</luceneMatchVersion></config>")
        assertEquals("10.0.0", facts.luceneMatchVersion)
        assertTrue(facts.fieldReferences.isEmpty())
        assertTrue("a declared version alone means the facts are not empty", !facts.isEmpty)
    }

    // --- which operation a parameter asks for ------------------------------------------------------

    /**
     * One mapping for every caller. An inspection reporting a bad `facet.field` and a completion list
     * filling one in have to agree about what a `facet.field` is *for*, or the plugin offers a field it
     * then underlines. It lives here rather than in `model` because a parameter name is this file's
     * vocabulary, and because it has to stay in step with the three field-name sets beside it.
     */
    @Test
    fun `parameters map to the operation they ask for`() {
        assertEquals(SolrFieldOperation.SEARCH, SolrConfigParser.operationFor("qf"))
        assertEquals(SolrFieldOperation.SEARCH, SolrConfigParser.operationFor("pf3"))
        assertEquals(SolrFieldOperation.FACET, SolrConfigParser.operationFor("facet.pivot"))
        assertEquals(SolrFieldOperation.SORT, SolrConfigParser.operationFor("group.field"))
    }

    /**
     * Null covers two cases worth not conflating: `fl` asks nothing of the index, while `bf` asks for a
     * per-document value but writes it as a function query rather than a field list — so a rule applied
     * to a whole token there would be applied to the wrong thing.
     */
    @Test
    fun `parameters asking nothing checkable map to null`() {
        for (parameter in listOf("fl", "df", "bf", "boost", "rows", "hl.fl")) {
            assertNull(parameter, SolrConfigParser.operationFor(parameter))
        }
    }

    /** Every parameter with an operation must be one the parser reads field names out of. */
    @Test
    fun `every mapped parameter holds field names`() {
        for (parameter in listOf("qf", "pf", "pf2", "pf3", "facet.field", "facet.pivot", "sort", "group.sort", "group.field")) {
            assertTrue(parameter, SolrConfigParser.holdsFieldNames(parameter))
        }
    }
}
