package org.apache.solr.ide.configset.hint

import com.intellij.codeInsight.hints.declarative.CollapseState
import com.intellij.codeInsight.hints.declarative.CollapsiblePresentationTreeBuilder
import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayActionData
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

    /** Records each requested presentation and its text segments, without rendering them. */
    private class RecordingSink : InlayTreeSink {
        val presentations = mutableListOf<InlayPosition>()
        val trees = mutableListOf<List<String>>()

        override fun addPresentation(
            position: InlayPosition,
            payloads: List<InlayPayload>?,
            tooltip: String?,
            hintFormat: HintFormat,
            builder: PresentationTreeBuilder.() -> Unit,
        ) {
            presentations += position
            trees += RecordingTreeBuilder().apply(builder).segments
        }

        override fun whenOptionEnabled(optionId: String, block: () -> Unit) = block()
    }

    /** Collects `text()` segments in order; the tree structure itself is not under test. */
    private class RecordingTreeBuilder : PresentationTreeBuilder {
        val segments = mutableListOf<String>()

        override fun text(text: String, actionData: InlayActionData?) {
            segments += text
        }

        override fun list(builder: PresentationTreeBuilder.() -> Unit) = builder()

        override fun collapsibleList(
            state: CollapseState,
            expandedState: CollapsiblePresentationTreeBuilder.() -> Unit,
            collapsedState: CollapsiblePresentationTreeBuilder.() -> Unit,
        ) = Unit

        override fun clickHandlerScope(actionData: InlayActionData, builder: PresentationTreeBuilder.() -> Unit) =
            builder()
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

    /**
     * The declarative renderer truncates any single `text()` segment past its inline budget —
     * `PresentationTreeBuilderImpl.MAX_SEGMENT_TEXT_LENGTH`, 30 characters — so the longest
     * summary must arrive as several short segments that reconstruct it exactly. One segment
     * carrying the whole summary renders on screen as "tokenised, case-insensitive,…".
     */
    fun testEachHintSegmentFitsTheRenderersInlineBudget() {
        myFixture.addFileToProject("core/conf/solrconfig.xml", "<config/>")
        myFixture.configureByText(
            "managed-schema.xml",
            """
            <schema name="products">
              <fieldType name="text_prefix" class="solr.TextField">
                <analyzer type="index">
                  <tokenizer class="solr.StandardTokenizerFactory"/>
                  <filter class="solr.LowerCaseFilterFactory"/>
                  <filter class="solr.EdgeNGramFilterFactory" minGramSize="2" maxGramSize="15"/>
                </analyzer>
              </fieldType>
              <field name="name_prefix" type="text_prefix"/>
            </schema>
            """.trimIndent(),
        )
        val collector = SolrMatchInlayHintsProvider().createCollector(myFixture.file, myFixture.editor)!!
        val sink = RecordingSink()
        for (tag in PsiTreeUtil.findChildrenOfType(myFixture.file, XmlTag::class.java)) {
            (collector as SharedBypassCollector).collectFromElement(tag, sink)
        }

        val segments = sink.trees.single()
        assertEquals("tokenised, case-insensitive, prefix-capable", segments.joinToString(""))
        for (segment in segments) {
            assertTrue(
                "'$segment' (${segment.length} chars) exceeds the renderer's 30-character segment budget",
                segment.length <= 30,
            )
        }
    }
}
