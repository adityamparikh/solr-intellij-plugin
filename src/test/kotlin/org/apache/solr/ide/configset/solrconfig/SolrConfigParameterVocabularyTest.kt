package org.apache.solr.ide.configset.solrconfig

import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.documentation.DocumentationManager
import com.intellij.lang.documentation.DocumentationProvider
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/**
 * The parameter vocabulary of `solrconfig.xml`: what may be set, what `defType` accepts, and what each
 * one means.
 *
 * **This is the question the plugin answered last, having answered the harder one first.** Field-name
 * completion inside `<str name="qf">` already worked, which presumes the reader knew to write `qf` — and
 * someone who does not will never meet it in a file that does not already use it. A reader reported
 * exactly that: the parameters were inert, and there was nowhere to learn them.
 *
 * The negative cases carry as much weight as the positive ones. A parameter list is spelled the same
 * whether it configures a query or an update processor chain, and offering `facet.range.gap` to the
 * latter would be offering nonsense with a straight face.
 */
class SolrConfigParameterVocabularyTest : SolrConfigsetTestCase() {

    private val schema = """
        <schema name="t" version="1.7">
          <fieldType name="string" class="solr.StrField"/>
          <field name="id" type="string"/>
        </schema>
    """.trimIndent()

    private var schemaAdded = false

    private fun configure(body: String) {
        if (!schemaAdded) {
            myFixture.addFileToProject("managed-schema.xml", schema)
            schemaAdded = true
        }
        myFixture.configureByText("solrconfig.xml", "<config>\n$body\n</config>")
    }

    private fun completionsFor(body: String): List<String> {
        configure(body)
        myFixture.complete(CompletionType.BASIC)
        return myFixture.lookupElementStrings.orEmpty()
    }

    private fun documentationFor(body: String): String? {
        configure(body)
        val target = DocumentationManager.getInstance(project)
            .findTargetElement(myFixture.editor, myFixture.file)
            ?: return null
        val provider = DocumentationManager.getProviderFromElement(target) as DocumentationProvider
        return provider.generateDoc(target, myFixture.file.findElementAt(myFixture.caretOffset))
    }

    private fun handler(body: String) =
        """<requestHandler name="/select" class="solr.SearchHandler"><lst name="defaults">$body</lst></requestHandler>"""

    // --- parameter names ---------------------------------------------------------------------------

    /** The four the reader named, each from a different place in Solr's vocabulary. */
    fun testTheEverydayParametersAreOffered() {
        val offered = completionsFor(handler("""<str name="<caret>"></str>"""))
        for (parameter in listOf("df", "qf", "pf", "rows", "fl", "q", "sort", "defType")) {
            assertTrue("expected $parameter among ${offered.size} offered", parameter in offered)
        }
    }

    /**
     * `defType` is offered, which it was not in the first generated resource.
     *
     * It is declared in `org.apache.solr.search.QueryParsing` rather than in the params package every
     * other parameter comes from, so a pass scoped to that package produced 340 parameters with the most
     * asked-about one missing — a plausible list and no error, which is this generator's standing failure
     * mode. Naming it here is what turns a repeat into a failure.
     */
    fun testDefTypeIsOfferedDespiteBeingDeclaredElsewhere() {
        val offered = completionsFor(handler("""<str name="<caret>"></str>"""))
        assertTrue("defType is declared outside the params package: $offered", "defType" in offered)
        assertTrue("so are these two", "q.op" in offered && "sow" in offered)
    }

    /** An `arr` names a repeated parameter, so its `name` is a parameter name in the same sense. */
    fun testParametersAreOfferedOnARepeatedParameter() {
        val offered = completionsFor(handler("""<arr name="<caret>"></arr>"""))
        assertTrue("expected facet.field among $offered", "facet.field" in offered)
    }

    /** A parameter already set cannot be set twice, so offering it would be offering a duplicate. */
    fun testAParameterAlreadySetIsNotOfferedAgain() {
        val offered = completionsFor(
            handler("""<str name="qf">id</str><str name="<caret>"></str>"""),
        )
        assertTrue("qf is already set: $offered", "qf" !in offered)
        assertTrue("but its neighbours are not", "pf" in offered)
    }

    /**
     * A parameter list that configures something other than a query is offered nothing.
     *
     * `<lst name="defaults">` under an update processor chain is a real element with a real meaning, and
     * the query vocabulary has nothing to do with it. This is the same position test the parser and the
     * unknown-field inspection use, asked rather than restated.
     */
    fun testNoParametersAreOfferedOutsideAQueryParameterList() {
        val offered = completionsFor(
            """<updateRequestProcessorChain name="x"><lst name="defaults"><str name="<caret>"></str></lst></updateRequestProcessorChain>""",
        )
        assertTrue("the query vocabulary does not belong here: $offered", "qf" !in offered)
    }

    /** The `name` of an element that is not a parameter is not a parameter name. */
    fun testTheNameOfARequestHandlerIsNotAParameterName() {
        val offered = completionsFor("""<requestHandler name="<caret>" class="solr.SearchHandler"/>""")
        assertTrue("a handler's name is the author's to choose: $offered", "qf" !in offered)
    }

    // --- defType values ---------------------------------------------------------------------------

    /** The closed set, which is the only parameter value in this file that is one. */
    fun testTheQueryParsersAreOfferedInsideADefType() {
        val offered = completionsFor(handler("""<str name="defType"><caret></str>"""))
        for (parser in listOf("edismax", "dismax", "lucene", "func")) {
            assertTrue("expected $parser among $offered", parser in offered)
        }
    }

    /**
     * A parser *class* is not offered where a registered name belongs.
     *
     * The two populations describe the same plugins and are written in different places: `defType` takes
     * `edismax`, while a `<queryParser>` element's `class` takes `solr.ExtendedDismaxQParserPlugin`.
     * Offering the class here would produce a configset that fails to load.
     */
    fun testAParserClassIsNotOfferedWhereANameBelongs() {
        val offered = completionsFor(handler("""<str name="defType"><caret></str>"""))
        assertTrue("a class name is not a registry key: $offered", offered.none { it.startsWith("solr.") })
    }

    /** And a parser name is not offered where a class belongs, which is the same mistake reversed. */
    fun testAParserNameIsNotOfferedWhereAClassBelongs() {
        val offered = completionsFor("""<queryParser name="mine" class="<caret>"/>""")
        assertTrue("expected the classes", offered.any { it.startsWith("solr.") && it.endsWith("QParserPlugin") })
        assertTrue("a registry key is not a class: $offered", "edismax" !in offered)
    }

    /** A parameter whose values are not enumerable is offered none, rather than a misleading few. */
    fun testNoValuesAreOfferedForAParameterWithAnOpenSet() {
        val offered = completionsFor(handler("""<int name="rows"><caret></int>"""))
        assertTrue("any integer is legal in rows: $offered", "edismax" !in offered)
    }

    // --- documentation ----------------------------------------------------------------------------

    /** The reader's actual request: hovering `qf` says what `qf` is. */
    fun testHoveringAParameterNameExplainsIt() {
        val doc = documentationFor(handler("""<str name="q<caret>f">id</str>"""))
        assertNotNull("expected documentation for qf", doc)
        assertTrue("expected the name: $doc", doc!!.contains("qf"))
        assertTrue("expected Solr's own words: $doc", doc.contains("query fields"))
        assertTrue("expected the declaring interface: $doc", doc.contains("DisMaxParams"))
    }

    /** `df` comes from a different interface and reads differently, which is worth pinning separately. */
    fun testHoveringTheDefaultQueryFieldExplainsIt() {
        val doc = documentationFor(handler("""<str name="d<caret>f">id</str>"""))
        assertNotNull("expected documentation for df", doc)
        assertTrue("expected Solr's own words: $doc", doc!!.contains("default query field"))
    }

    /** Hovering a `defType` value says what that parser is, from the plugin class's own Javadoc. */
    fun testHoveringADefTypeValueExplainsTheParser() {
        val doc = documentationFor(handler("""<str name="defType">edis<caret>max</str>"""))
        assertNotNull("expected documentation for edismax", doc)
        assertTrue("expected the class behind it: $doc", doc!!.contains("ExtendedDismaxQParserPlugin"))
    }

    /**
     * A parameter Solr does not declare documents nothing.
     *
     * **This is the contract, not a gap.** `solrconfig.xml` accepts components from outside Solr that
     * read parameters of their author's choosing, so an unknown name is the ordinary case — and an empty
     * popup would be a claim where silence is honest. Nothing may report it as invalid either; the
     * resource is a completion source and never a membership test.
     */
    fun testHoveringACustomParameterSaysNothing() {
        val doc = documentationFor(handler("""<str name="my.own.<caret>param">x</str>"""))
        assertNull("a parameter outside Solr's vocabulary must not be described: $doc", doc)
    }

    /** And a `defType` naming a parser this line does not register documents nothing. */
    fun testHoveringAnUnknownDefTypeSaysNothing() {
        val doc = documentationFor(handler("""<str name="defType">no<caret>such</str>"""))
        assertNull("an unregistered parser must not be described: $doc", doc)
    }
}
