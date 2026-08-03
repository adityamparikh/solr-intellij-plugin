package org.apache.solr.ide.configset.documentation

import com.intellij.psi.PsiElement
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/**
 * Which caret positions offer documentation, and what it says.
 *
 * These need a fixture because the provider's job is entirely about PSI: deciding, from an element
 * under a caret, whether there is anything to document. That decision was previously untested, and
 * it is the part most likely to be quietly wrong — a provider that silently declines looks exactly
 * like a provider that is not registered.
 */
class SolrConfigsetDocumentationProviderTest : SolrConfigsetTestCase() {

    private val provider = SolrConfigsetDocumentationProvider()

    private val schema = """
        <schema name="products">
          <fieldType name="string" class="solr.StrField" sortMissingLast="true"/>
          <fieldType name="text_general" class="solr.TextField">
            <analyzer type="index">
              <tokenizer class="solr.StandardTokenizerFactory"/>
              <filter class="solr.LowerCaseFilterFactory"/>
            </analyzer>
          </fieldType>
          <field name="sku" type="string" indexed="true" stored="false"/>
          <field name="name" type="text_general" indexed="true"/>
          <dynamicField name="*_s" type="string"/>
          <copyField source="name" dest="text"/>
        </schema>
    """.trimIndent()

    /**
     * Documents whatever sits at `<caret>`, or returns null when nothing does.
     *
     * `configureByText` places the file at the fixture root, so the `solrconfig.xml` supplying the
     * declared version has to go there too — a configset is a directory, and one in a sibling
     * directory is a different configset. The light project is shared across test methods, so the
     * file is created only once.
     */
    private fun docAtCaret(text: String): String? {
        givenSolrConfigAtFixtureRoot()
        myFixture.configureByText("managed-schema.xml", text)
        val element = provider.getCustomDocumentationElement(
            myFixture.editor,
            myFixture.file,
            myFixture.file.findElementAt(myFixture.caretOffset),
            myFixture.caretOffset,
        ) ?: return null
        return provider.generateDoc(element, element)
    }

    private fun givenSolrConfigAtFixtureRoot() {
        if (myFixture.tempDirFixture.getFile("solrconfig.xml") == null) {
            myFixture.addFileToProject(
                "solrconfig.xml",
                "<config><luceneMatchVersion>10.0.0</luceneMatchVersion></config>",
            )
        }
    }

    /**
     * Puts the caret in the middle of the first occurrence of [word].
     *
     * Built by splitting the word rather than by writing a marker string, because a Kotlin raw
     * string ending in a quote silently loses one — which produced malformed XML that parsed to an
     * empty model, and a test that failed for a reason nothing to do with the code under test.
     */
    private fun caretInside(word: String, occurrence: Int = 1, text: String = schema): String {
        var index = -1
        repeat(occurrence) {
            index = text.indexOf(word, index + 1)
            require(index >= 0) { "fixture has fewer than $occurrence occurrences of $word" }
        }
        val split = index + word.length / 2
        return text.substring(0, split) + "<caret>" + text.substring(split)
    }

    fun testDocumentationIsOfferedOnAFieldsType() {
        // The second "string" is the sku field's type reference; the first is the type's own name.
        val doc = docAtCaret(caretInside("string", occurrence = 2))
        assertNotNull("a field's type should be documented", doc)
        assertTrue(doc!!.contains("solr.StrField"))
        assertTrue("the type's match behaviour belongs in it", doc.contains("whole value"))
    }

    fun testDocumentationIsOfferedOnAFieldsName() {
        val doc = docAtCaret(caretInside("sku"))
        assertNotNull(doc)
        assertTrue(doc!!.contains("sku"))
        assertTrue("the property table is the point of documenting a field", doc.contains("Properties"))
    }

    fun testDocumentationIsOfferedOnAFieldTypeDeclaration() {
        val doc = docAtCaret(caretInside("text_general"))
        assertNotNull(doc)
        assertTrue("both chains belong in a type's documentation", doc!!.contains("Index analyser"))
        assertTrue(doc.contains("StandardTokenizerFactory"))
    }

    fun testDocumentationIsOfferedOnADynamicField() {
        val doc = docAtCaret(caretInside("*_s"))
        assertNotNull("a dynamic field is documented like any other", doc)
    }

    /**
     * Positions inside an element the plugin explains now answer with the *element*, since the
     * caret is within that tag. That is the point of element documentation: a reader should not
     * have to find an attribute value to get an answer.
     */
    fun testAPositionInsideAnElementFallsBackToTheElement() {
        val doc = docAtCaret(caretInside("copyField"))
        assertNotNull(doc)
        assertTrue("expected the copyField element's explanation: $doc", doc!!.contains("index time"))
    }

    /**
     * An attribute the plugin has nothing to say about still falls back to its element.
     *
     * The caret here is on the *name* `class`, not its value. The value half now answers with
     * the named class itself; the name half keeps falling back to the element, which answers
     * the "what is this element" a reader mid-tag is actually asking.
     */
    fun testAnUnknownAttributeFallsBackToItsElement() {
        val doc = docAtCaret(caretInside("class"))
        assertNotNull(doc)
        assertTrue("expected the fieldType element's explanation: $doc", doc!!.contains("Declares a field type"))
    }

    /**
     * Hovering a property answers about the property.
     *
     * This is the gesture a reader actually makes to ask what an attribute means and what Solr
     * would have used instead — and it previously answered with the enclosing element's
     * description, which is a fine answer to a question nobody asked.
     */
    fun testHoveringAPropertyExplainsIt() {
        val doc = docAtCaret(caretInside("indexed"))
        assertNotNull(doc)
        assertTrue("expected the property summary: $doc", doc!!.contains("can be searched"))
        assertTrue("expected what it accepts: $doc", doc.contains("true or false"))
        assertTrue("expected Solr's default: $doc", doc.contains("Solr default"))
    }

    /**
     * A schema whose two field types differ in exactly the way `omitNorms` turns on: `StrField`
     * descends from `PrimitiveFieldType` and `TextField` does not.
     */
    private val typedSchema = """
        <schema name="products" version="1.7">
          <fieldType name="string" class="solr.StrField"/>
          <fieldType name="text_general" class="solr.TextField"/>
          <fieldType name="custom" class="com.example.MyFieldType"/>
          <field name="sku" type="string"/>
          <field name="body" type="text_general"/>
          <field name="odd" type="custom"/>
        </schema>
    """.trimIndent()

    /**
     * **The answer the plugin exists to give.** The Reference Guide says `omitNorms` is true for
     * primitive types and false for text; only this can say which one the field under the caret is.
     */
    fun testOmitNormsResolvesFromThePrimitiveFieldTypesClass() {
        val doc = docAtCaret(caretInside("sku", text = typedSchema))
        assertNotNull(doc)
        assertTrue("expected a resolved omitNorms: $doc", doc!!.contains("omitNorms"))
        assertTrue("expected it attributed to the class: $doc", doc.contains("Solr default for solr.StrField"))
    }

    /** The same property on a text type resolves the other way, from the same table. */
    fun testOmitNormsResolvesFalseForATextFieldType() {
        val doc = docAtCaret(caretInside("body", text = typedSchema))
        assertNotNull(doc)
        assertTrue("expected it attributed to the class: $doc", doc!!.contains("Solr default for solr.TextField"))
    }

    /**
     * A class the catalog does not carry keeps the honest answer. A custom plugin type is exactly
     * the case where claiming a default would be inventing one.
     */
    fun testAnUnknownFieldTypeClassLeavesTheDefaultUndetermined() {
        val doc = docAtCaret(caretInside("odd", text = typedSchema))
        assertNotNull(doc)
        assertTrue("expected the undetermined wording: $doc", doc!!.contains("depends on the field type"))
        assertFalse("nothing may be attributed to a class the catalog lacks: $doc", doc.contains("Solr default for"))
    }

    /** And, on a field, what the value resolves to here and where it came from. */
    fun testAPropertyOnAFieldReportsItsEffectiveValueAndOrigin() {
        val doc = docAtCaret(caretInside("stored"))
        assertNotNull(doc)
        assertTrue("expected the resolved value: $doc", doc!!.contains("Here"))
        assertTrue("expected the origin: $doc", doc.contains("on this field"))
    }

    /**
     * A property on a `fieldType` has no "value for this field" to report, and inventing one would
     * assert something Solr does not. The general half still answers.
     */
    fun testAPropertyOnAFieldTypeHasNoEffectiveValue() {
        val doc = docAtCaret(caretInside("sortMissingLast"))
        assertNotNull(doc)
        assertTrue("expected what it accepts: $doc", doc!!.contains("true or false"))
        assertFalse("a field type has no effective value: $doc", doc.contains("Here"))
    }

    /**
     * Hovering the element gives the resolved configuration, not only a description of what a field
     * is. The property table was previously reachable only with the caret inside the `name` quotes.
     */
    fun testHoveringAFieldElementShowsTheResolvedConfiguration() {
        val doc = docAtCaret(caretInside("dynamicField"))
        assertNotNull(doc)
        assertTrue("expected the element description: $doc", doc!!.contains("Declares a field by"))
        assertTrue("expected the resolved properties: $doc", doc.contains("Properties"))
        assertTrue("expected an origin column: $doc", doc.contains("Solr default"))
    }

    /** An element that declares no field has no properties to resolve. */
    fun testHoveringACopyFieldShowsNoPropertyTable() {
        val doc = docAtCaret(caretInside("copyField"))
        assertNotNull(doc)
        assertFalse("a copy rule has no properties: $doc", doc!!.contains("<b>Properties</b>"))
    }

    /** A type the configset does not declare has no documentation to give. */
    fun testNoDocumentationForAnUndeclaredType() {
        assertNull(docAtCaret(caretInside("nope", text = schema.replaceFirst("type=" + '"' + "text_general" + '"', "type=" + '"' + "nope" + '"'))))
    }

    /** Outside a Solr project the provider must be inert, like every other surface. */
    fun testNoDocumentationOutsideASolrProject() {
        givenNoSolrOnTheClasspath()
        assertNull(docAtCaret(caretInside("sku")))
    }

    /** The link follows the version the configset declares, not the newest release. */
    fun testTheGuideLinkFollowsTheDeclaredVersion() {
        val doc = docAtCaret(caretInside("sku"))
        assertTrue("expected a 10_0 guide link, got: $doc", doc!!.contains("/guide/solr/10_0/"))
    }

    fun testExternalUrlIsOfferedForADocumentedElement() {
        givenSolrConfigAtFixtureRoot()
        myFixture.configureByText(
            "managed-schema.xml",
            caretInside("sku"),
        )
        val element: PsiElement = provider.getCustomDocumentationElement(
            myFixture.editor,
            myFixture.file,
            myFixture.file.findElementAt(myFixture.caretOffset),
            myFixture.caretOffset,
        )!!
        val urls = provider.getUrlFor(element, element)
        assertNotNull(urls)
        assertTrue(urls!!.single().startsWith("https://solr.apache.org/guide/solr/"))
    }

    fun testDocumentationIsOfferedOnAFieldTypesClassValue() {
        val doc = docAtCaret(caretInside("solr.StrField"))
        assertNotNull(doc)
        assertTrue("expected the kind in words: $doc", doc!!.contains("field type class"))
        assertTrue("expected the fully qualified name: $doc", doc.contains("org.apache.solr.schema.StrField"))
        assertTrue("expected the declaring type in the usage line: $doc", doc.contains("<code>string</code>"))
    }

    fun testDocumentationIsOfferedOnATokenizersClassValue() {
        val doc = docAtCaret(caretInside("solr.StandardTokenizerFactory"))
        assertNotNull(doc)
        assertTrue("expected the kind in words: $doc", doc!!.contains("tokenizer factory"))
        assertTrue("expected a catalog attribute: $doc", doc.contains("maxTokenLength"))
    }

    fun testDocumentationIsOfferedOnAFilterClassValue() {
        val doc = docAtCaret(caretInside("solr.LowerCaseFilterFactory"))
        assertNotNull(doc)
        assertTrue("expected the kind in words: $doc", doc!!.contains("token filter factory"))
    }

    /**
     * Hovering the factory *tag* answers with the complete configuration — every attribute at its
     * effective value — not the class-identity popup the `class` value already owns.
     */
    fun testHoveringAFactoryTagShowsItsCompleteConfiguration() {
        val filter = """
            <schema name="products">
              <fieldType name="text_edge" class="solr.TextField">
                <analyzer>
                  <tokenizer class="solr.StandardTokenizerFactory"/>
                  <filter class="solr.EdgeNGramFilterFactory" minGramSize="2" maxGramSize="15"/>
                </analyzer>
              </fieldType>
            </schema>
        """.trimIndent()
        val doc = docAtCaret(caretInside("filter", text = filter))
        assertNotNull("a factory tag should be documented", doc)
        assertTrue("expected the configuration table: $doc", doc!!.contains("Configuration"))
        assertTrue("expected the written minGramSize: $doc", doc.contains("2"))
        assertTrue("expected the written maxGramSize: $doc", doc.contains("15"))
        assertTrue(
            "expected the unwritten preserveOriginal default: $doc",
            doc.contains("preserveOriginal") && doc.contains("false"),
        )
        assertTrue("expected the Solr default origin: $doc", doc.contains("Solr default"))
        assertTrue("expected the on-this-filter origin: $doc", doc.contains("on this filter"))
        // The class-value Accepts table is a different popup; the tag must not be it.
        assertFalse(
            "the tag popup is configuration, not the bare Accepts list: $doc",
            doc.contains("<b>Accepts</b>") && !doc.contains("Configuration"),
        )
    }

    /** A tokenizer tag is the same surface with the tokenizer vocabulary. */
    fun testHoveringATokenizerTagShowsItsCompleteConfiguration() {
        val doc = docAtCaret(caretInside("tokenizer"))
        assertNotNull(doc)
        assertTrue("expected the configuration table: $doc", doc!!.contains("Configuration"))
        assertTrue("expected a catalog attribute: $doc", doc.contains("maxTokenLength"))
        assertTrue("expected the tokenizer origin vocabulary: $doc", doc.contains("on this tokenizer") || doc.contains("Solr default") || doc.contains("no default recorded"))
    }

    /**
     * A class written on the wrong element is documented on the element it is written on.
     *
     * `<filter class="solr.StandardTokenizerFactory"/>` is a configuration Solr refuses to load, and
     * therefore one a reader is plausibly hovering *because* it does not work. The catalog is
     * searched by class name, so the entry that comes back is a tokenizer's; taking the element from
     * that entry would print `<tokenizer>` over a tag that says `filter` and send the reader to edit
     * an element the file does not contain. The tag is the file's and the kind is the catalog's, and
     * showing both is what makes the mismatch visible instead of quietly repaired — the contract
     * `testAMisplacedClassValueIsDocumentedAsWhatItIs` already holds the class value to.
     */
    fun testAMisplacedFactoryClassIsDocumentedOnTheTagTheFileWrote() {
        val misplaced = """
            <schema name="products">
              <fieldType name="text_mixed" class="solr.TextField">
                <analyzer>
                  <filter class="solr.StandardTokenizerFactory" maxTokenLength="64"/>
                </analyzer>
              </fieldType>
            </schema>
        """.trimIndent()
        val doc = docAtCaret(caretInside("filter", text = misplaced))
        assertNotNull("a misplaced factory is still documented", doc)
        assertTrue("expected the element the file wrote: $doc", doc!!.contains("&lt;filter&gt;"))
        assertFalse("must not rewrite the tag to the one the class belongs on: $doc", doc.contains("&lt;tokenizer&gt;"))
        assertTrue("the written value belongs to the filter tag: $doc", doc.contains("on this filter"))
        assertFalse("the origin must not follow the class's kind: $doc", doc.contains("on this tokenizer"))
        assertTrue("the class is still described as what it is: $doc", doc.contains("tokenizer factory"))
    }

    /**
     * A custom plugin class the catalog has never seen must stay silent on the tag — an empty
     * configuration table would claim the class accepts nothing.
     */
    fun testAnUnknownFactoryClassOnATagOffersNothing() {
        val custom = schema.replace(
            "solr.LowerCaseFilterFactory",
            "com.example.CustomFilterFactory",
        )
        val doc = docAtCaret(caretInside("filter", occurrence = 1, text = custom))
        assertNull("an unknown factory class must not get a configuration table: $doc", doc)
    }

    /**
     * The class *value* keeps the identity popup. Putting the configuration table there would mix
     * two questions and steal the tag's reason to exist.
     */
    fun testAClassValueStillShowsTheIdentityPopupNotTheConfigurationTable() {
        val filter = """
            <schema name="products">
              <fieldType name="text_edge" class="solr.TextField">
                <analyzer>
                  <filter class="solr.EdgeNGramFilterFactory" minGramSize="2"/>
                </analyzer>
              </fieldType>
            </schema>
        """.trimIndent()
        val doc = docAtCaret(caretInside("solr.EdgeNGramFilterFactory", text = filter))
        assertNotNull(doc)
        assertTrue("expected the Accepts table on the class value: $doc", doc!!.contains("Accepts"))
        assertFalse(
            "the configuration table belongs on the tag, not the class value: $doc",
            doc.contains("<b>Configuration</b>"),
        )
    }

    /** Outside a Solr project a factory tag is as silent as every other surface. */
    fun testNoFactoryTagDocumentationOutsideASolrProject() {
        givenNoSolrOnTheClasspath()
        assertNull(docAtCaret(caretInside("filter")))
    }

    /** The catalog matches both spellings, so the hover must too. */
    fun testAFullyQualifiedClassValueAnswersLikeItsShortForm() {
        val fqn = schema.replace("solr.StrField", "org.apache.solr.schema.StrField")
        val doc = docAtCaret(caretInside("org.apache.solr.schema.StrField", text = fqn))
        assertNotNull(doc)
        assertTrue("expected the short spelling: $doc", doc!!.contains("solr.StrField"))
    }

    /**
     * A class the catalog does not know gets no popup — the same contract an undeclared type
     * has. Saying nothing beats describing a class the plugin has never seen.
     */
    fun testAnUnknownClassValueOffersNothing() {
        val custom = schema.replace("solr.StrField", "com.example.CustomField")
        val doc = docAtCaret(caretInside("com.example.CustomField", text = custom))
        assertNull("an unknown class must not be described: $doc", doc)
    }

    /**
     * A class in the wrong position still gets documented as what it is. Naming its kind is
     * itself the honest answer; flagging the misplacement is the inspections' job, not the hover's.
     */
    fun testAMisplacedClassValueIsDocumentedAsWhatItIs() {
        val misplaced = schema.replace("class=\"solr.StrField\"", "class=\"solr.StandardTokenizerFactory\"")
        val doc = docAtCaret(caretInside("solr.StandardTokenizerFactory", text = misplaced))
        assertNotNull(doc)
        assertTrue("expected the actual kind: $doc", doc!!.contains("tokenizer factory"))
    }

    fun testExternalUrlForAFilterClassNamesTheFiltersPage() {
        givenSolrConfigAtFixtureRoot()
        myFixture.configureByText("managed-schema.xml", caretInside("solr.LowerCaseFilterFactory"))
        val element: PsiElement = provider.getCustomDocumentationElement(
            myFixture.editor,
            myFixture.file,
            myFixture.file.findElementAt(myFixture.caretOffset),
            myFixture.caretOffset,
        )!!
        val urls = provider.getUrlFor(element, element)
        assertNotNull(urls)
        assertTrue("expected the filters page: $urls", urls!!.single().endsWith("/indexing-guide/filters.html"))
    }

    fun testExternalUrlForAFieldTypeClassNamesTheFieldTypesPage() {
        givenSolrConfigAtFixtureRoot()
        myFixture.configureByText("managed-schema.xml", caretInside("solr.StrField"))
        val element: PsiElement = provider.getCustomDocumentationElement(
            myFixture.editor,
            myFixture.file,
            myFixture.file.findElementAt(myFixture.caretOffset),
            myFixture.caretOffset,
        )!!
        val urls = provider.getUrlFor(element, element)
        assertNotNull(urls)
        assertTrue(
            "expected the field types page: $urls",
            urls!!.single().endsWith("/indexing-guide/field-types-included-with-solr.html"),
        )
    }
}
