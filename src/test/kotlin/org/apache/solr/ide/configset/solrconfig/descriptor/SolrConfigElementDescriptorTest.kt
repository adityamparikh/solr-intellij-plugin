package org.apache.solr.ide.configset.solrconfig.descriptor

import com.intellij.codeInsight.completion.CompletionType
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/**
 * What `solrconfig.xml` offers once the plugin owns its structure.
 *
 * The other half of [SolrConfigPresentCompletionTest], which recorded the sibling echo this replaces.
 * Read the two together: what changes here is that a suggestion becomes a fact about Solr rather than
 * a fact about the reader's own file.
 */
class SolrConfigElementDescriptorTest : SolrConfigsetTestCase() {

    private fun completionsFor(body: String): List<String> {
        myFixture.addFileToProject(
            "managed-schema.xml",
            """
            <schema name="t" version="1.7">
              <fieldType name="string" class="solr.StrField"/>
              <field name="id" type="string"/>
            </schema>
            """.trimIndent(),
        )
        myFixture.configureByText("solrconfig.xml", body)
        myFixture.complete(CompletionType.BASIC)
        return myFixture.lookupElementStrings.orEmpty()
    }

    fun testElementCompletionInsideConfigOffersSolrsVocabulary() {
        val offered = completionsFor("<config>\n  <<caret>\n</config>")
        assertTrue("expected requestHandler among $offered", "requestHandler" in offered)
        assertTrue("expected updateHandler among $offered", "updateHandler" in offered)
        assertTrue("expected luceneMatchVersion among $offered", "luceneMatchVersion" in offered)
    }

    /**
     * What Solr retired is carried by the catalog and withheld here.
     *
     * Offering it would be the plugin recommending a configuration Solr warns about on startup —
     * the one thing worse than offering nothing.
     */
    fun testADiscontinuedElementIsNotOffered() {
        val offered = completionsFor("<config>\n  <<caret>\n</config>")
        assertFalse("nrtMode is discontinued: $offered", "nrtMode" in offered)
        assertFalse("mainIndex is discontinued: $offered", "mainIndex" in offered)
    }

    /** Nesting is the point: what belongs under `<query>` is not what belongs under `<config>`. */
    fun testElementCompletionIsScopedToItsParent() {
        val offered = completionsFor("<config>\n  <query>\n    <<caret>\n  </query>\n</config>")
        assertTrue("expected filterCache under query: $offered", "filterCache" in offered)
        assertFalse("dataDir is top-level, not a child of query: $offered", "dataDir" in offered)
    }

    /**
     * The echo is gone, which is the change the earlier fixture exists to make visible.
     *
     * A second `<requestHandler>` was offered `startup` because the first one carried it. Now the
     * attributes offered are the ones Solr reads, and a name only the file knows is not among them.
     */
    fun testAttributeCompletionNoLongerEchoesASibling() {
        val offered = completionsFor(
            """
            <config>
              <updateHandler class="solr.DirectUpdateHandler2" madeUpAttribute="x"/>
              <updateHandler <caret>/>
            </config>
            """.trimIndent(),
        )
        assertFalse("a name only the file knows must not be offered: $offered", "madeUpAttribute" in offered)
    }

    /** And what replaces it comes from the generated vocabulary. */
    fun testAttributeCompletionOffersWhatSolrReads() {
        val offered = completionsFor(
            """
            <config>
              <updateHandler>
                <autoCommit <caret>/>
              </updateHandler>
            </config>
            """.trimIndent(),
        )
        assertTrue("expected maxDocs among $offered", "maxDocs" in offered)
        assertTrue("expected maxTime among $offered", "maxTime" in offered)
        assertFalse(
            "openSearcher is a child element rather than an attribute, and must not be offered here: $offered",
            "openSearcher" in offered,
        )
    }

    /**
     * Attribute or child element is Solr's own distinction, and the vocabulary keeps it.
     *
     * `EditableSolrConfigAttributes.json` codes `openSearcher` odd, which its legend defines as a
     * node rather than an attribute — and real configsets do write `<openSearcher>false</openSearcher>`
     * inside `<autoCommit>` while writing `maxDocs` as an attribute of it. Offering each in the wrong
     * position would teach the file's shape wrongly to the reader using completion to learn it.
     */
    fun testAChildElementIsOfferedAsAnElementRatherThanAnAttribute() {
        val offered = completionsFor(
            """
            <config>
              <updateHandler>
                <autoCommit>
                  <<caret>
                </autoCommit>
              </updateHandler>
            </config>
            """.trimIndent(),
        )
        assertTrue("expected openSearcher as a child element: $offered", "openSearcher" in offered)
    }

    /**
     * Owning the descriptor must not mean validating by absence.
     *
     * `solrconfig.xml` names plugin classes from outside Solr, and a custom component's element is
     * the ordinary case rather than an error. Nothing this adds may paint one red.
     */
    fun testAnUnknownElementIsNotFlagged() {
        myFixture.addFileToProject(
            "managed-schema.xml",
            """<schema name="t" version="1.7"><fieldType name="string" class="solr.StrField"/></schema>""",
        )
        myFixture.configureByText(
            "solrconfig.xml",
            """
            <config>
              <myCompanysComponent name="x" someAttribute="y"/>
            </config>
            """.trimIndent(),
        )
        myFixture.checkHighlighting(true, false, true)
    }
}
