package org.apache.solr.ide.configset.hint

import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayPayload
import com.intellij.codeInsight.hints.declarative.InlayPosition
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.PresentationTreeBuilder
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/**
 * Which files get hints, and which fields within them.
 *
 * Driven through the collector with a recording sink rather than through the editor's inlay model.
 * The editor route asserts against a rendered presentation whose text is awkward to read back; this
 * asserts the decision the provider actually makes — *which elements produce a hint* — which is the
 * part that can be wrong. What the hint says is covered by `SolrFieldPresentationTest`.
 */
class SolrMatchInlayHintsProviderTest : SolrConfigsetTestCase() {

    private val schema = """
        <schema name="products">
          <fieldType name="string" class="solr.StrField"/>
          <fieldType name="text_general" class="solr.TextField">
            <analyzer type="index">
              <tokenizer class="solr.StandardTokenizerFactory"/>
              <filter class="solr.LowerCaseFilterFactory"/>
            </analyzer>
          </fieldType>
          <fieldType name="custom" class="solr.TextField">
            <analyzer type="index"><tokenizer class="com.example.MysteryTokenizerFactory"/></analyzer>
          </fieldType>
          <field name="sku" type="string"/>
          <field name="name" type="text_general"/>
          <field name="mystery" type="custom"/>
          <field name="orphan" type="undeclared"/>
          <dynamicField name="*_s" type="string"/>
        </schema>
    """.trimIndent()

    /** Records that a presentation was requested, without rendering it. */
    private class RecordingSink : InlayTreeSink {
        val presentations = mutableListOf<InlayPosition>()

        override fun addPresentation(
            position: InlayPosition,
            payloads: List<InlayPayload>?,
            tooltip: String?,
            hintFormat: HintFormat,
            builder: PresentationTreeBuilder.() -> Unit,
        ) {
            presentations += position
        }

        override fun whenOptionEnabled(optionId: String, block: () -> Unit) = block()
    }

    /**
     * The fields that would receive a hint, by name.
     *
     * The configset needs a `solrconfig.xml` beside the schema for the directory to be recognized —
     * `schema.xml` is an ambiguous name and does not identify a configset on its own.
     */
    private fun hintedFields(text: String = schema, fileName: String = "managed-schema.xml"): List<String> {
        myFixture.addFileToProject("core/conf/solrconfig.xml", "<config/>")
        myFixture.configureByText(fileName, text)
        val collector = SolrMatchInlayHintsProvider().createCollector(myFixture.file, myFixture.editor)
            ?: return emptyList()

        val hinted = mutableListOf<String>()
        for (tag in PsiTreeUtil.findChildrenOfType(myFixture.file, XmlTag::class.java)) {
            val sink = RecordingSink()
            (collector as SharedBypassCollector).collectFromElement(tag, sink)
            if (sink.presentations.isNotEmpty()) hinted += tag.getAttributeValue("name").orEmpty()
        }
        return hinted
    }

    fun testEveryClassifiableFieldGetsExactlyOneHint() {
        assertEquals(listOf("sku", "name", "*_s"), hintedFields())
    }

    /** A dynamic field's type decides what it matches exactly as a declared field's does. */
    fun testDynamicFieldsGetHintsToo() {
        assertTrue("*_s" in hintedFields())
    }

    /**
     * The refusals, and the reason this is worth a test: an unrecognised factory means the chain was
     * not understood, and an undeclared type means the schema is wrong in a way an inspection should
     * report. Neither is something to paper over with an approximate hint.
     */
    fun testNoHintWhereTheAnalysisIsNotConfidentOrTheTypeIsUndeclared() {
        val hinted = hintedFields()
        assertFalse("an unrecognised factory must silence the hint", "mystery" in hinted)
        assertFalse("an undeclared type must silence the hint", "orphan" in hinted)
    }

    /** Field *types* are not fields, and must not attract a hint of their own. */
    fun testFieldTypeDeclarationsGetNoHint() {
        assertFalse("string" in hintedFields())
    }

    /** Outside a configset the provider contributes nothing, whatever the file contains. */
    fun testNoCollectorForAFileThatIsNotAConfigset() {
        assertTrue(hintedFields(fileName = "notes.xml").isEmpty())
    }

    fun testNoCollectorOutsideASolrProject() {
        givenNoSolrOnTheClasspath()
        assertTrue(hintedFields().isEmpty())
    }
}
