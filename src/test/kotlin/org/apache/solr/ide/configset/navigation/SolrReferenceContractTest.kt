package org.apache.solr.ide.configset.navigation

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.pom.PomTargetPsiElement
import com.intellij.psi.PsiReferenceBase
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/**
 * The two promises the reference and target layer makes that no feature test exercises.
 *
 * **Neither is exotic, and both would fail silently.** A reference that started offering completion
 * variants would double every entry in the popup, because the completion contributor already owns
 * these positions; a target that reported itself unwritable would make rename refuse with no
 * explanation. Feature tests cannot see either — completion is asked of the contributor, and rename
 * in a fixture never consults writability — so they are asserted directly.
 *
 * [SolrDeclarationReference] states the variants promise once, so these three cases are no longer
 * three implementations to check. They still earn their place: what they pin is that each provider
 * hands back a reference carrying that contract at all, which is the thing a fourth position could
 * quietly get wrong.
 */
class SolrReferenceContractTest : SolrConfigsetTestCase() {

    private fun schema(body: String) = """
        <schema name="products">
          <fieldType name="text_general" class="solr.TextField"/>
          <field name="description" type="text_general"/>
          <field name="text" type="text_general"/>
          $body
        </schema>
    """.trimIndent()

    private fun variantsAtCaret(): Array<Any> =
        (myFixture.getReferenceAtCaretPosition() as PsiReferenceBase<*>).variants

    /**
     * **Completion for these positions belongs to the contributor, and only to it.**
     * `SolrConfigsetCompletionContributor` offers field and type names with what each one matches
     * attached. A reference returning variants as well would put every name in the popup twice.
     */
    fun testAFieldTypeReferenceOffersNoVariantsOfItsOwn() {
        myFixture.configureByText(
            "managed-schema.xml",
            schema("""<field name="name" type="text_gen<caret>eral"/>"""),
        )
        assertEmpty(variantsAtCaret().toList())
    }

    fun testACopyFieldEndOffersNoVariantsOfItsOwn() {
        myFixture.configureByText(
            "managed-schema.xml",
            schema("""<copyField source="descri<caret>ption" dest="text"/>"""),
        )
        assertEmpty(variantsAtCaret().toList())
    }

    fun testAHandlerParameterReferenceOffersNoVariantsOfItsOwn() {
        myFixture.addFileToProject("managed-schema.xml", schema(""))
        myFixture.configureByText(
            "solrconfig.xml",
            """<config><requestHandler name="/select"><lst name="defaults">""" +
                """<str name="qf">descri<caret>ption</str></lst></requestHandler></config>""",
        )
        assertEmpty(variantsAtCaret().toList())
    }

    /**
     * A declaration in an ordinary project file is writable, so rename proceeds.
     *
     * The value of asserting it is the inverse: were this hardcoded to false — or left to a default
     * that answered false — Shift+F6 would decline on every configset in every project, and no
     * rename fixture would say why, because they never reach this question.
     */
    fun testADeclarationInAWritableFileIsRenameable() {
        myFixture.configureByText(
            "managed-schema.xml",
            schema("""<field name="descri<caret>ption2" type="text_general"/>"""),
        )
        val target = TargetElementUtil.findTargetElement(
            myFixture.editor,
            TargetElementUtil.getInstance().allAccepted,
        )
        val declaration = (target as PomTargetPsiElement).target as SolrDeclarationTarget
        assertTrue("an ordinary schema must be renameable", declaration.isWritable)
    }
}
