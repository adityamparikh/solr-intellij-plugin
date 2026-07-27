package org.apache.solr.ide.editor

import org.apache.solr.ide.configset.SolrConfigsetTestCase

/**
 * What is offered in each attribute value, and what must not be.
 *
 * The negative cases assert that the offered list is *exactly* the valid set — nothing from
 * elsewhere in the file, and nothing at all in positions where any value is legal. A list implies
 * that what is not on it is wrong, so an incomplete list in an open-ended position would be worse
 * than no list.
 */
class SolrConfigsetCompletionTest : SolrConfigsetTestCase() {

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
          <field name="name" type="text_general" indexed="true"/>
          <dynamicField name="*_s" type="string"/>
          <copyField source="name" dest="text"/>
          BODY
        </schema>
    """.trimIndent()

    /** The lookup offered at `<caret>`, with the fixture placed so the file is a configset. */
    private fun completionsFor(body: String): List<String> {
        myFixture.configureByText("managed-schema.xml", schema.replace("BODY", body))
        myFixture.complete(com.intellij.codeInsight.completion.CompletionType.BASIC)
        return myFixture.lookupElementStrings.orEmpty()
    }

    // --- what is offered ------------------------------------------------------------------------

    fun testFieldTypesAreOfferedForAFieldsType() {
        val offered = completionsFor("""<field name="sku" type="<caret>"/>""")
        assertEquals(listOf("string", "text_general"), offered.sorted())
    }

    fun testFieldTypesAreOfferedForADynamicFieldsType() {
        assertTrue("string" in completionsFor("""<dynamicField name="*_x" type="<caret>"/>"""))
    }

    fun testDeclaredFieldsAreOfferedForACopyFieldSource() {
        val offered = completionsFor("""<copyField source="<caret>" dest="text"/>""")
        assertTrue("id" in offered)
        assertTrue("name" in offered)
    }

    /** A `copyField` may legitimately name a dynamic pattern, so those belong on the list. */
    fun testDynamicPatternsAreOfferedForACopyFieldDestination() {
        assertTrue("*_s" in completionsFor("""<copyField source="name" dest="<caret>"/>"""))
    }

    fun testBooleansAreOfferedForBooleanProperties() {
        for (property in listOf("indexed", "stored", "docValues", "multiValued", "required", "omitNorms")) {
            assertEquals(
                "$property takes only true or false",
                listOf("false", "true"),
                completionsFor("""<field name="sku" type="string" $property="<caret>"/>""").sorted(),
            )
        }
    }

    fun testBooleanPropertiesAreCompletedOnAFieldTypeToo() {
        assertEquals(
            listOf("false", "true"),
            completionsFor("""<fieldType name="t" class="solr.StrField" docValues="<caret>"/>""").sorted(),
        )
    }

    // --- what must not be offered ----------------------------------------------------------------

    /**
     * The offered set is exactly the declared types — nothing else from the file, and in particular
     * not `stored`, which appears in this schema as an attribute name and would typecheck as far as
     * the editor is concerned while failing when Solr loads the core.
     */
    fun testOnlyDeclaredTypesAreOfferedForAFieldsType() {
        val offered = completionsFor("""<field name="sku" type="<caret>"/>""")
        assertEquals("the offered set must be exactly the declared types: $offered", 2, offered.size)
        for (word in listOf("stored", "indexed", "copyField", "schema", "products", "name")) {
            assertFalse("'$word' appears in the file but is not a field type: $offered", word in offered)
        }
    }

    fun testOnlyBooleansAreOfferedForABooleanProperty() {
        val offered = completionsFor("""<field name="sku" type="string" indexed="<caret>"/>""")
        assertEquals("only true and false may be offered: $offered", 2, offered.size)
    }

    /**
     * Where the valid set is not closed, nothing is contributed and the platform's own behaviour is
     * left alone. A partial list in an open-ended position implies the values not on it are wrong.
     */
    fun testFreeFormAttributesAreLeftToThePlatform() {
        val offered = completionsFor("""<fieldType name="t" class="solr.TextField" positionIncrementGap="<caret>"/>""")
        assertTrue("nothing of ours belongs here: $offered", "string" !in offered && "true" !in offered)
    }

    fun testAFieldsNameIsNotCompleted() {
        val offered = completionsFor("""<field name="<caret>" type="string"/>""")
        assertTrue("a new field's name is the author's to choose: $offered", "id" !in offered)
    }

    // --- the gate ---------------------------------------------------------------------------------

    fun testNothingIsOfferedOutsideASolrProject() {
        givenNoSolrOnTheClasspath()
        val offered = completionsFor("""<field name="sku" type="<caret>"/>""")
        assertTrue("string" !in offered)
    }

    fun testNothingIsOfferedInAFileThatIsNotAConfigset() {
        myFixture.configureByText("notes.xml", schema.replace("BODY", """<field name="sku" type="<caret>"/>"""))
        myFixture.complete(com.intellij.codeInsight.completion.CompletionType.BASIC)
        assertTrue("string" !in myFixture.lookupElementStrings.orEmpty())
    }
}
