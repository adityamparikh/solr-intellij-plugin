package org.apache.solr.ide.configset.reference

import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.util.parentOfType
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/** Navigation from each end of a `copyField` to the field it names. */
class SolrCopyFieldReferenceTest : SolrConfigsetTestCase() {

    private val schema = """
        <schema name="products">
          <fieldType name="text_general" class="solr.TextField"/>
          <field name="title" type="text_general"/>
          <field name="text" type="text_general"/>
          <field name="title_t" type="text_general"/>
          <dynamicField name="*_t" type="text_general"/>
          BODY
        </schema>
    """.trimIndent()

    private fun resolveAtCaret(body: String, fileName: String = "managed-schema.xml") =
        myFixture.configureByText(fileName, schema.replace("BODY", body))
            .let { myFixture.getReferenceAtCaretPosition()?.resolve() }

    private fun declaringTagAt(body: String): XmlTag =
        (resolveAtCaret(body) as XmlAttributeValue).parentOfType<XmlTag>()!!

    fun testTheSourceResolvesToTheFieldItNames() {
        val declaration = declaringTagAt("""<copyField source="ti<caret>tle" dest="text"/>""")
        assertEquals("field", declaration.name)
        assertEquals("title", declaration.getAttributeValue("name"))
    }

    fun testTheDestinationResolvesToTheFieldItNames() {
        val declaration = declaringTagAt("""<copyField source="title" dest="te<caret>xt"/>""")
        assertEquals("field", declaration.name)
        assertEquals("text", declaration.getAttributeValue("name"))
    }

    /**
     * The pattern is written literally in both places, so this is an exact reference rather than a
     * guess about what the glob matches.
     */
    fun testAGlobResolvesToTheDynamicFieldSpellingIt() {
        val declaration = declaringTagAt("""<copyField source="title" dest="*_<caret>t"/>""")
        assertEquals("dynamicField", declaration.name)
        assertEquals("*_t", declaration.getAttributeValue("name"))
    }

    /**
     * The line this navigation deliberately does not cross. `title_t` is a concrete field the
     * pattern `*_t` would match, but which fields a pattern matches depends on the documents
     * indexed rather than on the schema, so resolving to one would be an invented target — and to
     * *the first* one, silently, which is worse. The `dynamicField` is the only defensible answer.
     */
    fun testAGlobDoesNotResolveToAConcreteFieldItWouldMatch() {
        val declaration = declaringTagAt("""<copyField source="*_<caret>t" dest="text"/>""")
        assertEquals("dynamicField", declaration.name)
    }

    /**
     * The other direction crosses the line safely: a concrete name that only a dynamic pattern
     * supplies navigates to that pattern's declaration. Unlike a glob's concrete matches, this
     * target is not invented — it is Solr's own resolution, the same one the dangling-copyField
     * inspection consults when it stays silent on exactly this name.
     */
    fun testANameBackedOnlyByAPatternResolvesToTheDynamicFieldDeclaringIt() {
        val declaration = declaringTagAt("""<copyField source="body<caret>_t" dest="text"/>""")
        assertEquals("dynamicField", declaration.name)
        assertEquals("*_t", declaration.getAttributeValue("name"))
    }

    /** Declared beats dynamic, Solr's own precedence: `title_t` is declared outright and wins. */
    fun testADeclaredNameBeatsThePatternThatWouldAlsoMatchIt() {
        val declaration = declaringTagAt("""<copyField source="title<caret>_t" dest="text"/>""")
        assertEquals("field", declaration.name)
        assertEquals("title_t", declaration.getAttributeValue("name"))
    }

    /**
     * `source="*"` copies every field and declares nothing. It resolves to nothing and, because the
     * reference is soft, draws no warning for it — the correct outcome for valid syntax the schema
     * simply has no declaration to point at.
     */
    fun testCopyEverythingResolvesToNothingWithoutAPlatformWarning() {
        myFixture.configureByText(
            "managed-schema.xml",
            schema.replace("BODY", """<copyField source="<caret>*" dest="text"/>"""),
        )
        val reference = myFixture.getReferenceAtCaretPosition()
        assertNotNull(reference)
        assertNull(reference!!.resolve())
        assertTrue("the reference must be soft", (reference as PsiReferenceBase<*>).isSoft)
    }

    fun testAnUndeclaredFieldResolvesToNothing() {
        assertNull(resolveAtCaret("""<copyField source="no<caret>pe" dest="text"/>"""))
    }

    /** The reference covers the name, not the quotes around it. */
    fun testTheReferenceCoversTheNameOnly() {
        myFixture.configureByText(
            "managed-schema.xml",
            schema.replace("BODY", """<copyField source="ti<caret>tle" dest="text"/>"""),
        )
        val reference = myFixture.getReferenceAtCaretPosition()!!
        assertEquals("title", reference.rangeInElement.substring(reference.element.text))
    }

    /**
     * `source` and `dest` are ordinary words. Nothing but the `copyField` tag makes them field
     * names, and an Ant-style copy task in a file the plugin does read would otherwise resolve.
     */
    fun testTheseAttributeNamesAreOnlyReferencesOnACopyField() {
        assertNull(resolveAtCaret("""<copy source="ti<caret>tle" dest="text"/>"""))
        assertNull(resolveAtCaret("""<copy source="title" dest="te<caret>xt"/>"""))
    }

    fun testOtherAttributesOfACopyFieldAreNotReferences() {
        assertNull(resolveAtCaret("""<copyField source="title" dest="text" maxChars="10<caret>0"/>"""))
    }

    fun testNothingResolvesOutsideASolrProject() {
        givenNoSolrOnTheClasspath()
        assertNull(resolveAtCaret("""<copyField source="ti<caret>tle" dest="text"/>"""))
    }

    fun testNothingResolvesInAFileThatIsNotAConfigset() {
        assertNull(resolveAtCaret("""<copyField source="ti<caret>tle" dest="text"/>""", fileName = "notes.xml"))
    }
}
