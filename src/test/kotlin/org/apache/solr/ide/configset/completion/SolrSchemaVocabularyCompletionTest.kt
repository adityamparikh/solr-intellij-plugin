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
     * Its own three are offered; the property table's are not, because they would be errors on
     * this tag.
     */
    fun testACopyFieldOffersItsOwnAttributesAndNoFieldProperties() {
        val offered = completionsFor(
            """
            <schema name="t">
              <fieldType name="string" class="solr.StrField"/>
              <field name="sku" type="string"/>
              <copyField source="sku" <caret>/>
            </schema>
            """.trimIndent(),
        )
        assertTrue("expected dest among $offered", "dest" in offered)
        assertTrue("expected maxChars among $offered", "maxChars" in offered)
        assertFalse("source is already on the tag: $offered", "source" in offered)
        assertFalse("indexed is not a copyField attribute: $offered", "indexed" in offered)
        assertFalse("sortMissingLast is not a copyField attribute: $offered", "sortMissingLast" in offered)
    }

    /**
     * The root element's own vocabulary. `version` is the one worth offering — it silently changes
     * schema-wide defaults, and a reader who has never met it will not type it unprompted.
     */
    fun testTheSchemaTagOffersItsOwnAttributes() {
        val offered = completionsFor(
            """
            <schema <caret>>
              <fieldType name="string" class="solr.StrField"/>
            </schema>
            """.trimIndent(),
        )
        assertTrue("expected name among $offered", "name" in offered)
        assertTrue("expected version among $offered", "version" in offered)
        assertFalse("indexed is a field property, not a schema attribute: $offered", "indexed" in offered)
    }

    /** An `analyzer` carries exactly `type`, whose two values already complete. */
    fun testAnAnalyzerOffersItsTypeAttribute() {
        val offered = completionsFor(
            """
            <schema name="t">
              <fieldType name="text" class="solr.TextField">
                <analyzer <caret>>
                  <tokenizer class="solr.StandardTokenizerFactory"/>
                </analyzer>
              </fieldType>
            </schema>
            """.trimIndent(),
        )
        assertTrue("expected type among $offered", "type" in offered)
        assertFalse("indexed is a field property, not an analyzer attribute: $offered", "indexed" in offered)
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

    /**
     * A tag carrying no attributes yet, which every other test in this section skips over — they
     * all start from a tag that already has one.
     *
     * `isAttributePosition` measures the tag's header out to the end of its last attribute, which
     * reads as though it must collapse to a single point here and misclassify the caret. It does
     * not: the platform inserts its dummy identifier at the caret before the tag is reparsed, so by
     * the time the range is computed there *is* a last attribute — the dummy itself. That is a
     * load-bearing accident of how completion works rather than anything the range says, so it is
     * pinned here.
     */
    fun testAttributesAreOfferedOnATagThatHasNoneYet() {
        val offered = completionsFor(
            """
            <schema name="t">
              <fieldType name="string" class="solr.StrField"/>
              <field <caret>/>
            </schema>
            """.trimIndent(),
        )
        assertTrue("expected name among $offered", "name" in offered)
        assertTrue("expected type among $offered", "type" in offered)
        assertFalse("a child element is not an attribute: $offered", "analyzer" in offered)
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

    // --- factory attributes, from the constructor-bytecode pass ------------------------------------

    /**
     * The case that prompted this: a reader writing an ngram filter cannot know what it accepts
     * without leaving the editor, and the names exist nowhere a listing of classes could find them.
     */
    fun testAFiltersAttributesComeFromItsClass() {
        val offered = completionsFor(
            """
            <schema name="t">
              <fieldType name="x" class="solr.TextField">
                <analyzer><filter class="solr.EdgeNGramFilterFactory" <caret>/></analyzer>
              </fieldType>
            </schema>
            """.trimIndent(),
        )
        assertTrue("expected minGramSize among $offered", "minGramSize" in offered)
        assertTrue("expected maxGramSize among $offered", "maxGramSize" in offered)
        assertTrue("expected preserveOriginal among $offered", "preserveOriginal" in offered)
    }

    /** An attribute already written is not offered again, here as anywhere else. */
    fun testAnAttributeAlreadyWrittenIsNotOfferedAgain() {
        val offered = completionsFor(
            """
            <schema name="t">
              <fieldType name="x" class="solr.TextField">
                <analyzer><filter class="solr.EdgeNGramFilterFactory" minGramSize="2" <caret>/></analyzer>
              </fieldType>
            </schema>
            """.trimIndent(),
        )
        assertFalse("minGramSize is already written: $offered", "minGramSize" in offered)
        assertTrue("maxGramSize is not: $offered", "maxGramSize" in offered)
    }

    /** Each factory gets its own attributes; a tokenizer's are not a filter's. */
    fun testATokenizersAttributesAreItsOwn() {
        val offered = completionsFor(
            """
            <schema name="t">
              <fieldType name="x" class="solr.TextField">
                <analyzer><tokenizer class="solr.JapaneseTokenizerFactory" <caret>/></analyzer>
              </fieldType>
            </schema>
            """.trimIndent(),
        )
        assertTrue("expected mode among $offered", "mode" in offered)
        assertTrue("expected userDictionary among $offered", "userDictionary" in offered)
        assertFalse("that is an ngram attribute: $offered", "minGramSize" in offered)
    }

    /**
     * A sibling's attributes are not echoed. The platform's schema-less XML fallback offers
     * attribute names collected from same-named tags elsewhere in the file — an EdgeNGram filter
     * next door put `minGramSize` on every `<filter>` in the schema. Owning the element
     * descriptor is what replaces that guess with the catalog's answer.
     */
    fun testAnotherFactorysAttributesAreNotEchoedFromSiblingTags() {
        val offered = completionsFor(
            """
            <schema name="t">
              <fieldType name="x" class="solr.TextField">
                <analyzer>
                  <filter class="solr.LowerCaseFilterFactory" <caret>/>
                  <filter class="solr.EdgeNGramFilterFactory" minGramSize="2" maxGramSize="15"/>
                </analyzer>
              </fieldType>
            </schema>
            """.trimIndent(),
        )
        assertFalse("minGramSize belongs to the sibling EdgeNGram filter: $offered", "minGramSize" in offered)
        assertFalse("maxGramSize belongs to the sibling EdgeNGram filter: $offered", "maxGramSize" in offered)
    }

    /** One entry per attribute: the descriptor and the contributor must not both add a row. */
    fun testAttributeNamesAreOfferedExactlyOnce() {
        val offered = completionsFor(
            """
            <schema name="t">
              <fieldType name="string" class="solr.StrField"/>
              <field name="sku" type="string" <caret>/>
            </schema>
            """.trimIndent(),
        )
        assertEquals("indexed offered more than once: $offered", 1, offered.count { it == "indexed" })
        assertEquals("stored offered more than once: $offered", 1, offered.count { it == "stored" })
    }

    /**
     * A class the catalog does not know accepts attributes the plugin cannot name. Silence is the
     * answer; falling through to the field-property table would offer `indexed` on a filter.
     */
    fun testAnUnknownFactoryClassOffersNothingRatherThanFieldProperties() {
        val offered = completionsFor(
            """
            <schema name="t">
              <fieldType name="x" class="solr.TextField">
                <analyzer><filter class="com.example.MyFilterFactory" <caret>/></analyzer>
              </fieldType>
            </schema>
            """.trimIndent(),
        )
        assertFalse("a filter is not a field: $offered", "indexed" in offered)
        assertFalse("nor does it have field properties: $offered", "sortMissingLast" in offered)
    }

    fun testACharFiltersAttributesComeFromItsClass() {
        val offered = completionsFor(
            """
            <schema name="t">
              <fieldType name="x" class="solr.TextField">
                <analyzer><charFilter class="solr.PatternReplaceCharFilterFactory" <caret>/></analyzer>
              </fieldType>
            </schema>
            """.trimIndent(),
        )
        assertTrue("expected pattern among $offered", "pattern" in offered)
        assertTrue("expected replacement among $offered", "replacement" in offered)
    }

    /** Nothing names a class yet, so nothing can be said about what it accepts. */
    fun testAnAnalysisComponentWithNoClassOffersNothing() {
        val offered = completionsFor(
            """
            <schema name="t">
              <fieldType name="x" class="solr.TextField">
                <analyzer><filter <caret>/></analyzer>
              </fieldType>
            </schema>
            """.trimIndent(),
        )
        assertFalse("a filter is not a field: $offered", "indexed" in offered)
        assertFalse("nor is its class known: $offered", "minGramSize" in offered)
    }

    /**
     * A class of the wrong kind for the tag it sits in. A tokenizer named in a `<filter>` is an
     * error, and answering with its attributes would confirm the mistake rather than expose it.
     */
    fun testAClassOfTheWrongKindOffersNothing() {
        val offered = completionsFor(
            """
            <schema name="t">
              <fieldType name="x" class="solr.TextField">
                <analyzer><filter class="solr.JapaneseTokenizerFactory" <caret>/></analyzer>
              </fieldType>
            </schema>
            """.trimIndent(),
        )
        assertFalse("a tokenizer is not a filter: $offered", "userDictionary" in offered)
        assertFalse("and not a field either: $offered", "indexed" in offered)
    }

    /** Factory attributes follow the declared line, like the class names do. */
    fun testFactoryAttributesFollowTheDeclaredSolrLine() {
        val offered = completionsInConfigset(
            "attrs",
            "<config><luceneMatchVersion>9.12.0</luceneMatchVersion></config>",
            """
            <schema name="t">
              <fieldType name="x" class="solr.TextField">
                <analyzer><filter class="solr.EdgeNGramFilterFactory" <caret>/></analyzer>
              </fieldType>
            </schema>
            """.trimIndent(),
        )
        assertTrue("expected minGramSize among $offered", "minGramSize" in offered)
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

