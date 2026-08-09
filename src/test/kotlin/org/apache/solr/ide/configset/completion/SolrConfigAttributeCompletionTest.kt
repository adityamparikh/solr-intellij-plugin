package org.apache.solr.ide.configset.completion

import com.intellij.codeInsight.completion.CompletionType
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/**
 * What attribute-name completion offers inside `solrconfig.xml` today.
 *
 * **A characterization test: it records behaviour rather than asserting a design.** The plugin owns the
 * element descriptors for the schema and declines `solrconfig.xml`, which leaves that file in the
 * platform's schema-less mode. The argument for widening the descriptor rests on what schema-less mode
 * does here, and that was inferred from the plugin's own account of the schema rescue rather than
 * measured — so it is measured here, before a change moves a descriptor over this file and overwrites
 * the evidence.
 *
 * `solrconfig.xml` is the worst case for the platform's fallback, which is why the question matters more
 * here than in a schema: the file is *made of* same-named tags. Every `<str>` is a sibling of every other
 * `<str>`, and every `<requestHandler>` of every other handler, so an answer assembled from what
 * same-named tags elsewhere in the file happen to carry has an unusually large and unusually irrelevant
 * pool to draw from.
 */
class SolrConfigAttributeCompletionTest : SolrConfigsetTestCase() {

    private val schema = """
        <schema name="t" version="1.6">
          <fieldType name="string" class="solr.StrField"/>
          <field name="id" type="string"/>
        </schema>
    """.trimIndent()

    private fun completionsFor(config: String): List<String> {
        myFixture.addFileToProject("managed-schema.xml", schema)
        myFixture.configureByText("solrconfig.xml", config)
        myFixture.complete(CompletionType.BASIC)
        return myFixture.lookupElementStrings.orEmpty()
    }

    /**
     * The echo, if there is one: a distinctive attribute on one handler, and the caret inside another.
     *
     * `startup` is real Solr and appears on exactly one of the two handlers. Whether the platform offers
     * it on the second is the whole question — it is the `solrconfig.xml` equivalent of an ngram filter's
     * `minGramSize` turning up on every `<filter>` in a schema, which is the failure the schema
     * descriptor was written to stop.
     */
    fun testWhatIsOfferedForAHandlerAttributeToday() {
        val offered = completionsFor(
            """
            <config>
              <requestHandler name="/lazy" class="solr.SearchHandler" startup="lazy"/>
              <requestHandler <caret>/>
            </config>
            """.trimIndent(),
        )

        // Recorded, not endorsed. `startup` appearing here is the sibling echo; `name` and `class` are
        // the two attributes the plugin would offer from its own knowledge once it owns this file.
        assertTrue(
            "the sibling echo is the premise of the descriptor change — if this fails, re-argue it: $offered",
            "startup" in offered,
        )
    }

    /**
     * The same question one level down, where the sibling pool is largest.
     *
     * A parameter list is a run of `<str>` tags, so an echo here draws on every other parameter in the
     * file. `name` is the only attribute any of them carries, which makes this the case where the
     * platform's answer happens to be right — worth pinning precisely because a widened descriptor must
     * not make it worse.
     */
    fun testWhatIsOfferedForAParameterAttributeToday() {
        val offered = completionsFor(
            """
            <config>
              <requestHandler name="/select" class="solr.SearchHandler">
                <lst name="defaults">
                  <str name="qf">id</str>
                  <str <caret>/>
                </lst>
              </requestHandler>
            </config>
            """.trimIndent(),
        )

        assertTrue("expected 'name' among $offered", "name" in offered)
    }
}
