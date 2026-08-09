package org.apache.solr.ide.configset.reference

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.psi.ElementDescriptionUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.util.parentOfType
import com.intellij.psi.xml.XmlAttributeValue
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

    /**
     * **The fourth kind, and the one that was missing.** It was left out on the reasoning that a
     * path points at a file rather than at a declaration and so would be grouped by the platform's
     * own file rules. There are none for a `FileReference` in an XML attribute value, so Find Usages
     * on `stopwords.txt` listed both correct results under *Unclassified*.
     */
    fun testAnAnalyzerReadingAResourceFileIsGroupedAsSuch() {
        myFixture.addFileToProject("stopwords.txt", "the\n")
        myFixture.configureByText(
            "managed-schema.xml",
            """
            <schema name="products">
              <fieldType name="text" class="solr.TextField">
                <analyzer>
                  <tokenizer class="solr.StandardTokenizerFactory"/>
                  <filter class="solr.StopFilterFactory" words="stop<caret>words.txt"/>
                </analyzer>
              </fieldType>
            </schema>
            """.trimIndent(),
        )
        assertEquals("Analyzer component reading this file", usageTypeAtCaret())
    }

    /**
     * A `<charFilter>`'s `mapping` is the other resource carrier, and groups the same way.
     *
     * Worth its own case because the two tags are separate entries in `RESOURCE_CARRIERS`, and a
     * classification keyed on the tag rather than on the reference would pass the case above and
     * fail this one.
     */
    fun testACharFilterReadingAResourceFileIsGroupedTheSameWay() {
        myFixture.addFileToProject("mapping-ISOLatin1Accent.txt", """"é" => "e"""" + "\n")
        myFixture.configureByText(
            "managed-schema.xml",
            """
            <schema name="products">
              <fieldType name="text" class="solr.TextField">
                <analyzer>
                  <charFilter class="solr.MappingCharFilterFactory" mapping="mapping-ISO<caret>Latin1Accent.txt"/>
                  <tokenizer class="solr.StandardTokenizerFactory"/>
                </analyzer>
              </fieldType>
            </schema>
            """.trimIndent(),
        )
        assertEquals("Analyzer component reading this file", usageTypeAtCaret())
    }

    /**
     * The same attribute outside a configset is grouped by nothing, because it references nothing.
     *
     * This pins the classification to the reference rather than to the text: `words="stopwords.txt"`
     * reads identically here, and the only thing that differs is that no configset owns the file, so
     * no provider contributed a reference to it. What it does *not* reach is the narrower question
     * the production code asks — whether a file reference came from this plugin's own set rather
     * than another plugin's — because arranging a foreign file reference inside an XML attribute
     * takes a second plugin. That check is reasoned about where it is written, not asserted here.
     */
    fun testTheSameAttributeOutsideAConfigsetIsLeftUngrouped() {
        myFixture.addFileToProject("stopwords.txt", "the\n")
        myFixture.configureByText(
            "notes.xml",
            """<notes><filter class="solr.StopFilterFactory" words="stopwords.txt"/></notes>""",
        )
        val value = myFixture.file.findElementAt(myFixture.file.text.indexOf("stopwords.txt"))!!
            .parentOfType<XmlAttributeValue>()!!
        assertNull(SolrUsageTypeProvider().getUsageType(value))
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
