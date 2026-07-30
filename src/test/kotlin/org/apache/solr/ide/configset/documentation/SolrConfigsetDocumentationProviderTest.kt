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
