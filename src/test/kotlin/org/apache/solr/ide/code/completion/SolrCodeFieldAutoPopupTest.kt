package org.apache.solr.ide.code.completion

import com.intellij.testFramework.fixtures.CompletionAutoPopupTester
import com.intellij.util.ThrowableRunnable
import org.apache.solr.ide.code.SolrCodeFixtures
import org.apache.solr.ide.configset.activation.SolrConfigsetTestCase

/**
 * The field list opening by itself while a name is typed into a SolrJ string.
 *
 * **The sandbox report: "it should autocomplete".** Completion worked on Ctrl-Space and never
 * appeared unasked, because inside a string both Java and Kotlin cancel the auto-popup. Typed
 * character by character here, through the platform's own auto-popup harness, since a
 * `completeBasic()` would pass whether or not anything opened by itself.
 *
 * The silences matter as much: an ordinary string keeps the language's behaviour, and the value half
 * of a clause opens nothing.
 */
class SolrCodeFieldAutoPopupTest : SolrConfigsetTestCase() {

    private lateinit var tester: CompletionAutoPopupTester

    override fun setUp() {
        super.setUp()
        tester = CompletionAutoPopupTester(myFixture)
    }

    /** The harness types off the event thread and waits for each popup, as a user would see it. */
    override fun runInDispatchThread(): Boolean = false

    override fun runTestRunnable(testRunnable: ThrowableRunnable<Throwable>) {
        tester.runWithAutoPopupEnabled(testRunnable)
    }

    private fun searching(body: String) = """
        import org.apache.solr.client.solrj.SolrQuery;
        class Search {
            void go() {
                SolrQuery q = new SolrQuery("*:*");
                $body
            }
        }
    """.trimIndent()

    private fun given(name: String, text: String) {
        SolrCodeFixtures.givenSolrJ(myFixture)
        SolrCodeFixtures.givenConfigsetDeclaring(myFixture, "id", "category", "cost")
        myFixture.configureByText(name, text)
    }

    private fun offered(): List<String>? = tester.lookup?.items?.map { it.lookupString }

    // --- where it opens -------------------------------------------------------------------------------

    fun testTypingAFieldNameInAFilterQueryOpensThePopup() {
        given("Search.java", searching("""q.addFilterQuery("<caret>");"""))

        tester.typeWithPauses("c")

        assertSameElements(offered() ?: return fail("no popup opened"), "category", "cost")
    }

    fun testTypingAFieldNameInKotlinOpensThePopup() {
        given(
            "Search.kt",
            """
            import org.apache.solr.client.solrj.SolrQuery
            fun go() {
                val q = SolrQuery("*:*")
                q.addFilterQuery("<caret>")
            }
            """.trimIndent(),
        )

        tester.typeWithPauses("c")

        assertSameElements(offered() ?: return fail("no popup opened"), "category", "cost")
    }

    /** The clause after an operator is a field position again. */
    fun testTypingTheNextClausesFieldOpensThePopup() {
        given("Search.java", searching("""q.setQuery("category:books AND <caret>");"""))

        tester.typeWithPauses("c")

        assertSameElements(offered() ?: return fail("no popup opened"), "category", "cost")
    }

    /** A comma in a field list is where the next name starts. */
    fun testACommaInAFieldListOpensThePopup() {
        given("Search.java", searching("""q.setFields("id<caret>");"""))

        tester.typeWithPauses(",")

        assertSameElements(offered() ?: return fail("no popup opened"), "id", "category", "cost")
    }

    // --- where it stays shut --------------------------------------------------------------------------

    /** After the colon the caret is in a value: no field belongs there, so nothing opens. */
    fun testTypingAValueOpensNothing() {
        given("Search.java", searching("""q.setQuery("category<caret>");"""))

        tester.typeWithPauses(":c")

        assertNull("a popup opened in a value: ${offered()}", tester.lookup)
    }

    /** A string that is not an argument of a Solr call keeps the language's own rule. */
    fun testAnOrdinaryStringOpensNothing() {
        given("Search.java", searching("""String s = "<caret>";"""))

        tester.typeWithPauses("c")

        assertNull("a popup opened in an ordinary string: ${offered()}", tester.lookup)
    }

    /** A comma anywhere else in Java is left as it was. */
    fun testACommaOutsideAFieldListOpensNothing() {
        given("Search.java", searching("""String s = "id<caret>";"""))

        tester.typeWithPauses(",")

        assertNull("a popup opened after an ordinary comma: ${offered()}", tester.lookup)
    }
}
