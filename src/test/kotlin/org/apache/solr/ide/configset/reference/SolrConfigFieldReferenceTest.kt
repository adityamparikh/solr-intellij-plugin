package org.apache.solr.ide.configset.reference

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.parentOfType
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

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
}
