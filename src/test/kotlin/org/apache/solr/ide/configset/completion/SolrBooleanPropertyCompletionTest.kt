package org.apache.solr.ide.configset.completion

import com.intellij.codeInsight.completion.CompletionType
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/** Completion for the field properties that accept only `true` or `false`. */
class SolrBooleanPropertyCompletionTest : SolrConfigsetTestCase() {

    /**
     * The version is part of the fixture's meaning, not decoration: several properties default
     * differently below schema version 1.7, so a fixture that omitted it would be read as 1.0 and
     * would assert 2008's answers.
     */
    private fun schema(version: String = "1.7") = """
        <schema name="products" version="$version">
          <fieldType name="string" class="solr.StrField"/>
          <field name="id" type="string"/>
          BODY
        </schema>
    """.trimIndent()

    private fun completionsFor(body: String): List<String> {
        myFixture.configureByText("managed-schema.xml", schema().replace("BODY", body))
        myFixture.complete(CompletionType.BASIC)
        return myFixture.lookupElementStrings.orEmpty().sorted()
    }

    /** The values marked `default` for a property, on a schema declaring [version]. */
    private fun markedDefaultsFor(body: String, version: String = "1.7"): List<String> {
        myFixture.configureByText("managed-schema.xml", schema(version).replace("BODY", body))
        myFixture.complete(CompletionType.BASIC)
        return myFixture.lookupElements.orEmpty()
            .map { e -> com.intellij.codeInsight.lookup.LookupElementPresentation().also { e.renderElement(it) } }
            .filter { it.typeText == "default" }
            .mapNotNull { it.itemText }
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

    /**
     * "This is what you already have" is usually what a reader is working out, and a list of two
     * identical-looking values cannot answer it.
     */
    fun testTheDefaultValueIsMarked() {
        assertEquals(
            "indexed defaults to true",
            listOf("true"),
            markedDefaultsFor("""<field name="sku" type="string" indexed="<caret>"/>"""),
        )
    }

    fun testMultiValuedMarksFalseAsTheDefault() {
        assertEquals(
            "multiValued defaults to false",
            listOf("false"),
            markedDefaultsFor("""<field name="sku" type="string" multiValued="<caret>"/>"""),
        )
    }

    /**
     * Solr 9.7 flipped `uninvertible` at schema version 1.7 and left earlier schemas alone, so the
     * mark has to follow the file rather than name one answer. Marking `false` on a 1.6 configset
     * would tell the reader that leaving the attribute off gets them a value it does not.
     */
    fun testUninvertibleMarksTheDefaultForTheDeclaredSchemaVersion() {
        val body = """<field name="sku" type="string" uninvertible="<caret>"/>"""
        assertEquals(
            "below 1.7 a field is uninvertible unless it says otherwise",
            listOf("true"),
            markedDefaultsFor(body, version = "1.6"),
        )
        assertEquals(
            "from 1.7 the default flipped",
            listOf("false"),
            markedDefaultsFor(body, version = "1.7"),
        )
    }

    /**
     * Where Solr's default depends on the field type — `omitNorms` is true for primitive types and
     * false for text — neither value is marked. Marking one would assert something Solr does not.
     */
    fun testNothingIsMarkedWhenTheDefaultDependsOnTheFieldType() {
        assertEquals(
            "omitNorms has no single default to claim",
            emptyList<String>(),
            markedDefaultsFor("""<field name="sku" type="string" omitNorms="<caret>"/>"""),
        )
    }
}
