package org.apache.solr.ide.configset.completion

import com.intellij.codeInsight.completion.CompletionType
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/**
 * Completion of the schema's own vocabulary — the element and attribute *names*.
 *
 * Value completion answers "what goes here"; this answers "what may I write at all", which is the
 * question a reader who has not learned the vocabulary is actually asking.
 */
class SolrSchemaVocabularyCompletionTest : SolrConfigsetTestCase() {

    private fun completionsFor(text: String, fileName: String = "managed-schema.xml"): List<String> {
        myFixture.configureByText(fileName, text)
        myFixture.complete(CompletionType.BASIC)
        return myFixture.lookupElementStrings.orEmpty()
    }

    // --- element names --------------------------------------------------------------------------

    fun testElementsLegalInsideSchemaAreOffered() {
        // No typed prefix: IntelliJ filters the lookup by what has been typed, so `<fiel` could
        // never show `uniqueKey` however correct the provider is.
        val offered = completionsFor(
            """
            <schema name="t">
              <fieldType name="string" class="solr.StrField"/>
              <caret>
            </schema>
            """.trimIndent(),
        )
        for (element in listOf("field", "dynamicField", "fieldType", "copyField", "uniqueKey")) {
            assertTrue("expected $element among $offered", element in offered)
        }
    }

    /**
     * Nesting is respected. A `copyField` inside an `analyzer` is not a thing, and offering it
     * teaches the reader something false about Solr.
     */
    fun testOnlyAnalyzerIsOfferedInsideAFieldType() {
        myFixture.configureByText(
            "managed-schema.xml",
            """
            <schema name="t">
              <fieldType name="text" class="solr.TextField">
                <caret>
              </fieldType>
            </schema>
            """.trimIndent(),
        )
        val offered = myFixture.complete(CompletionType.BASIC).orEmpty().map { it.lookupString }

        // Exactly one, not merely "analyzer is present". The point of nesting awareness is what it
        // withholds, so a test that only checks the expected entry would pass on a provider that
        // offered every element in the schema.
        assertEquals(listOf("analyzer"), offered)
    }

    /**
     * The path a reader actually takes: typing `<fie` and asking. The element being typed is not
     * one this knows children for, so the candidates come from what is legal *beside* it — which is
     * a different branch from the one a bare caret exercises, and the more common of the two.
     */
    fun testElementsAreOfferedWhileTheTagIsBeingTyped() {
        val offered = completionsFor(
            """
            <schema name="t">
              <fieldType name="string" class="solr.StrField"/>
              <fiel<caret>
            </schema>
            """.trimIndent(),
        )
        assertTrue("expected field among $offered", "field" in offered)
        assertTrue("expected fieldType among $offered", "fieldType" in offered)
    }

    // --- attribute names ------------------------------------------------------------------------

    /**
     * A `copyField` carries `source`, `dest` and `maxChars` — none of which is a field property.
     * The property table is the only thing this knows, so the honest answer is silence rather than
     * a list of attributes that would be errors on this tag.
     */
    fun testNoAttributesAreOfferedOnATagWithNoKnownProperties() {
        val offered = completionsFor(
            """
            <schema name="t">
              <fieldType name="string" class="solr.StrField"/>
              <field name="sku" type="string"/>
              <copyField source="sku" <caret>/>
            </schema>
            """.trimIndent(),
        )
        assertFalse("indexed is not a copyField attribute: $offered", "indexed" in offered)
        assertFalse("sortMissingLast is not a copyField attribute: $offered", "sortMissingLast" in offered)
    }

    fun testFieldAttributesAreOffered() {
        val offered = completionsFor(
            """
            <schema name="t">
              <fieldType name="string" class="solr.StrField"/>
              <field name="sku" type="string" <caret>/>
            </schema>
            """.trimIndent(),
        )
        for (attribute in listOf("indexed", "stored", "docValues", "sortMissingLast", "omitNorms")) {
            assertTrue("expected $attribute among $offered", attribute in offered)
        }
    }

    /** An attribute cannot be written twice, so offering one already present is offering an error. */
    fun testAttributesAlreadyPresentAreNotOffered() {
        val offered = completionsFor(
            """
            <schema name="t">
              <fieldType name="string" class="solr.StrField"/>
              <field name="sku" type="string" indexed="true" <caret>/>
            </schema>
            """.trimIndent(),
        )
        assertFalse("indexed is already on the tag: $offered", "indexed" in offered)
        assertTrue("stored is not: $offered", "stored" in offered)
    }

    /** A field cannot carry the properties that configure a type's own behaviour. */
    fun testTypeOnlyPropertiesAreNotOfferedOnAField() {
        val offered = completionsFor(
            """
            <schema name="t">
              <fieldType name="string" class="solr.StrField"/>
              <field name="sku" type="string" <caret>/>
            </schema>
            """.trimIndent(),
        )
        assertFalse("positionIncrementGap is type-only: $offered", "positionIncrementGap" in offered)
    }

    fun testTypeOnlyPropertiesAreOfferedOnAFieldType() {
        val offered = completionsFor(
            """
            <schema name="t">
              <fieldType name="text" class="solr.TextField" <caret>/>
            </schema>
            """.trimIndent(),
        )
        assertTrue("expected positionIncrementGap among $offered", "positionIncrementGap" in offered)
        assertTrue("a fieldType also carries the field properties: $offered", "indexed" in offered)
    }

    // --- closed non-boolean values ---------------------------------------------------------------

    fun testAnalyzerPhasesAreOffered() {
        val offered = completionsFor(
            """
            <schema name="t">
              <fieldType name="text" class="solr.TextField">
                <analyzer type="<caret>"/>
              </fieldType>
            </schema>
            """.trimIndent(),
        )
        assertEquals(listOf("index", "query"), offered.sorted())
    }

    fun testSynonymQueryStyleOffersItsThreeValues() {
        val offered = completionsFor(
            """
            <schema name="t">
              <fieldType name="text" class="solr.TextField" synonymQueryStyle="<caret>"/>
            </schema>
            """.trimIndent(),
        )
        assertEquals(listOf("as_distinct_terms", "as_same_term", "pick_best"), offered.sorted())
    }

    /**
     * The same attribute name on a tag that cannot carry it. `synonymQueryStyle` configures a field
     * type; on a `copyField` it means nothing, and offering its three values there would present
     * nonsense as though Solr accepted it.
     */
    fun testAPropertysValuesAreNotOfferedOnATagThatCannotCarryIt() {
        val offered = completionsFor(
            """
            <schema name="t">
              <fieldType name="text" class="solr.TextField"/>
              <copyField source="a" dest="b" synonymQueryStyle="<caret>"/>
            </schema>
            """.trimIndent(),
        )
        assertFalse("not a copyField attribute: $offered", "as_same_term" in offered)
    }

    // --- the gate ---------------------------------------------------------------------------------

    fun testNothingIsOfferedOutsideAConfigset() {
        val offered = completionsFor(
            """<schema name="t"><fiel<caret></schema>""",
            fileName = "notes.xml",
        )
        assertFalse("dynamicField" in offered)
    }
}
