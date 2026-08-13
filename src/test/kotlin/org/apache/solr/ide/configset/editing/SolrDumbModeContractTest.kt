package org.apache.solr.ide.configset.editing

import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.lang.documentation.ide.IdeDocumentationTargetProvider
import com.intellij.platform.backend.documentation.impl.computeDocumentationBlocking
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiRecursiveElementVisitor
import com.intellij.testFramework.DumbModeTestUtils
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase
import org.apache.solr.ide.configset.schema.inspection.SolrDanglingCopyFieldInspection

/**
 * Every contribution that declares itself dumb-aware answers while the project is indexing.
 *
 * **The declaration is a promise, and until this existed nothing checked it was kept.** Almost nothing
 * here reads an index — a configset is text, parsed from files the detector has already found — and
 * these declarations assert that of the surfaces they sit on. What the rule cannot be is *nothing
 * reads an index*: class navigation does, deliberately, and the requirement on it is that it both
 * declines the declaration and guards with `DumbService`. A declaration is cheap to write and stays
 * green forever whether or not the code beneath it grew an unguarded index read, because every other
 * fixture in this suite runs in smart mode.
 *
 * It has been wrong once. A `class` value explained nothing during indexing while the class-value
 * suite was green, because the reference at that caret read the stub index unguarded and the throw
 * escaped into the platform's target collection, killing a popup the plugin could otherwise have
 * filled from an already-parsed file.
 *
 * **One case per mechanism, because the mechanism differs by extension point** and a sweep that
 * checked only one would leave the others declaring whatever they liked: documentation and inlay
 * providers implement the `DumbAware` marker, while inspections and completion override
 * `isDumbAware()`. What is asserted is deliberately shallow — that the surface answers at all during
 * indexing — since what each one *says* is pinned thoroughly by its own suite in smart mode.
 */
class SolrDumbModeContractTest : SolrConfigsetTestCase() {

    private val schema = """
        <schema name="t" version="1.6">
          <fieldType name="string" class="solr.StrField"/>
          <field name="id" type="string"/>
        </schema>
    """.trimIndent()

    /** The popup at the caret, reached the way a reader reaches it rather than through a provider. */
    private fun documentationAtCaret(): String? =
        IdeDocumentationTargetProvider.getInstance(project)
            .documentationTargets(myFixture.editor, myFixture.file, myFixture.caretOffset)
            .firstNotNullOfOrNull { computeDocumentationBlocking(it.createPointer())?.html }

    /** The `DumbAware` marker, on the surface that has already been wrong once — here in the schema. */
    fun testQuickDocumentationExplainsAClassWhileIndexing() {
        myFixture.configureByText(
            "managed-schema.xml",
            """
            <schema name="t" version="1.6">
              <fieldType name="text" class="solr.Text<caret>Field"/>
            </schema>
            """.trimIndent(),
        )
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            val doc = documentationAtCaret()
            assertNotNull("nothing explained the class during indexing", doc)
            assertTrue("expected the class in the popup: $doc", doc!!.contains("TextField"))
        }
    }

    /** The `isDumbAware()` override, on completion. */
    fun testCompletionOffersTheCatalogWhileIndexing() {
        myFixture.configureByText(
            "managed-schema.xml",
            """
            <schema name="t" version="1.6">
              <fieldType name="text" class="<caret>"/>
            </schema>
            """.trimIndent(),
        )
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            myFixture.complete(CompletionType.BASIC)
            val offered = myFixture.lookupElementStrings.orEmpty()
            assertTrue("the catalog needs no index, so indexing must not empty it: $offered", "solr.StrField" in offered)
        }
    }

    /**
     * The same override, on an inspection — the surface where silence reads as a clean file.
     *
     * **Driven directly rather than through `doHighlighting`, which deadlocks here.** The highlighting
     * pipeline waits for smart mode, so asking it for results inside a forced dumb mode waits forever;
     * that is a property of the harness rather than of the plugin. Running the visitor is what the
     * audit actually wants to know in any case — whether the rule's own code survives indexing — since
     * *scheduling* a dumb-aware inspection during indexing is the platform's promise, not this
     * plugin's.
     */
    fun testAnInspectionStillFiresWhileIndexing() {
        myFixture.configureByText(
            "managed-schema.xml",
            schema.replace("</schema>", """  <copyField source="nosuchfield" dest="id"/>${'\n'}</schema>"""),
        )
        val inspection = SolrDanglingCopyFieldInspection()
        assertTrue("this test is meaningless if the rule stops claiming dumb-awareness", inspection.isDumbAware)
        val holder = ProblemsHolder(InspectionManager.getInstance(project), myFixture.file, true)

        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            val visitor = inspection.buildVisitor(holder, true)
            myFixture.file.accept(
                object : PsiRecursiveElementVisitor() {
                    override fun visitElement(element: PsiElement) {
                        element.accept(visitor)
                        super.visitElement(element)
                    }
                },
            )
        }

        val reported = holder.results.map { it.descriptionTemplate }
        assertTrue(
            "a dangling copyField is dangling whether or not the project is indexed: $reported",
            reported.any { "nosuchfield" in it },
        )
    }
}
