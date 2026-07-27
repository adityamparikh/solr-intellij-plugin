package org.apache.solr.ide.configset.reference

import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.util.parentOfType
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/** Navigation from a field's `type` to the `fieldType` that declares it. */
class SolrFieldTypeReferenceTest : SolrConfigsetTestCase() {

    private val schema = """
        <schema name="products">
          <fieldType name="string" class="solr.StrField"/>
          <fieldType name="text_general" class="solr.TextField"/>
          <fieldtype name="legacy" class="solr.StrField"/>
          BODY
        </schema>
    """.trimIndent()

    private fun resolveAtCaret(body: String, fileName: String = "managed-schema.xml") =
        myFixture.configureByText(fileName, schema.replace("BODY", body))
            .let { myFixture.getReferenceAtCaretPosition()?.resolve() }

    fun testAFieldsTypeResolvesToItsDeclaration() {
        val target = resolveAtCaret("""<field name="name" type="text_g<caret>eneral"/>""")
        assertNotNull("expected the type declaration", target)
        val declaringTag = (target as XmlAttributeValue).parentOfType<XmlTag>()!!
        assertEquals("fieldType", declaringTag.name)
        assertEquals("text_general", declaringTag.getAttributeValue("name"))
    }

    fun testADynamicFieldsTypeResolvesToo() {
        assertNotNull(resolveAtCaret("""<dynamicField name="*_s" type="str<caret>ing"/>"""))
    }

    /** Older schemas spell it `fieldtype`; both spellings declare a type. */
    fun testTheLegacyFieldtypeSpellingResolves() {
        assertNotNull(resolveAtCaret("""<field name="old" type="leg<caret>acy"/>"""))
    }

    /**
     * An undeclared type resolves to nothing, and the reference is soft so the platform adds no
     * warning of its own — that name is already reported by the unknown-field-type inspection, in
     * Solr's vocabulary rather than the platform's.
     */
    fun testAnUndeclaredTypeResolvesToNothingWithoutAPlatformWarning() {
        val reference = myFixture.configureByText(
            "managed-schema.xml",
            schema.replace("BODY", """<field name="x" type="no<caret>pe"/>"""),
        ).let { myFixture.getReferenceAtCaretPosition() }
        assertNotNull(reference)
        assertNull(reference!!.resolve())
        assertTrue("the reference must be soft", reference.let { it as com.intellij.psi.PsiReferenceBase<*> }.isSoft)
    }

    /** The reference covers the name, not the quotes around it. */
    fun testTheReferenceCoversTheNameOnly() {
        myFixture.configureByText("managed-schema.xml", schema.replace("BODY", """<field name="n" type="str<caret>ing"/>"""))
        val reference = myFixture.getReferenceAtCaretPosition()!!
        assertEquals("string", reference.rangeInElement.substring(reference.element.text))
    }

    fun testOtherAttributesAreNotReferences() {
        assertNull(resolveAtCaret("""<field name="na<caret>me" type="string"/>"""))
        assertNull(resolveAtCaret("""<field name="n" type="string" indexed="tr<caret>ue"/>"""))
    }

    fun testNothingResolvesOutsideASolrProject() {
        givenNoSolrOnTheClasspath()
        assertNull(resolveAtCaret("""<field name="name" type="text_g<caret>eneral"/>"""))
    }

    fun testNothingResolvesInAFileThatIsNotAConfigset() {
        assertNull(resolveAtCaret("""<field name="name" type="text_g<caret>eneral"/>""", fileName = "notes.xml"))
    }
}
