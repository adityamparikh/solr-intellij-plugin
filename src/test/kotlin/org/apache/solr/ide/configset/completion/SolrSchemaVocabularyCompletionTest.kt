package org.apache.solr.ide.configset.completion

import com.intellij.codeInsight.completion.CompletionType
import com.intellij.testFramework.DumbModeTestUtils
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

    // --- class names, from the generated catalog ---------------------------------------------------

    /**
     * The `class` attribute means four different things in a schema, and each gets its own
     * population. Offering a tokenizer where a field type belongs would be offering an error.
     */
    fun testAFieldTypesClassOffersFieldTypeImplementations() {
        val offered = completionsFor(
            """
            <schema name="t">
              <fieldType name="x" class="<caret>"/>
            </schema>
            """.trimIndent(),
        )
        assertTrue("expected solr.StrField among $offered", "solr.StrField" in offered)
        assertTrue("expected solr.TextField among $offered", "solr.TextField" in offered)
        assertFalse("a tokenizer is not a field type: $offered", "solr.StandardTokenizerFactory" in offered)
    }

    fun testATokenizersClassOffersTokenizers() {
        val offered = completionsFor(
            """
            <schema name="t">
              <fieldType name="x" class="solr.TextField">
                <analyzer><tokenizer class="<caret>"/></analyzer>
              </fieldType>
            </schema>
            """.trimIndent(),
        )
        assertTrue("expected solr.StandardTokenizerFactory among $offered", "solr.StandardTokenizerFactory" in offered)
        assertFalse("a field type is not a tokenizer: $offered", "solr.StrField" in offered)
    }

    fun testAFiltersClassOffersTokenFilters() {
        val offered = completionsFor(
            """
            <schema name="t">
              <fieldType name="x" class="solr.TextField">
                <analyzer><filter class="<caret>"/></analyzer>
              </fieldType>
            </schema>
            """.trimIndent(),
        )
        assertTrue("expected solr.LowerCaseFilterFactory among $offered", "solr.LowerCaseFilterFactory" in offered)
        assertFalse("a tokenizer is not a filter: $offered", "solr.StandardTokenizerFactory" in offered)
    }

    /**
     * The catalog follows the line the configset declares — and the declaration is in
     * `solrconfig.xml`, not the schema, which is why this needs a two-file configset. Solr 10
     * renamed `EnumField` to `EnumFieldType`, so the two lines offer visibly different vocabularies.
     */
    fun testTheOfferedClassesFollowTheDeclaredSolrLine() {
        val offered = completionsInConfigset(
            "nine",
            "<config><luceneMatchVersion>9.12.0</luceneMatchVersion></config>",
            """
            <schema name="t">
              <fieldType name="x" class="<caret>"/>
            </schema>
            """.trimIndent(),
        )
        assertTrue("9.10 has solr.EnumField: $offered", "solr.EnumField" in offered)
        assertFalse("that is the 9.10 catalog, not 10's: $offered", "solr.BinaryQuantizedDenseVectorField" in offered)
    }

    /**
     * Completion inside a configset that has its own `solrconfig.xml`, kept in its own directory.
     *
     * `configureByText` writes at the fixture root, and a `solrconfig.xml` left there makes the
     * root a configset for every test that runs afterwards — `BasePlatformTestCase` shares one
     * light project across test classes, so that leaks out of this file entirely. It did, and it
     * broke the detector's tests.
     */
    private fun completionsInConfigset(directory: String, solrConfig: String, schemaText: String): List<String> {
        myFixture.addFileToProject("$directory/solrconfig.xml", solrConfig)
        val caret = schemaText.indexOf("<caret>")
        val schema = myFixture.addFileToProject("$directory/managed-schema.xml", schemaText.replace("<caret>", ""))
        myFixture.configureFromExistingVirtualFile(schema.virtualFile)
        myFixture.editor.caretModel.moveToOffset(caret)
        myFixture.complete(CompletionType.BASIC)
        return myFixture.lookupElementStrings.orEmpty()
    }

    /** `class` on a tag that names no class population contributes nothing. */
    fun testClassOnAnUnrelatedTagOffersNothing() {
        val offered = completionsFor(
            """
            <schema name="t">
              <fieldType name="string" class="solr.StrField"/>
              <field name="sku" type="string" class="<caret>"/>
            </schema>
            """.trimIndent(),
        )
        assertFalse("a field has no class: $offered", "solr.StrField" in offered)
    }

    // --- the gate ---------------------------------------------------------------------------------

    /**
     * Completion answers while the project is still indexing.
     *
     * Nothing here reads an index — the model is parsed from the configset's own text — but the
     * platform withholds a contributor that has not said so, which would have disabled this during
     * exactly the minutes a reader is first opening files.
     */
    fun testCompletionAnswersWhileTheProjectIsIndexing() {
        myFixture.configureByText(
            "managed-schema.xml",
            """
            <schema name="t">
              <fieldType name="string" class="solr.StrField"/>
              <field name="sku" type="string" <caret>/>
            </schema>
            """.trimIndent(),
        )
        var offered: List<String> = emptyList()
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            myFixture.complete(CompletionType.BASIC)
            offered = myFixture.lookupElementStrings.orEmpty()
        }
        assertTrue("expected indexed while indexing, got $offered", "indexed" in offered)
    }

    fun testNothingIsOfferedOutsideAConfigset() {
        val offered = completionsFor(
            """<schema name="t"><fiel<caret></schema>""",
            fileName = "notes.xml",
        )
        assertFalse("dynamicField" in offered)
    }
}
