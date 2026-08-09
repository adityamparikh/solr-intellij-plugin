package org.apache.solr.ide.configset.reference

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.psi.ElementDescriptionUtil
import com.intellij.psi.PsiElement
import com.intellij.usageView.UsageViewTypeLocation
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/**
 * How a declaration names itself once the platform puts it on screen.
 *
 * **Written after a sandbox pass, which is the only place these were visible.** Find Usages on
 * `text_general` returned the right four results under a header reading *Solr Declaration Target* —
 * the plugin's own Kotlin class name, de-camel-cased by the platform's fallback and shown to the
 * user. The same description feeds the rename dialog, so Shift+F6 offered to rename a *Solr
 * Declaration Target*. Nothing in the headless suite could see either, because both are produced by
 * the presentation layer the tests bypass.
 */
class SolrDeclarationPresentationTest : SolrConfigsetTestCase() {

    private fun schema(
        fieldType: String = """name="text_general"""",
        field: String = """name="description"""",
        dynamic: String = """name="*_t"""",
    ) = """
        <schema name="products">
          <fieldType $fieldType class="solr.TextField"/>
          <field $field type="text_general"/>
          <dynamicField $dynamic type="text_general"/>
        </schema>
    """.trimIndent()

    private fun targetIn(schemaText: String): PsiElement {
        myFixture.configureByText("managed-schema.xml", schemaText)
        return TargetElementUtil.findTargetElement(
            myFixture.editor,
            TargetElementUtil.getInstance().allAccepted,
        )!!
    }

    private fun typeOf(element: PsiElement) =
        ElementDescriptionUtil.getElementDescription(element, UsageViewTypeLocation.INSTANCE)

    fun testAFieldTypeDeclarationCallsItselfAFieldType() {
        assertEquals("field type", typeOf(targetIn(schema(fieldType = """name="text_gen<caret>eral""""))))
    }

    fun testAFieldDeclarationCallsItselfAField() {
        assertEquals("field", typeOf(targetIn(schema(field = """name="descri<caret>ption""""))))
    }

    /**
     * Told apart from a concrete field, because the distinction is the one a reader most needs when
     * deciding whether a rename is safe — a pattern supplies names it does not spell.
     */
    fun testADynamicFieldDeclarationCallsItselfADynamicField() {
        assertEquals("dynamic field", typeOf(targetIn(schema(dynamic = """name="*<caret>_t""""))))
    }

    private fun usageTypeAtCaret(): String? =
        SolrUsageTypeProvider().getUsageType(myFixture.getReferenceAtCaretPosition()!!.element)?.toString()

    fun testAFieldNamingTheTypeIsGroupedAsSuch() {
        myFixture.configureByText(
            "managed-schema.xml",
            """
            <schema name="products">
              <fieldType name="text_general" class="solr.TextField"/>
              <field name="description" type="text_gen<caret>eral"/>
            </schema>
            """.trimIndent(),
        )
        assertEquals("Field declaring this type", usageTypeAtCaret())
    }

    fun testACopyFieldEndIsGroupedAsACopyRule() {
        myFixture.configureByText(
            "managed-schema.xml",
            """
            <schema name="products">
              <fieldType name="text_general" class="solr.TextField"/>
              <field name="description" type="text_general"/>
              <field name="text" type="text_general"/>
              <copyField source="descri<caret>ption" dest="text"/>
            </schema>
            """.trimIndent(),
        )
        assertEquals("Copy rule", usageTypeAtCaret())
    }

    /**
     * The one worth naming in full, because it is the usage in the file the reader is not looking
     * at — and the one whose blast radius is hardest to judge from the schema alone.
     */
    fun testAHandlerParameterIsGroupedByItsFile() {
        myFixture.addFileToProject("managed-schema.xml", schema())
        myFixture.configureByText(
            "solrconfig.xml",
            """<config><requestHandler name="/select"><lst name="defaults">""" +
                """<str name="qf">name^3 descri<caret>ption</str></lst></requestHandler></config>""",
        )
        assertEquals("Handler parameter in solrconfig.xml", usageTypeAtCaret())
    }

    /** An element holding none of this plugin's references keeps whatever grouping it had. */
    fun testAnUnrelatedElementIsLeftUngrouped() {
        myFixture.configureByText("beans.xml", """<beans><bean id="ab" class="x.Y"/></beans>""")
        val tag = myFixture.file.children.first()
        assertNull(SolrUsageTypeProvider().getUsageType(tag))
    }

    /**
     * Everything outside XML is declined on the type alone.
     *
     * The assertion is the same *null* the case above produces, and the reason it is worth writing
     * separately is that the two reach it differently: this one must not have to ask the element for
     * its references first. Every usage view in the IDE consults this provider for every result of
     * every search, and a Solr plugin has no business computing references over somebody's Java.
     */
    fun testAnElementOutsideXmlIsLeftUngrouped() {
        myFixture.configureByText("notes.txt", "body_t")
        assertNull(SolrUsageTypeProvider().getUsageType(myFixture.file.firstChild))
    }

    /** Nothing is described for a target this plugin did not produce. */
    fun testAnUnrelatedElementIsLeftToThePlatform() {
        myFixture.configureByText("beans.xml", """<beans><bean id="a<caret>b"/></beans>""")
        val element = myFixture.file.findElementAt(myFixture.caretOffset)!!
        assertNull(SolrDeclarationDescriptionProvider().getElementDescription(element, UsageViewTypeLocation.INSTANCE))
    }
}
