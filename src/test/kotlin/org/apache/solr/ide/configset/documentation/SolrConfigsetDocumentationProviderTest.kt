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

    /** An attribute the plugin has nothing to say about still falls back to its element. */
    fun testAnUnknownAttributeFallsBackToItsElement() {
        val doc = docAtCaret(caretInside("indexed"))
        assertNotNull(doc)
        assertTrue("expected the field element's explanation: $doc", doc!!.contains("Declares one field"))
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
}
