package org.apache.solr.ide.configset.editing

import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase
import org.apache.solr.ide.configset.inspection.SolrDanglingCopyFieldInspection
import org.apache.solr.ide.configset.inspection.SolrUnknownAttributeInspection
import org.apache.solr.ide.configset.inspection.SolrUnknownFieldTypeInspection

/**
 * The fixes offered alongside the two reference inspections.
 *
 * An inspection that says a name is wrong while withholding the list of right ones is doing half a
 * job — and it computed that list in order to decide. These assert the list is offered, ordered so
 * the likely typo comes first, and that applying one leaves a configset that still parses.
 */
class SolrReferenceQuickFixTest : SolrConfigsetTestCase() {

    private val schema = """
        <schema name="products">
          <fieldType name="string" class="solr.StrField"/>
          <fieldType name="text_general" class="solr.TextField"/>
          <field name="id" type="string"/>
          <field name="name" type="string"/>
          <field name="description" type="text_general"/>
          <field name="text" type="text_general"/>
          <dynamicField name="*_s" type="string"/>
          BODY
        </schema>
    """.trimIndent()

    private fun fixesFor(body: String, vararg inspections: com.intellij.codeInspection.LocalInspectionTool): List<String> {
        myFixture.enableInspections(*inspections)
        myFixture.configureByText("managed-schema.xml", schema.replace("BODY", body))
        return myFixture.getAllQuickFixes().map { it.text }
    }

    // --- unknown field type ---------------------------------------------------------------------

    fun testAnUnknownTypeOffersTheDeclaredTypes() {
        val fixes = fixesFor("""<field name="sku" type="strin<caret>g2"/>""", SolrUnknownFieldTypeInspection())
        assertTrue("expected the declared types: $fixes", fixes.any { it.contains("string") })
        assertTrue("expected the declared types: $fixes", fixes.any { it.contains("text_general") })
    }

    /** Ranked by edit distance, because the overwhelmingly common cause is a typo. */
    fun testTheClosestSpellingIsOfferedFirst() {
        val fixes = fixesFor("""<field name="sku" type="text_genera<caret>"/>""", SolrUnknownFieldTypeInspection())
        assertTrue("expected text_general first, got: $fixes", fixes.first().contains("text_general"))
    }

    fun testApplyingTheFixProducesAValidSchema() {
        myFixture.enableInspections(SolrUnknownFieldTypeInspection())
        myFixture.configureByText("managed-schema.xml", schema.replace("BODY", """<field name="sku" type="strin<caret>g2"/>"""))
        val fix = myFixture.getAllQuickFixes().first { it.text.contains("string") }
        myFixture.launchAction(fix)
        assertTrue(myFixture.file.text.contains("""type="string""""))
        assertFalse(myFixture.file.text.contains("string2"))
    }

    // --- dangling copyField ---------------------------------------------------------------------

    fun testADanglingCopyFieldOffersTheDeclaredFields() {
        val fixes = fixesFor("""<copyField source="descriptoi<caret>n" dest="text"/>""", SolrDanglingCopyFieldInspection())
        assertTrue("expected description first, got: $fixes", fixes.first().contains("description"))
        assertTrue("expected fields on the list: $fixes", fixes.any { it.contains("name") })
    }

    /**
     * A dynamic pattern is a legal `copyField` end, so it belongs among the candidates.
     *
     * The destination here deliberately does *not* match `*_s` — a name that matched would resolve
     * through the pattern and never be reported in the first place.
     */
    fun testDynamicPatternsAreOfferedToo() {
        val fixes = fixesFor("""<copyField source="name" dest="nosuc<caret>h"/>""", SolrDanglingCopyFieldInspection())
        assertTrue("expected the dynamic pattern among the candidates: $fixes", fixes.any { it.contains("*_s") })
    }

    // --- unknown attribute name -----------------------------------------------------------------

    /**
     * The fix for a misspelled attribute *name*, which is reported on a different element than every
     * other fix here — the name token rather than the value.
     *
     * This asserts the fix is applied, not merely offered. Offering was never the broken half: the
     * menu listed the right spellings and choosing one silently did nothing, because the fix only
     * knew how to write a value. A test that stopped at `getAllQuickFixes` passed throughout.
     */
    fun testApplyingTheFixRenamesAMisspelledAttribute() {
        myFixture.enableInspections(SolrUnknownAttributeInspection())
        myFixture.configureByText(
            "managed-schema.xml",
            schema.replace("BODY", """<field name="sku" type="string" indexd<caret>="true"/>"""),
        )
        val fix = myFixture.getAllQuickFixes().first { it.text.contains("indexed") }
        myFixture.launchAction(fix)
        assertTrue("expected the attribute renamed, got: ${myFixture.file.text}", myFixture.file.text.contains("""indexed="true""""))
        assertFalse(myFixture.file.text.contains("indexd"))
    }

    /**
     * A schema with many fields must not answer a typo with a directory. Past a handful the list
     * stops being a suggestion.
     */
    fun testTheNumberOfSuggestionsIsCapped() {
        val many = (1..40).joinToString("\n") { """<field name="f$it" type="string"/>""" }
        val fixes = fixesFor("$many\n<copyField source=\"zzz<caret>zz\" dest=\"text\"/>", SolrDanglingCopyFieldInspection())
        assertTrue("expected a capped list, got ${fixes.size}", fixes.size <= 6)
    }
}
