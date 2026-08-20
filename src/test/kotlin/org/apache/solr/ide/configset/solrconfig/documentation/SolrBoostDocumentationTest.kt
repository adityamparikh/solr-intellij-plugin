package org.apache.solr.ide.configset.solrconfig.documentation

import com.intellij.lang.documentation.ide.IdeDocumentationTargetProvider
import com.intellij.platform.backend.documentation.impl.computeDocumentationBlocking
import com.intellij.platform.backend.presentation.TargetPresentation
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/**
 * The boost popup, reached the way a reader reaches it.
 *
 * **Entering through [IdeDocumentationTargetProvider] rather than through the provider is the point
 * of this class**, not an incidental choice. The platform decides *who answers* at a caret by
 * collecting targets before any documentation provider is consulted, so a test that calls
 * `generateDoc` passes with the position test missing altogether — which is the defect most likely
 * to ship here, and the shape of one that already shipped once when a class popup died in dumb mode.
 */
class SolrBoostDocumentationTest : SolrConfigsetTestCase() {

    private val schema = """
        <schema name="products" version="1.6">
          <fieldType name="text_general" class="solr.TextField"/>
          <field name="name" type="text_general" indexed="true"/>
          <field name="stored_only" type="text_general" indexed="false" docValues="false"/>
        </schema>
    """.trimIndent()

    /** The popup at the caret, reached the way a reader reaches it rather than through a provider. */
    private fun documentationAtCaret(): String? =
        IdeDocumentationTargetProvider.getInstance(project)
            .documentationTargets(myFixture.editor, myFixture.file, myFixture.caretOffset)
            .firstNotNullOfOrNull { computeDocumentationBlocking(it.createPointer())?.html }

    /**
     * The popup's *header*, which the hover path computes alongside the HTML.
     *
     * **Computing the documentation is only half of what the UI asks for**, and the half that
     * worked. `PsiElementDocumentationTarget.computePresentation` runs on the same request and
     * rejects an element it cannot name, so a boost popup whose HTML was perfect still threw in a
     * real editor while every fixture here stayed green.
     */
    private fun presentationAtCaret(): TargetPresentation? =
        IdeDocumentationTargetProvider.getInstance(project)
            .documentationTargets(myFixture.editor, myFixture.file, myFixture.caretOffset)
            .firstOrNull()
            ?.computePresentation()

    private fun popupFor(body: String): String? {
        myFixture.addFileToProject("managed-schema.xml", schema)
        myFixture.configureByText(
            "solrconfig.xml",
            """<config><requestHandler name="/select"><lst name="defaults">$body</lst></requestHandler></config>""",
        )
        return documentationAtCaret()
    }

    fun testACaretInAQueryFieldBoostExplainsIt() {
        val popup = popupFor("""<str name="qf">name^<caret>3 description</str>""")

        assertNotNull("nothing explained the boost", popup)
        assertTrue("expected the term-match sentence: $popup", popup!!.contains("term match"))
        assertTrue("expected the boosted field named: $popup", popup.contains("name"))
    }

    /**
     * The half the HTML tests could not see, and the one that threw in a sandbox.
     *
     * A boost has no name of its own — it is a range inside a text node — so the element carrying it
     * has to supply one anyway, or the platform refuses to present the target and the popup dies
     * with the documentation already computed and correct.
     */
    fun testTheBoostTargetCanBePresented() {
        popupFor("""<str name="qf">name^<caret>3 description</str>""")

        val presentation = presentationAtCaret()
        assertNotNull("the boost target could not be presented", presentation)
        assertEquals("^3", presentation!!.presentableText)
    }

    /**
     * The regression that matters most: an existing popup losing what it proved.
     *
     * Two characters apart, and the field's own popup is the one that already worked. A position
     * test that claims too much is how this feature would break something rather than add to it.
     */
    fun testACaretOnTheFieldNameStillExplainsTheField() {
        val popup = popupFor("""<str name="qf">na<caret>me^3 description</str>""")

        assertNotNull("the field's own popup went missing", popup)
        assertFalse("the boost popup answered where the field's should: $popup", popup!!.contains("term match"))
    }

    fun testAFunctionQueryBoostNamesNoField() {
        val popup = popupFor("""<str name="bf">recip(rord(name),1,1000,1000)^<caret>2.5</str>""")

        assertNotNull("nothing explained the function-query boost", popup)
        assertTrue("expected bf to read as additive: $popup", popup!!.contains("added"))
        assertFalse("a function query must not be described as a field: $popup", popup.contains("Boosts"))
    }

    fun testABoostOnAFieldSolrCannotSearchSaysThereIsNothingToScale() {
        val popup = popupFor("""<str name="qf">stored_only^<caret>2</str>""")

        assertNotNull(popup)
        assertTrue("expected the unsearchable phrasing: $popup", popup!!.contains("nothing for the boost to scale"))
    }

    /** An undeclared name is the unknown-field inspection's finding, not this popup's to repeat. */
    fun testABoostOnAnUndeclaredFieldStillExplainsTheBoost() {
        val popup = popupFor("""<str name="qf">nosuchfield^<caret>2</str>""")

        assertNotNull("the syntax half should survive an unresolvable field", popup)
        assertTrue("expected the boost still explained: $popup", popup!!.contains("term match"))
        assertFalse("an undeclared field must not be named as resolved: $popup", popup.contains("Boosts"))
    }

    /** A `^` outside the boostable parameters is an ordinary character and answers nothing. */
    fun testACaretAfterAMarkerInAPlainParameterExplainsNoBoost() {
        val popup = popupFor("""<str name="fl">name^<caret>3</str>""")

        assertNull("a plain parameter has no boost syntax to explain", popup)
    }
}
