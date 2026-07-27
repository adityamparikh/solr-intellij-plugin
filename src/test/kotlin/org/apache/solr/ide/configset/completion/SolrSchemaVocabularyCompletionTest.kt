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
        // A single candidate is inserted rather than listed, so the assertion is on the result.
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
        val offered = myFixture.complete(CompletionType.BASIC)
            ?.map { it.lookupString }
            ?: listOf(myFixture.file.text.substringAfter("solr.TextField\">").trim().takeWhile { it.isLetter() })
        assertTrue("expected analyzer, got $offered", offered.any { it.contains("analyzer") })
        assertFalse("copyField is not legal here: $offered", offered.any { it == "copyField" })
        assertFalse("field is not legal here: $offered", offered.any { it == "field" })
    }

    // --- attribute names ------------------------------------------------------------------------

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
        assertEquals(listOf("index", "query"), offered.sorted().sortedBy { it })
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

    // --- the gate ---------------------------------------------------------------------------------

    fun testNothingIsOfferedOutsideAConfigset() {
        val offered = completionsFor(
            """<schema name="t"><fiel<caret></schema>""",
            fileName = "notes.xml",
        )
        assertFalse("dynamicField" in offered)
    }
}
