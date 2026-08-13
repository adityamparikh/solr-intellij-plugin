package org.apache.solr.ide.configset.solrconfig.descriptor

import com.intellij.codeInsight.completion.CompletionType
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/**
 * What element and attribute completion offers in `solrconfig.xml` **before** this plugin owns it.
 *
 * **A fixture that exists to be read once and then contradicted.** The plan asked for present-day
 * behaviour to be pinned before the descriptor gate moves, because the account of it — that the
 * platform's schema-less mode echoes whatever attributes same-named sibling tags carry — was
 * inferred from this plugin's own notes rather than measured. `solrconfig.xml` is made of same-named
 * tags, so it is the worst case for that echo and the one most worth having measured.
 *
 * This is a guard of the kind that never fires in a passing suite: it catches a change to behaviour
 * nobody looked at, and it is worth exactly one commit of attention. When the descriptor arm lands,
 * these expectations change — and a diff showing *how* they changed is the point of writing them
 * down first.
 */
class SolrConfigPresentCompletionTest : SolrConfigsetTestCase() {

    private fun configure(body: String) {
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
    }

    private fun completionsFor(body: String): List<String> {
        configure(body)
        myFixture.complete(CompletionType.BASIC)
        return myFixture.lookupElementStrings.orEmpty()
    }

    /**
     * Typing `<` inside `<config>` offers nothing the plugin knows.
     *
     * The gap this whole step is about, stated as a measurement rather than as a complaint: the
     * elements Solr accepts here are `requestHandler`, `query`, `updateHandler` and forty more, and
     * a reader gets whichever tag names happen to appear elsewhere in their own file.
     */
    fun testElementCompletionInsideConfigOffersNoSolrVocabulary() {
        val offered = completionsFor(
            """
            <config>
              <requestHandler name="/select" class="solr.SearchHandler"/>
              <caret>
            </config>
            """.trimIndent(),
        )
        assertFalse(
            "before the descriptor arm, no Solr element vocabulary is offered: $offered",
            "updateHandler" in offered || "luceneMatchVersion" in offered || "codecFactory" in offered,
        )
    }

    /**
     * Attribute completion echoes the sibling, which is the claim that had never been measured.
     *
     * Two `<requestHandler>` tags, one carrying an attribute the other does not. The platform has no
     * schema for this file, so what it offers on the second is drawn from the first — meaning the
     * suggestion is a fact about the reader's own file rather than about Solr.
     */
    fun testAttributeCompletionEchoesASiblingsAttributes() {
        val offered = completionsFor(
            """
            <config>
              <requestHandler name="/select" class="solr.SearchHandler" startup="lazy"/>
              <requestHandler <caret>/>
            </config>
            """.trimIndent(),
        )
        assertTrue(
            "the echo is the present behaviour, and this is where it is recorded: $offered",
            "startup" in offered,
        )
    }

    /**
     * And the echo crosses element kinds where the tag names match, which is the failure mode that
     * matters: an attribute legal on one element is offered on another because the file happens to
     * spell them the same.
     */
    fun testTheEchoIsDrawnFromTheFileRatherThanFromSolr() {
        val offered = completionsFor(
            """
            <config>
              <lst name="defaults"><str name="q">*:*</str></lst>
              <str <caret>/>
            </config>
            """.trimIndent(),
        )
        assertTrue("a sibling's attribute is offered: $offered", "name" in offered)
    }
}
