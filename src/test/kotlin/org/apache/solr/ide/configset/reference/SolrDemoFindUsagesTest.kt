package org.apache.solr.ide.configset.reference

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.psi.ElementDescriptionUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.parentOfType
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag
import com.intellij.usageView.UsageViewTypeLocation
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase
import java.io.File

/**
 * Performs the demo's Find Usages gestures against the committed demo configset.
 *
 * **The rest of the Find Usages suite uses fixtures chosen to exercise one rule; this one uses the
 * files the demo is actually driven on**, for the reason
 * [org.apache.solr.ide.configset.parsing.DemoConfigsetTest] gives about the parser: a feature that
 * works on every synthetic case and not on the fixture it will be demonstrated with is a feature
 * that fails in front of an audience. It also pins the demo's shape, so removing the dynamic field
 * or the `pf` that references it fails a build rather than being discovered on stage.
 *
 * **What this is not.** It cannot replace
 * [the manual suite's](../../../../../../../../docs/manual-test-suite.md) NAV-3 and NAV-6, which are
 * sandbox gestures with a human reading the Find Usages tool window. This asserts that the platform
 * returns the right usages; only a person can see that the window presents them intelligibly. The
 * manual pass stays required.
 */
class SolrDemoFindUsagesTest : SolrConfigsetTestCase() {

    private val demoDirectory = File(System.getProperty("user.dir"), "demo/solr/conf")

    /** The committed demo configset, copied into the fixture project at its own path. */
    private fun demoSchema(): PsiFile {
        assertTrue(
            "demo configset not found at $demoDirectory — this test reads the committed fixture",
            demoDirectory.isDirectory,
        )
        val schema = myFixture.addFileToProject(
            "demo/solr/conf/managed-schema.xml",
            File(demoDirectory, "managed-schema.xml").readText(),
        )
        myFixture.addFileToProject(
            "demo/solr/conf/solrconfig.xml",
            File(demoDirectory, "solrconfig.xml").readText(),
        )
        return schema
    }

    /**
     * The target the Find Usages action would find with the caret inside [name], declared by the
     * tag [declarationPrefix] opens — the gesture the demo performs, at the position it performs it.
     *
     * Anchored on the name as well as the tag, because anchoring on the tag alone finds whichever
     * declaration comes first in the file and silently asks about that one instead.
     */
    private fun targetOnDeclaredName(file: PsiFile, declarationPrefix: String, name: String): PsiElement? {
        val start = file.text.indexOf(declarationPrefix + name)
        assertTrue("the demo schema no longer declares `$declarationPrefix$name`", start >= 0)
        myFixture.openFileInEditor(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(start + declarationPrefix.length)
        return TargetElementUtil.findTargetElement(
            myFixture.editor,
            TargetElementUtil.getInstance().allAccepted,
        )
    }

    /**
     * Demo step 27, first half: the caret on the `text_general` declaration, and every field using
     * it appears.
     */
    fun testFindUsagesOnTheFieldTypeDeclarationListsEveryFieldUsingIt() {
        val schema = demoSchema()
        val target = targetOnDeclaredName(schema, """<fieldType name="""", "text_general")
        assertNotNull("no target on the text_general declaration", target)

        val users = ReferencesSearch.search(target!!).findAll()
            .mapNotNull { (it.element as? XmlAttributeValue)?.parentOfType<XmlTag>()?.getAttributeValue("name") }
        assertEquals(setOf("name", "description", "text", "*_t"), users.toSet())
    }

    /**
     * **What the Find Usages window actually reads, on the declaration the demo uses.**
     *
     * A sandbox pass found the header saying *Solr Declaration Target* and the results grouped under
     * *Unclassified* — both correct results, presented as though something were broken. These assert
     * the strings rather than the structure, because the strings are what went wrong and what a test
     * can still reach. What remains beyond them is only how the tool window paints, which is the
     * part the manual pass keeps.
     */
    fun testTheDemoFieldTypeNamesItselfAndGroupsItsUsages() {
        val schema = demoSchema()
        val target = targetOnDeclaredName(schema, """<fieldType name="""", "text_general")!!

        assertEquals(
            "field type",
            ElementDescriptionUtil.getElementDescription(target, UsageViewTypeLocation.INSTANCE),
        )
        val groups = ReferencesSearch.search(target).findAll()
            .map { SolrUsageTypeProvider().getUsageType(it.element)?.toString() }
            .toSet()
        assertEquals(setOf("Field declaring this type"), groups)
    }

    /** The same two questions of the dynamic field, whose usage lives in the other file. */
    fun testTheDemoDynamicFieldNamesItselfAndGroupsItsHandlerUsage() {
        val schema = demoSchema()
        val target = targetOnDeclaredName(schema, """<dynamicField name="""", "*_t")!!

        assertEquals(
            "dynamic field",
            ElementDescriptionUtil.getElementDescription(target, UsageViewTypeLocation.INSTANCE),
        )
        val inHandler = ReferencesSearch.search(target).findAll()
            .single { it.element.containingFile.name == "solrconfig.xml" }
        assertEquals(
            "Handler parameter in solrconfig.xml",
            SolrUsageTypeProvider().getUsageType(inHandler.element)?.toString(),
        )
    }

    /**
     * Demo step 27, second half: the caret on `<dynamicField name="*_t">` reaches the `pf` parameter
     * naming `body_t`, which no `<field>` declares and no text search for `*_t` would ever find.
     */
    fun testFindUsagesOnTheDynamicFieldReachesTheNameOnlyItSupplies() {
        val schema = demoSchema()
        val target = targetOnDeclaredName(schema, """<dynamicField name="""", "*_t")
        assertNotNull("no target on the *_t declaration", target)

        val hits = ReferencesSearch.search(target!!).findAll()
        val inHandler = hits.single { it.element.containingFile.name == "solrconfig.xml" }
        val absolute = inHandler.rangeInElement.shiftRight(inHandler.element.textRange.startOffset)
        assertEquals(
            "body_t",
            inHandler.element.containingFile.text.substring(absolute.startOffset, absolute.endOffset),
        )
    }
}
