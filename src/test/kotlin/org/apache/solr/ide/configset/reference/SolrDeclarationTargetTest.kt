package org.apache.solr.ide.configset.reference

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.search.searches.ReferencesSearch
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/**
 * Where Find Usages currently stops, pinned so that closing the gap has to say so.
 *
 * **These assert an absence, and that is deliberate.** Find Usages works from a reference and is
 * refused from the declaration — the manual suite's NAV-3 and NAV-4 record the refusal, and
 * `docs/design/pending/2026-08-04-declaration-targets/design.md` explains it. Declarations as
 * targets is the step that closes it, and when it lands the three `yieldsNoTarget` assertions below
 * invert. That inversion is the proof the step did what it claimed, which is why the boundary is
 * written down rather than left as a thing everyone rediscovers.
 *
 * [TargetElementUtil.findTargetElement] is the call the Find Usages action itself makes, so a null
 * here *is* the *Cannot search for usages from this location* the user sees. Asserting against it
 * rather than against the action keeps the test at the level the behaviour is decided.
 *
 * The search half is asserted alongside, because the two together are the whole diagnosis: the
 * reference graph is complete and traversable in reverse today, and only the step that turns a caret
 * into a target is missing.
 */
class SolrDeclarationTargetTest : SolrConfigsetTestCase() {

    /**
     * One schema, with the caret placed by substituting whichever attribute the test is about.
     * Each test therefore states its own caret position literally, which is the part that carries
     * the meaning.
     */
    private fun schema(
        fieldType: String = """name="text_general"""",
        nameFieldType: String = """type="text_general"""",
        description: String = """name="description"""",
        dynamic: String = """name="*_t"""",
    ) = """
        <schema name="products">
          <fieldType $fieldType class="solr.TextField"/>
          <field name="name" $nameFieldType/>
          <field $description type="text_general"/>
          <dynamicField $dynamic type="text_general"/>
          <copyField source="name" dest="description"/>
        </schema>
    """.trimIndent()

    private fun handler(body: String) =
        """<config><requestHandler name="/select"><lst name="defaults">$body</lst></requestHandler></config>"""

    private fun targetIn(schemaText: String): PsiElement? {
        myFixture.configureByText("managed-schema.xml", schemaText)
        return TargetElementUtil.findTargetElement(
            myFixture.editor,
            TargetElementUtil.getInstance().allAccepted,
        )
    }

    private fun describe(element: PsiElement?) =
        if (element == null) "null" else "${element.javaClass.simpleName}(${element.text})"

    fun testAFieldTypeDeclarationYieldsNoTarget() {
        val target = targetIn(schema(fieldType = """name="text_gen<caret>eral""""))
        assertNull("expected no target on the declaration, got ${describe(target)}", target)
    }

    fun testAFieldDeclarationYieldsNoTarget() {
        val target = targetIn(schema(description = """name="descri<caret>ption""""))
        assertNull("expected no target on the declaration, got ${describe(target)}", target)
    }

    fun testADynamicFieldDeclarationYieldsNoTarget() {
        val target = targetIn(schema(dynamic = """name="*<caret>_t""""))
        assertNull("expected no target on the declaration, got ${describe(target)}", target)
    }

    /** The contrast that makes the absence above a target problem rather than a graph problem. */
    fun testAFieldTypeReferenceYieldsATarget() {
        assertNotNull(
            "a reference must resolve to a target",
            targetIn(schema(nameFieldType = """type="text_gen<caret>eral"""")),
        )
    }

    /** The same contrast across the file boundary. */
    fun testAHandlerParameterReferenceYieldsATarget() {
        myFixture.addFileToProject("managed-schema.xml", schema())
        myFixture.configureByText("solrconfig.xml", handler("""<str name="qf">name^3 descri<caret>ption</str>"""))
        assertNotNull(
            "a cross-file reference must resolve to a target",
            TargetElementUtil.findTargetElement(myFixture.editor, TargetElementUtil.getInstance().allAccepted),
        )
    }

    /**
     * The reverse traversal already works, which is why this step is about targets and not about
     * search. Overlaps `SolrConfigFieldReferenceTest` on purpose: there it is the reference's own
     * behaviour, here it is half of the diagnosis.
     */
    fun testSearchingADeclarationReachesTheCrossFileReference() {
        val schemaFile = myFixture.addFileToProject("managed-schema.xml", schema())
        myFixture.configureByText("solrconfig.xml", handler("""<str name="qf">name^3 description</str>"""))
        val hits = ReferencesSearch.search(SolrSchemaPsi.findField(schemaFile, "description")!!).findAll()
        assertTrue(
            "expected the qf parameter among the usages, got ${hits.map { it.element.containingFile.name }}",
            hits.any { it.element.containingFile.name == "solrconfig.xml" },
        )
    }

    /**
     * **The one case the default search cannot reach, and the reason the step carries a
     * `referencesSearch` executor.** `ReferencesSearch` picks candidates out of the word index
     * before asking any reference to confirm itself, and `body_t` shares no word with `*_t`, so the
     * reference that genuinely resolves to this pattern is never offered for confirmation. Left
     * alone, Find Usages on a dynamic field reports an empty list.
     */
    fun testSearchingADynamicFieldMissesTheNameItsPatternSupplies() {
        val schemaFile = myFixture.addFileToProject("managed-schema.xml", schema())
        myFixture.configureByText("solrconfig.xml", handler("""<str name="qf">body_t</str>"""))
        val hits = ReferencesSearch.search(SolrSchemaPsi.findField(schemaFile, "*_t")!!).findAll()
        assertTrue(
            "the default search unexpectedly reached ${hits.map { it.element.text }}",
            hits.none { it.element.containingFile.name == "solrconfig.xml" },
        )
    }
}
