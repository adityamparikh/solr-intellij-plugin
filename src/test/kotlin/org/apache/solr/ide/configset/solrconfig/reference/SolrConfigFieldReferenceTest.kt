package org.apache.solr.ide.configset.solrconfig.reference

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.parentOfType
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase
import org.apache.solr.ide.configset.schema.documentation.SolrSchemaDocumentationProvider
import org.apache.solr.ide.configset.navigation.SolrSchemaPsi

/**
 * Navigation from a `solrconfig.xml` handler parameter to the schema field it names.
 *
 * This is the file boundary the plugin exists to close, as a *navigation*: the inspection already
 * reports a parameter naming a field the schema does not declare, and these tests are the gesture
 * in the other direction — landing on the declaration from the place that uses it.
 */
class SolrConfigFieldReferenceTest : SolrConfigsetTestCase() {

    private val schema = """
        <schema name="products">
          <fieldType name="text_general" class="solr.TextField"/>
          <field name="name" type="text_general"/>
          <field name="description" type="text_general"/>
          <dynamicField name="*_t" type="text_general"/>
        </schema>
    """.trimIndent()

    private fun configureConfig(body: String) {
        myFixture.addFileToProject("managed-schema.xml", schema)
        myFixture.configureByText("solrconfig.xml", "<config>$body</config>")
    }

    private fun resolveAtCaret(body: String): PsiElement? {
        configureConfig(body)
        return myFixture.getReferenceAtCaretPosition()?.resolve()
    }

    private fun declaringTagAt(body: String): XmlTag =
        (resolveAtCaret(body) as XmlAttributeValue).parentOfType<XmlTag>()!!

    private fun handler(body: String) =
        """<requestHandler name="/select"><lst name="defaults">$body</lst></requestHandler>"""

    fun testAHandlerParameterResolvesAcrossTheFileBoundary() {
        val declaration = declaringTagAt(handler("""<str name="df">descri<caret>ption</str>"""))
        assertEquals("field", declaration.name)
        assertEquals("description", declaration.getAttributeValue("name"))
    }

    /**
     * A parameter written in one of Solr's other scalar value tags navigates like a `<str>`.
     *
     * The three gestures over these values have to agree about which tags carry them. Completion and
     * the inspections read the parser's set of six; this contributor was registered on `str` alone, so
     * a `qf` written as `<int name="qf">` was inspected and completed and silently not navigable — the
     * one of the three failures a reader would blame on themselves.
     */
    fun testAParameterInANonStringValueTagResolves() {
        val declaration = declaringTagAt(handler("""<int name="qf">descri<caret>ption</int>"""))
        assertEquals("field", declaration.name)
        assertEquals("description", declaration.getAttributeValue("name"))
    }

    /** `qf` holds several names with boost markup; each name is its own reference. */
    fun testEachNameInAWeightedListResolvesOnItsOwn() {
        val declaration = declaringTagAt(handler("""<str name="qf">name^3 descri<caret>ption</str>"""))
        assertEquals("description", declaration.getAttributeValue("name"))
    }

    fun testAnArrayItemResolves() {
        val declaration = declaringTagAt(handler("""<arr name="facet.field"><str>na<caret>me</str></arr>"""))
        assertEquals("field", declaration.name)
        assertEquals("name", declaration.getAttributeValue("name"))
    }

    /**
     * A name only a dynamic pattern supplies navigates to that pattern's declaration — Solr's own
     * resolution, and the same answer the unknown-field inspection consults when it stays silent
     * on exactly this name. Without this, the inspection and the navigation disagree: no warning,
     * yet a dead Cmd+Click.
     */
    fun testANameBackedByADynamicPatternResolvesToItsDeclaration() {
        val declaration = declaringTagAt(handler("""<str name="qf">body<caret>_t</str>"""))
        assertEquals("dynamicField", declaration.name)
        assertEquals("*_t", declaration.getAttributeValue("name"))
    }

    /**
     * A `defaults` list also appears under elements that have nothing to do with queries, and the
     * parser already declines to read those. A reference there would navigate from a value that is
     * not a field name.
     */
    fun testAParameterListOutsideAQueryCarrierOffersNoReference() {
        configureConfig(
            """<updateRequestProcessorChain><lst name="defaults"><str name="qf">na<caret>me</str></lst></updateRequestProcessorChain>""",
        )
        assertNull(myFixture.getReferenceAtCaretPosition())
    }

    /**
     * Soft, like every reference in this package: the unresolved name is the inspection's to
     * report, in Solr's vocabulary, and a second platform-worded warning on the same text would
     * say less and double the noise.
     */
    fun testAnUndeclaredNameResolvesToNothingAndTheReferenceIsSoft() {
        configureConfig(handler("""<str name="qf">no<caret>pe</str>"""))
        val reference = myFixture.getReferenceAtCaretPosition()
        assertNotNull(reference)
        assertNull(reference!!.resolve())
        assertTrue("the reference must be soft", (reference as PsiReferenceBase<*>).isSoft)
    }

    /** The other direction of the same edge: searching the field's references reaches the parameter. */
    fun testFindUsagesOnAFieldIncludesTheParameterThatNamesIt() {
        val schemaFile = myFixture.addFileToProject("managed-schema.xml", schema)
        myFixture.configureByText(
            "solrconfig.xml",
            "<config>${handler("""<str name="qf">name^3 description</str>""")}</config>",
        )
        val declaration = SolrSchemaPsi.findField(schemaFile, "description")!!
        val references = ReferencesSearch.search(declaration).findAll()
        assertTrue(
            "expected a reference from solrconfig.xml, got: ${references.map { it.element.containingFile.name }}",
            references.any { it.element.containingFile.name == "solrconfig.xml" },
        )
    }

    /**
     * Hover on a field name inside a qf parameter resolves to field documentation via reference
     * resolution when SolrSchemaDocumentationProvider declines the position.
     *
     * This is FR-10's regression coverage: the documentation provider's `getCustomDocumentationElement`
     * claims attribute values and schema tags, but a field name in qf is tag *text* and matches
     * neither, so the provider returns null. The platform then resolves the reference at the caret
     * and documents its target instead, which the reference contributor supplies. Without this test,
     * the behaviour is one refactor away from an unnoticed regression — either the documentation
     * provider claiming tag text for a different feature, or the reference contributor changing its
     * resolution order, would break hover on field names in parameters.
     */
    fun testHoverOnFieldNameInQfParameterResolvesDocumentation() {
        myFixture.addFileToProject("managed-schema.xml", schema)
        myFixture.configureByText(
            "solrconfig.xml",
            "<config><luceneMatchVersion>10.0.0</luceneMatchVersion>${handler("""<str name="qf">na<caret>me^3 description</str>""")}</config>",
        )
        val provider = SolrSchemaDocumentationProvider()
        // The documentation provider must decline this position (return null) because it's tag text,
        // not an attribute value or schema element. This lets the platform fall through to reference
        // resolution.
        val customElement = provider.getCustomDocumentationElement(
            myFixture.editor,
            myFixture.file,
            myFixture.file.findElementAt(myFixture.caretOffset),
            myFixture.caretOffset,
        )
        assertNull(
            "SolrSchemaDocumentationProvider must decline field names in parameter text",
            customElement,
        )
        // The platform resolves the reference and documents the target. We verify this by getting
        // the quick documentation at the caret position, which will follow the reference to the field
        // declaration if the reference contributor is working.
        val reference = myFixture.getReferenceAtCaretPosition()
        assertNotNull("expected a reference at the caret", reference)
        val target = reference!!.resolve()
        assertNotNull("expected the reference to resolve to the field declaration", target)
        val targetTag = (target as XmlAttributeValue).parentOfType<XmlTag>()!!
        assertEquals("field", targetTag.name)
        assertEquals("name", targetTag.getAttributeValue("name"))
        // Verify that documentation is available for the resolved target. The platform will call
        // generateDoc on the resolved target, and the documentation provider should answer for
        // the field declaration.
        val doc = provider.generateDoc(target, target)
        assertNotNull("expected documentation for the field declaration", doc)
        assertTrue(
            "expected the field name in the documentation: $doc",
            doc!!.contains("name"),
        )
    }
}
