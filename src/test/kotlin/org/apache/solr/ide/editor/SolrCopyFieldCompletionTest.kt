package org.apache.solr.ide.editor

import com.intellij.codeInsight.completion.CompletionType
import org.apache.solr.ide.configset.SolrConfigsetTestCase

/** Completion for a `copyField`'s two ends. */
class SolrCopyFieldCompletionTest : SolrConfigsetTestCase() {

    private val schema = """
        <schema name="products">
          <fieldType name="string" class="solr.StrField"/>
          <field name="id" type="string"/>
          <field name="name" type="string"/>
          <field name="text" type="string"/>
          <dynamicField name="*_s" type="string"/>
          BODY
        </schema>
    """.trimIndent()

    private fun completionsFor(body: String): List<String> {
        myFixture.configureByText("managed-schema.xml", schema.replace("BODY", body))
        myFixture.complete(CompletionType.BASIC)
        return myFixture.lookupElementStrings.orEmpty()
    }

    fun testDeclaredFieldsAreOfferedForASource() {
        val offered = completionsFor("""<copyField source="<caret>" dest="text"/>""")
        assertTrue(offered.containsAll(listOf("id", "name", "text")))
    }

    /** A `copyField` may legitimately name a dynamic pattern, so those belong on the list. */
    fun testDynamicPatternsAreOfferedForADestination() {
        assertTrue("*_s" in completionsFor("""<copyField source="name" dest="<caret>"/>"""))
    }

    fun testNothingElseFromTheFileIsOffered() {
        val offered = completionsFor("""<copyField source="<caret>" dest="text"/>""")
        assertEquals("three fields and one dynamic pattern: $offered", 4, offered.size)
        for (word in listOf("string", "schema", "copyField", "dest")) {
            assertFalse("'$word' appears in the file but is not a field: $offered", word in offered)
        }
    }

    fun testNothingIsOfferedOutsideASolrProject() {
        givenNoSolrOnTheClasspath()
        assertTrue("id" !in completionsFor("""<copyField source="<caret>" dest="text"/>"""))
    }
}
