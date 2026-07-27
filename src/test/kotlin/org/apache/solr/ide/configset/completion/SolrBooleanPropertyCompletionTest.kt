package org.apache.solr.ide.configset.completion

import com.intellij.codeInsight.completion.CompletionType
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/** Completion for the field properties that accept only `true` or `false`. */
class SolrBooleanPropertyCompletionTest : SolrConfigsetTestCase() {

    private val schema = """
        <schema name="products">
          <fieldType name="string" class="solr.StrField"/>
          <field name="id" type="string"/>
          BODY
        </schema>
    """.trimIndent()

    private fun completionsFor(body: String): List<String> {
        myFixture.configureByText("managed-schema.xml", schema.replace("BODY", body))
        myFixture.complete(CompletionType.BASIC)
        return myFixture.lookupElementStrings.orEmpty().sorted()
    }

    fun testEveryBooleanPropertyOffersTrueAndFalse() {
        for (property in listOf("indexed", "stored", "docValues", "multiValued", "required", "omitNorms", "termVectors")) {
            assertEquals(
                "$property takes only true or false",
                listOf("false", "true"),
                completionsFor("""<field name="sku" type="string" $property="<caret>"/>"""),
            )
        }
    }

    fun testBooleanPropertiesAreCompletedOnAFieldTypeToo() {
        assertEquals(listOf("false", "true"), completionsFor("""<fieldType name="t" class="solr.StrField" docValues="<caret>"/>"""))
    }

    /**
     * Where the valid set is not closed, nothing is contributed and the platform's own behaviour is
     * left alone. A partial list in an open-ended position implies the values not on it are wrong.
     */
    fun testAFreeFormAttributeIsLeftToThePlatform() {
        val offered = completionsFor("""<fieldType name="t" class="solr.TextField" positionIncrementGap="<caret>"/>""")
        assertTrue("nothing of ours belongs here: $offered", "true" !in offered && "string" !in offered)
    }

    fun testNothingIsOfferedOutsideASolrProject() {
        givenNoSolrOnTheClasspath()
        assertTrue("true" !in completionsFor("""<field name="sku" type="string" indexed="<caret>"/>"""))
    }
}
