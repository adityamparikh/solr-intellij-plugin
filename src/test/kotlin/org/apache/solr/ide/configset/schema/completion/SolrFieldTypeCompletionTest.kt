package org.apache.solr.ide.configset.schema.completion

import com.intellij.codeInsight.completion.CompletionType
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/**
 * Completion for a field's `type`.
 *
 * The negative cases assert the offered list is *exactly* the declared types. A list implies that
 * what is not on it is wrong, so an incomplete one would be worse than none.
 */
class SolrFieldTypeCompletionTest : SolrConfigsetTestCase() {

    private val schema = """
        <schema name="products">
          <fieldType name="string" class="solr.StrField"/>
          <fieldType name="text_general" class="solr.TextField">
            <analyzer type="index">
              <tokenizer class="solr.StandardTokenizerFactory"/>
              <filter class="solr.LowerCaseFilterFactory"/>
            </analyzer>
          </fieldType>
          <field name="id" type="string" indexed="true" stored="true"/>
          BODY
        </schema>
    """.trimIndent()

    private fun completionsFor(body: String, fileName: String = "managed-schema.xml"): List<String> {
        myFixture.configureByText(fileName, schema.replace("BODY", body))
        myFixture.complete(CompletionType.BASIC)
        return myFixture.lookupElementStrings.orEmpty()
    }

    fun testDeclaredTypesAreOffered() {
        assertEquals(listOf("string", "text_general"), completionsFor("""<field name="sku" type="<caret>"/>""").sorted())
    }

    fun testTypesAreOfferedForADynamicFieldToo() {
        assertTrue("string" in completionsFor("""<dynamicField name="*_x" type="<caret>"/>"""))
    }

    /**
     * Exactly the declared types, and in particular not `stored` — which appears in this schema as
     * an attribute name and would typecheck as far as the editor is concerned while failing when
     * Solr loads the core.
     */
    fun testNothingElseFromTheFileIsOffered() {
        val offered = completionsFor("""<field name="sku" type="<caret>"/>""")
        assertEquals("the offered set must be exactly the declared types: $offered", 2, offered.size)
        for (word in listOf("stored", "indexed", "schema", "products", "id")) {
            assertFalse("'$word' appears in the file but is not a field type: $offered", word in offered)
        }
    }

    /** A new field's name is the author's to choose, so nothing is offered for it. */
    fun testAFieldsNameIsNotCompleted() {
        assertTrue("id" !in completionsFor("""<field name="<caret>" type="string"/>"""))
    }

    fun testNothingIsOfferedOutsideASolrProject() {
        givenNoSolrOnTheClasspath()
        assertTrue("string" !in completionsFor("""<field name="sku" type="<caret>"/>"""))
    }

    fun testNothingIsOfferedInAFileThatIsNotAConfigset() {
        assertTrue("string" !in completionsFor("""<field name="sku" type="<caret>"/>""", fileName = "notes.xml"))
    }
}
