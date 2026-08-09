package org.apache.solr.ide.configset.schema.hint

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
 * asserts the decision the provider actually makes — *which elements produce a hint* — as well as the
 * exact text it renders. `SolrFieldPresentationTest` covers the documentation popup, the other surface
 * built from the same model.
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
          <fieldType name="unknown_class" class="com.example.MysteryFieldType"/>
          <field name="sku" type="string"/>
          <field name="name" type="text_general"/>
          <field name="mystery" type="custom"/>
          <field name="opaque" type="unknown_class"/>
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

    /** The hint text for one field, reassembled from its segments, or null if it got no hint. */
    private fun hintFor(name: String, text: String = schema): String? {
        myFixture.addFileToProject("core/conf/solrconfig.xml", "<config/>")
        myFixture.configureByText("managed-schema.xml", text)
        val collector = SolrMatchInlayHintsProvider().createCollector(myFixture.file, myFixture.editor)
            ?: return null
        for (tag in PsiTreeUtil.findChildrenOfType(myFixture.file, XmlTag::class.java)) {
            if (tag.getAttributeValue("name") != name) continue
            val sink = RecordingSink()
            (collector as SharedBypassCollector).collectFromElement(tag, sink)
            if (sink.presentations.isEmpty()) return null
            return sink.trees.single().joinToString("")
        }
        return null
    }

    fun testEveryFieldWithADeclaredTypeGetsExactlyOneHint() {
        assertEquals(listOf("sku", "name", "mystery", "opaque", "*_s"), hintedFields())
    }

    /** A dynamic field's type decides what it matches exactly as a declared field's does. */
    fun testDynamicFieldsGetHintsToo() {
        assertTrue("*_s" in hintedFields())
    }

    /**
     * The refusal that survives, at the level of which fields get a hint at all. The unconfident case
     * has moved to testAnUnrecognisedFactorySilencesOnlyTheMatchHalf.
     */
    fun testNoHintWhereTheTypeIsUndeclared() {
        assertFalse("an undeclared type must silence the hint", "orphan" in hintedFields())
    }

    /**
     * Match first, then storage shape, and the four storage phrases in Reference Guide order
     * (`indexed`, `stored`, `docValues`, `multiValued`) — the match half is the output nothing else
     * produces.
     *
     * `no doc values` because this fixture's `<schema>` declares no `version`, so
     * [SolrSchemaVersion.ASSUMED] — 1.0 — applies, and the `docValues` default turns on 1.7. That is
     * Solr's own reading of a versionless schema, and the fixture is left that way deliberately.
     *
     * `multi-valued`, for the same reason: `multiValued`'s own default is version-conditional too —
     * true below schema version 1.1 — so the same versionless schema that turns doc values off turns
     * this on. See `SolrFieldPropertiesTest` for the same default asserted directly.
     */
    fun testHintStatesMatchCapabilityThenStorageShape() {
        assertEquals(
            "whole value, case-sensitive, indexed, stored, no doc values, multi-valued",
            hintFor("sku"),
        )
    }

    /**
     * The behaviour change. An unrecognised factory means the analyser chain was not understood; it
     * says nothing about stored or multiValued, which are read from attributes and defaults. Withholding
     * them was withholding a fact the plugin is certain of.
     */
    fun testAnUnrecognisedFactorySilencesOnlyTheMatchHalf() {
        assertEquals("indexed, stored, no doc values, multi-valued", hintFor("mystery"))
    }

    /**
     * The refusal that survives. Property resolution is three-tier — field, then field type, then
     * Solr's default — and an undeclared type removes the middle tier without removing the
     * fall-through, so `stored` would resolve to true and be attributed to a Solr default that the
     * missing type might have overridden. A missing type is an inspection's finding.
     */
    fun testAnUndeclaredTypeStillSilencesTheHintEntirely() {
        assertNull(hintFor("orphan"))
    }

    /**
     * Per-property silence. The catalog does not carry this class, so docValues has no answer — but
     * indexed, stored and multiValued never depended on the class at all.
     */
    fun testAPropertyWithNoAnswerContributesNoPhrase() {
        val hint = hintFor("opaque")
        assertNotNull(hint)
        assertTrue("indexed, stored, multi-valued" in hint!!)
        assertFalse("no doc values claim either way", "doc values" in hint)
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
     *
     * `no doc values, multi-valued` for the same version-default reason as
     * `testHintStatesMatchCapabilityThenStorageShape`: this fixture's `<schema>` declares no
     * `version` either, so [SolrSchemaVersion.ASSUMED] governs both properties' defaults.
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
        assertEquals(
            "tokenised, case-insensitive, prefix-capable, indexed, stored, no doc values, multi-valued",
            segments.joinToString(""),
        )
        for (segment in segments) {
            assertTrue(
                "'$segment' (${segment.length} chars) exceeds the renderer's 30-character segment budget",
                segment.length <= 30,
            )
        }
    }
}
