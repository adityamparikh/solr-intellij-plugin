package org.apache.solr.ide.configset.documentation

import com.intellij.psi.PsiElement
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase
import org.junit.Assert.assertNotEquals

/**
 * Which caret positions answer, now that the attribute's own name does.
 *
 * **The absences carry the weight here.** What could go wrong is a popup appearing where none
 * should, or an existing popup losing what the catalog proved — both worse than the silence this
 * replaces, and neither visible to a test that only checks the new answers arrive.
 */
class SolrAttributeDocumentationTest : SolrConfigsetTestCase() {

    private fun schema(body: String) = """
        <schema name="products" version="1.6">
          <fieldType name="text_general" class="solr.TextField">
            <analyzer>
              <tokenizer class="solr.StandardTokenizerFactory"/>
              <filter class="solr.EdgeNGramFilterFactory" minGramSize="2" maxGramSize="15"/>
            </analyzer>
          </fieldType>
          <field name="description" type="text_general" indexed="true"/>
          <field name="text" type="text_general"/>
          $body
        </schema>
    """.trimIndent()

    /** The popup for whatever the caret is on, through the same path the platform takes. */
    private fun documentationAtCaret(text: String): String? {
        myFixture.configureByText("managed-schema.xml", text)
        val provider = SolrConfigsetDocumentationProvider()
        val target: PsiElement = provider.getCustomDocumentationElement(
            myFixture.editor,
            myFixture.file,
            myFixture.file.findElementAt(myFixture.caretOffset),
            myFixture.caretOffset,
        ) ?: return null
        return provider.generateDoc(target, null)
    }

    fun testAFieldsNameAttributeExplainsItself() {
        val doc = documentationAtCaret(schema("""<field na<caret>me="sku" type="text_general"/>"""))!!
        assertTrue(doc, "document" in doc.lowercase())
    }

    fun testAFieldTypesClassAttributeExplainsItself() {
        val doc = documentationAtCaret(
            schema("").replace("""<fieldType name="text_general" cl""", """<fieldType name="text_general" c<caret>l"""),
        )!!
        assertTrue(doc, "class" in doc.lowercase())
    }

    /** A `copyField`'s ends are told apart, which is why the table is keyed by element and name. */
    fun testACopyFieldsEndsAreDescribedDifferently() {
        val source = documentationAtCaret(schema("""<copyField sou<caret>rce="description" dest="text"/>"""))!!
        val destination = documentationAtCaret(schema("""<copyField source="description" de<caret>st="text"/>"""))!!
        assertNotEquals(source, destination)
    }

    /**
     * The schema version answers twice: what the attribute decides anywhere, and what this value
     * decides here. The second half is the part no external documentation can supply.
     */
    fun testTheSchemaVersionReportsWhatThisValueDecides() {
        val doc = documentationAtCaret(
            schema("").replace("""<schema name="products" ver""", """<schema name="products" v<caret>er"""),
        )!!
        assertTrue(doc, "1.6" in doc)
        assertTrue(doc, "docValues" in doc)
    }

    /**
     * **The factory popup is left exactly as the catalog built it.**
     *
     * An earlier revision of this step added a *Does* row here, written by hand from the Reference
     * Guide. It was withdrawn: quick documentation links to the guide rather than embedding it, and
     * this popup already carries that link. The assertion that matters is therefore that nothing was
     * added and nothing was lost — the rows below are what bytecode proved.
     */
    fun testAFactoryAttributeKeepsExactlyItsCatalogPopup() {
        val doc = documentationAtCaret(
            schema("").replace("""minGramSize="2"""", """minG<caret>ramSize="2""""),
        )!!
        assertTrue("the owner must be named: $doc", "EdgeNGram" in doc)
        assertTrue("the value type must be stated: $doc", "Accepts" in doc)
        assertFalse("no hand-written meaning belongs here: $doc", "Does" in doc)
    }

    /** A property attribute still answers with the property popup, not the structural one. */
    fun testAPropertyAttributeIsUnaffected() {
        val doc = documentationAtCaret(schema("""<field name="sku" type="text_general" ind<caret>exed="true"/>"""))!!
        assertTrue("the property popup states Solr's default: $doc", "Solr default" in doc)
    }

    /** Nothing outside a configset answers, whatever it is called. */
    fun testAnAttributeOutsideAConfigsetAnswersNothing() {
        myFixture.configureByText("beans.xml", """<schema na<caret>me="products" version="1.6"/>""")
        val provider = SolrConfigsetDocumentationProvider()
        assertNull(
            provider.getCustomDocumentationElement(
                myFixture.editor,
                myFixture.file,
                myFixture.file.findElementAt(myFixture.caretOffset),
                myFixture.caretOffset,
            ),
        )
    }

    /**
     * **An attribute the table does not describe falls through, and must not be described anyway.**
     *
     * `name` on a `<copyField>` is not a field name, which is the whole reason the table is keyed by
     * element and attribute together. The caret still answers — it reaches the element popup, as it
     * did before this step — but it must not gain the `<field>` description, which would state a
     * falsehood about the file.
     */
    fun testAnUndescribedAttributeFallsThroughToItsElement() {
        val doc = documentationAtCaret(
            schema("""<copyField name="no<caret>pe" source="description" dest="text"/>"""),
        )!!
        assertTrue("expected the copyField element popup: $doc", "copyField" in doc)
        assertFalse(
            "a copyField's name must never be described as a field name: $doc",
            "name documents use" in doc,
        )
    }
}
