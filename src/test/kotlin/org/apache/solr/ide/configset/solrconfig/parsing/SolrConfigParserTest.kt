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

    /**
     * `fl` written one name per line is Solr, and used to yield a field with a line break in it.
     *
     * Solr separates these names on commas and whitespace alike, so a multi-line `fl` is an ordinary
     * way to write a long list. This branch split on `","` and a literal `" "`, which left the
     * newline inside the token — and a newline is not one of the excluded characters, so the trimmed
     * result was a single "field" spelling two names at once. The unknown-field inspection then
     * reported a field nobody wrote, on a file Solr reads correctly.
     *
     * The boostable branch beside it always split on a whitespace regex and never had this. The two
     * disagreeing about what separates a name from the next is the defect, so the case is asserted
     * for both.
     */
    @Test
    fun `names separated by newlines are read as names`() {
        val xml = """
            <config>
              <requestHandler name="/select" class="solr.SearchHandler">
                <lst name="defaults">
                  <str name="fl">id
            name
            price</str>
                  <str name="qf">name
            description</str>
                </lst>
              </requestHandler>
            </config>
        """.trimIndent()

        val fl = references(xml).filter { it.parameterName == "fl" }
        assertEquals(listOf("id", "name", "price"), fl.map { it.fieldName })

        val qf = references(xml).filter { it.parameterName == "qf" }
        assertEquals(listOf("name", "description"), qf.map { it.fieldName })
    }

    /** Commas and whitespace mix freely in one value, which is also how Solr reads them. */
    @Test
    fun `names separated by a mix of commas and whitespace are read as names`() {
        val xml = """
            <config>
              <requestHandler name="/select" class="solr.SearchHandler">
                <lst name="defaults">
                  <str name="fl">id, name  price,,category</str>
                </lst>
              </requestHandler>
            </config>
        """.trimIndent()

        val fl = references(xml).filter { it.parameterName == "fl" }
        assertEquals(listOf("id", "name", "price", "category"), fl.map { it.fieldName })
    }

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
        for (parameter in listOf("fl", "df", "bf", "boost", "rows", "hl.fl", "terms.fl", "mlt.fl")) {
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

    private fun handlerWith(parameters: String) = SolrConfigParser.parse(
        """
        <config>
          <requestHandler name="/h" class="solr.SearchHandler">
            <lst name="defaults">$parameters</lst>
          </requestHandler>
        </config>
        """.trimIndent(),
    ).fieldReferences.map { it.fieldName }

    /**
     * Both parameters name fields, and each is split the way Solr splits it.
     *
     * Neither was in the grammar until now, so a `terms.fl` produced no field reference at all and a
     * typo in one was invisible rather than merely unexplained.
     */
    @Test
    fun `the terms and more-like-this parameters are read`() {
        assertEquals(listOf("cat"), handlerWith("""<str name="terms.fl">cat</str>"""))
        assertEquals(listOf("name", "features"), handlerWith("""<str name="mlt.fl">name,features</str>"""))
    }

    /**
     * `terms.fl` is repeated, not delimited, and a comma in it is part of the name.
     *
     * `TermsComponent` reads every *occurrence* of the parameter — `params.getParams(TERMS_FIELD)` —
     * and hands each value straight to `indexReader.terms(field)` without splitting. So
     * `terms.fl` of `name,cat` is a broken configuration, and a plugin that split it would resolve
     * both halves and endorse the defect. Several fields are several occurrences, which is what an
     * `arr` writes.
     */
    @Test
    fun `a terms field list is one name however it is punctuated`() {
        assertEquals(listOf("name,cat"), handlerWith("""<str name="terms.fl">name,cat</str>"""))
        assertEquals(
            listOf("name", "cat"),
            handlerWith("""<arr name="terms.fl"><str>name</str><str>cat</str></arr>"""),
        )
    }

    /**
     * More-like-this splits on a comma or a space, matching `Pattern.compile(",| ")` in
     * `MoreLikeThisHandler` rather than the any-whitespace rule `fl` gets.
     */
    @Test
    fun `a more-like-this list splits on a comma or a space`() {
        assertEquals(listOf("name", "features"), handlerWith("""<str name="mlt.fl">name features</str>"""))
    }

    /**
     * A value written across lines still resolves both names here, and Solr would match neither.
     *
     * Solr adds each token verbatim — `list.add(string)` with no trim — so a `mlt.fl` split over
     * lines asks for a field whose name begins with a newline. This parser trims every token, as it
     * must for the parameters where trimming is right, so it reads two healthy field names.
     *
     * **Recorded rather than fixed, because the divergence does not make this inspection wrong.**
     * It answers whether a name is declared, and `name` and `features` genuinely are. What goes
     * unreported is a differently-shaped defect — a parameter value Solr cannot parse — which wants
     * an inspection of its own rather than a distortion of this one. Pinning it here is what stops
     * the next reader assuming the newline case was considered and handled.
     */
    @Test
    fun `a more-like-this value split across lines is read more generously than Solr reads it`() {
        assertEquals(listOf("name", "features"), handlerWith("<str name=\"mlt.fl\">name,\n  features</str>"))
    }

}
