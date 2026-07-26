package org.apache.solr.ide.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun `a config with no handlers yields nothing`() {
        assertTrue(SolrConfigParser.parse("<config><luceneMatchVersion>10.0.0</luceneMatchVersion></config>").isEmpty)
    }
}
